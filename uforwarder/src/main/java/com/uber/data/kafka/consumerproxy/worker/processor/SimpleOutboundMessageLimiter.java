package com.uber.data.kafka.consumerproxy.worker.processor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.uber.data.kafka.consumerproxy.common.StructuredLogging;
import com.uber.data.kafka.consumerproxy.common.StructuredTags;
import com.uber.data.kafka.consumerproxy.worker.limiter.AdaptiveInflightLimiter;
import com.uber.data.kafka.consumerproxy.worker.limiter.AsyncInflightLimiterAdapter;
import com.uber.data.kafka.consumerproxy.worker.limiter.InflightLimiter;
import com.uber.data.kafka.consumerproxy.worker.limiter.LongFixedInflightLimiter;
import com.uber.data.kafka.consumerproxy.worker.limiter.WindowedAggregator;
import com.uber.data.kafka.datatransfer.Job;
import com.uber.data.kafka.datatransfer.common.CoreInfra;
import com.uber.data.kafka.datatransfer.common.RoutingUtils;
import com.uber.data.kafka.datatransfer.common.StructuredFields;
import com.uber.m3.tally.Scope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OutboundMessageLimiter manages the number of outbound messages for the pipeline. */
public class SimpleOutboundMessageLimiter implements OutboundMessageLimiter {
  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleOutboundMessageLimiter.class);
  private static final int N_BUCKETS_PER_MINUTE = 10;
  private static final int BUCKET_SECONDS_PER_MINUTE = 60 / N_BUCKETS_PER_MINUTE;
  private static final int DEFAULT_MAX_INFLIGHT = 1000;
  private static final int BACKPRESSURE_ADAPTIVE_LIMITER_MINUTE = 30;
  protected final Job jobTemplate;
  private final AdaptiveInflightLimiter.Builder adaptiveInfligtLimiterBuilder;
  private final LongFixedInflightLimiter longFixedInflightLimiter;
  private final AdaptiveInflightLimiter adaptiveInflightLimiter;
  private final AdaptiveInflightLimiter shadowAdaptiveInflightLimiter;
  private final CoreInfra infra;
  private final Map<TopicPartition, ScopeAndInflight> topicPartitionToScopeAndInflight;
  private final InflightTracker inflightTracker;
  private final AsyncInflightLimiterAdapter asyncStaticLimiterAdapter;
  private final AsyncInflightLimiterAdapter asyncAdaptiveLimiterAdapter;
  private final AsyncInflightLimiterAdapter asyncShadowAdaptiveLimiterAdapter;
  private final int maxOutboundCacheCount;
  private final Ticker ticker;
  // time measured in nanoseconds, use adaptive limit if current time is before
  // useAdaptiveLimiterUntilTick
  private volatile long useAdaptiveLimiterUntilTick;

  protected SimpleOutboundMessageLimiter(Builder builder, Job jobTemplate) {
    this.longFixedInflightLimiter = new LongFixedInflightLimiter(0);
    this.asyncStaticLimiterAdapter = AsyncInflightLimiterAdapter.of(longFixedInflightLimiter);
    this.adaptiveInfligtLimiterBuilder = builder.adaptiveInfligtLimiterBuilder;
    this.infra = builder.infra;
    this.adaptiveInflightLimiter = buildLimiter(true);
    this.asyncAdaptiveLimiterAdapter = AsyncInflightLimiterAdapter.of(adaptiveInflightLimiter);
    this.shadowAdaptiveInflightLimiter = buildLimiter(false);
    this.asyncShadowAdaptiveLimiterAdapter =
        AsyncInflightLimiterAdapter.of(shadowAdaptiveInflightLimiter);
    this.topicPartitionToScopeAndInflight = new ConcurrentHashMap<>();
    this.inflightTracker = new InflightTracker();
    this.jobTemplate = jobTemplate;
    this.maxOutboundCacheCount = builder.maxOutboundCacheCount;
    this.ticker = builder.ticker;
    this.useAdaptiveLimiterUntilTick = ticker.read();
  }

  /** Closes the limiter */
  @Override
  public void close() {
    adaptiveInflightLimiter.close();
    longFixedInflightLimiter.close();
    asyncStaticLimiterAdapter.close();
    asyncAdaptiveLimiterAdapter.close();

    LOGGER.info(MetricNames.OUTBOUND_CACHE_CLOSE_SUCCESS);
  }

  @Override
  public void updateLimit(int limit) {
    longFixedInflightLimiter.updateLimit(limit);
    boolean dryRunAdaptiveLimiter;
    int maxLimit;
    if (limit > 0) {
      // use static limit
      dryRunAdaptiveLimiter = true;
      maxLimit = limit;
    } else {
      // use adaptive limit
      dryRunAdaptiveLimiter = false;
      maxLimit =
          Math.max(
              DEFAULT_MAX_INFLIGHT,
              maxOutboundCacheCount * topicPartitionToScopeAndInflight.size());
    }
    adaptiveInflightLimiter.setMaxInflight(maxLimit);
    shadowAdaptiveInflightLimiter.setMaxInflight(maxLimit);
    LOGGER.info(
        "updated inflight limit to " + limit,
        StructuredLogging.kafkaGroup(jobTemplate.getKafkaConsumerTask().getConsumerGroup()),
        StructuredLogging.kafkaTopic(jobTemplate.getKafkaConsumerTask().getTopic()));
  }

  @Override
  public void publishMetrics() {
    int nPartitions = topicPartitionToScopeAndInflight.size();
    topicPartitionToScopeAndInflight.forEach(
        (tp, scopeAndInflight) -> {
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_SIZE)
              .update(scopeAndInflight.inflight.intValue());
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_SIZE_ONE_MINUTE_MAX)
              .update(inflightTracker.oneMinuteMax() / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_SIZE_ONE_MINUTE_MIN)
              .update(inflightTracker.oneMinuteMin() / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_LIMIT)
              .update(longFixedInflightLimiter.getMetrics().getLimit() / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_QUEUE)
              .update(
                  asyncStaticLimiterAdapter.getMetrics().getAsyncQueueSize()
                      / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_ADAPTIVE_LIMIT)
              .update(adaptiveInflightLimiter.getMetrics().getLimit() / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_SHADOW_ADAPTIVE_LIMIT)
              .update(shadowAdaptiveInflightLimiter.getMetrics().getLimit() / (double) nPartitions);
          scopeAndInflight
              .scope
              .gauge(MetricNames.OUTBOUND_CACHE_ADAPTIVE_LIMIT_ENABLED)
              .update(useFixedLimiter() ? 0 : 1);
          publishMetrics(
              scopeAndInflight.scope,
              adaptiveInflightLimiter.getMetrics().getExtraMetrics(),
              MetricNames.OUTBOUND_CACHE_ADAPTIVE_LIMIT);
          publishMetrics(
              scopeAndInflight.scope,
              shadowAdaptiveInflightLimiter.getMetrics().getExtraMetrics(),
              MetricNames.OUTBOUND_CACHE_SHADOW_ADAPTIVE_LIMIT);
        });
  }

  /**
   * Start processing a topic partition consumer job note, initialization and acquirePermit could
   * happen asynchronously.
   *
   * @param job the job
   */
  @Override
  public void init(Job job) {
    TopicPartition topicPartition =
        new TopicPartition(
            job.getKafkaConsumerTask().getTopic(), job.getKafkaConsumerTask().getPartition());

    Scope topicPartitionScope =
        infra
            .scope()
            .tagged(
                ImmutableMap.of(
                    StructuredFields.URI,
                    RoutingUtils.extractAddress(job.getRpcDispatcherTask().getUri()),
                    StructuredFields.KAFKA_GROUP,
                    job.getKafkaConsumerTask().getConsumerGroup(),
                    StructuredFields.KAFKA_CLUSTER,
                    job.getKafkaConsumerTask().getCluster(),
                    StructuredFields.KAFKA_TOPIC,
                    job.getKafkaConsumerTask().getTopic(),
                    StructuredFields.KAFKA_PARTITION,
                    Integer.toString(job.getKafkaConsumerTask().getPartition())));

    topicPartitionToScopeAndInflight.computeIfAbsent(
        topicPartition, o -> new ScopeAndInflight(topicPartitionScope, job));
  }

  /**
   * Cancel one topic partition consumer job
   *
   * @param job
   */
  @Override
  public void cancel(Job job) {
    TopicPartition topicPartition =
        new TopicPartition(
            job.getKafkaConsumerTask().getTopic(), job.getKafkaConsumerTask().getPartition());
    topicPartitionToScopeAndInflight.remove(topicPartition);
  }

  /** Cancel all running topic partition consumer jobs */
  @Override
  public void cancelAll() {
    topicPartitionToScopeAndInflight.clear();
  }

  /** Gets if the job exists * */
  @Override
  public boolean contains(Job job) {
    final String topic = job.getKafkaConsumerTask().getTopic();
    final int partition = job.getKafkaConsumerTask().getPartition();
    return topicPartitionToScopeAndInflight.containsKey(new TopicPartition(topic, partition));
  }

  /**
   * tries to get a permit from the inflight limiter TODO: cleanup after migrate to async permit
   * mode
   */
  @Override
  public InflightLimiter.Permit acquirePermit(ProcessorMessage processorMessage) {
    TopicPartition topicPartition =
        new TopicPartition(
            processorMessage.getPhysicalMetadata().getTopic(),
            processorMessage.getPhysicalMetadata().getPartition());
    ScopeAndInflight scopeAndInflight = topicPartitionToScopeAndInflight.get(topicPartition);
    if (scopeAndInflight == null) {
      LOGGER.error(
          "a topic partition has not been assigned to this message limiter",
          StructuredLogging.kafkaTopic(topicPartition.topic()),
          StructuredLogging.kafkaPartition(topicPartition.partition()));
      throw new IllegalStateException(
          String.format(
              "topic-partition %s has not been assigned to this message limiter", topicPartition));
    }
    final String cluster = scopeAndInflight.job.getKafkaConsumerTask().getCluster();
    final String group = scopeAndInflight.job.getKafkaConsumerTask().getConsumerGroup();
    final String topic = scopeAndInflight.job.getKafkaConsumerTask().getTopic();
    final int partition = scopeAndInflight.job.getKafkaConsumerTask().getPartition();
    final String rpcUri = scopeAndInflight.job.getRpcDispatcherTask().getUri();
    final boolean useFixedLimiter = useFixedLimiter();
    try {
      // TODO (T4576171): use tryAcquire instead of blocking forever on lock
      NestedPermitBuilder permitBuilder = new NestedPermitBuilder();
      // if limit <= 0, fixedInflightLimiter returns NoopPermit, adaptiveInflightLimiter takes
      // effect
      // else, adaptiveInflightLimiter returns NoopPermit, fixedInflightLimiter takes effect
      // shadowAdaptiveInflightLimiter always run in dryrun mode for algorithm performance tuning
      permitBuilder.withPermit(longFixedInflightLimiter.acquire(!useFixedLimiter));
      permitBuilder.withPermit(adaptiveInflightLimiter.acquire(useFixedLimiter));
      permitBuilder.withPermit(shadowAdaptiveInflightLimiter.acquire(true));
      permitBuilder.withScopeAndInflight(scopeAndInflight);
      return permitBuilder.build();
    } catch (InterruptedException e) {
      LOGGER.error(
          "the thread is interrupted while acquiring a permit",
          StructuredLogging.kafkaCluster(cluster),
          StructuredLogging.kafkaGroup(group),
          StructuredLogging.kafkaTopic(topic),
          StructuredLogging.kafkaPartition(partition),
          StructuredLogging.destination(rpcUri));
      infra
          .scope()
          .tagged(
              StructuredTags.builder()
                  .setDestination(rpcUri)
                  .setKafkaCluster(cluster)
                  .setKafkaGroup(group)
                  .setKafkaTopic(topic)
                  .setKafkaPartition(partition)
                  .build())
          .counter(MetricNames.OUTBOUND_CACHE_THREAD_INTERRUPTED)
          .inc(1);
      return InflightLimiter.NoopPermit.INSTANCE;
    }
  }

  /** tries to get a permit from the inflight limiter */
  @Override
  public CompletableFuture<InflightLimiter.Permit> acquirePermitAsync(
      ProcessorMessage processorMessage) {
    TopicPartition topicPartition =
        new TopicPartition(
            processorMessage.getPhysicalMetadata().getTopic(),
            processorMessage.getPhysicalMetadata().getPartition());
    ScopeAndInflight scopeAndInflight = topicPartitionToScopeAndInflight.get(topicPartition);
    if (scopeAndInflight == null) {
      LOGGER.error(
          "a topic partition has not been assigned to this message limiter",
          StructuredLogging.kafkaTopic(topicPartition.topic()),
          StructuredLogging.kafkaPartition(topicPartition.partition()));
      throw new IllegalStateException(
          String.format(
              "topic-partition %s has not been assigned to this message limiter", topicPartition));
    }
    final boolean useFixedLimiter = useFixedLimiter();
    NestedPermitBuilder permitBuilder = new NestedPermitBuilder();
    // if limit <= 0, fixedInflightLimiter returns NoopPermit, adaptiveInflightLimiter takes
    // effect
    // else, adaptiveInflightLimiter returns NoopPermit, fixedInflightLimiter takes effect
    // shadowAdaptiveInflightLimiter always run in dryrun mode for algorithm performance tuning
    return acquireAsync(processorMessage, asyncStaticLimiterAdapter, !useFixedLimiter)
        .thenCompose(
            longPermit ->
                acquireAsync(processorMessage, asyncAdaptiveLimiterAdapter, useFixedLimiter)
                    .thenCompose(
                        adaptivePermit ->
                            acquireAsync(processorMessage, asyncShadowAdaptiveLimiterAdapter, true)
                                .thenApply(
                                    shadowPermit ->
                                        permitBuilder
                                            .withPermit(longPermit)
                                            .withPermit(adaptivePermit)
                                            .withPermit(shadowPermit)
                                            .withScopeAndInflight(scopeAndInflight)
                                            .build())));
  }

  /**
   * Tests if to use fixed limiter
   *
   * @return the boolean
   */
  @VisibleForTesting
  protected boolean useFixedLimiter() {
    // use fixed limit only when
    // there is valid fixed limit
    // current time is after the time to use adaptive limiter
    return longFixedInflightLimiter.getMetrics().getLimit() > 0
        && useAdaptiveLimiterUntilTick < ticker.read();
  }

  /**
   * When message dropped by consumer service, enable adaptive flow control for limited time to
   * reduce pressure to down stream
   */
  private void onBackpressure() {
    useAdaptiveLimiterUntilTick =
        ticker.read() + TimeUnit.MINUTES.toNanos(BACKPRESSURE_ADAPTIVE_LIMITER_MINUTE);
  }

  private CompletableFuture<InflightLimiter.Permit> acquireAsync(
      ProcessorMessage processorMessage, AsyncInflightLimiterAdapter limiter, boolean dryRun) {
    TopicPartitionOffset topicPartitionOffset = processorMessage.getPhysicalMetadata();
    return infra
        .contextManager()
        .wrap(
            processorMessage
                .getStub()
                .withFuturePermit(
                    limiter.acquireAsync(
                        topicPartitionOffset.getPartition(),
                        topicPartitionOffset.getOffset(),
                        dryRun)));
  }

  Collection<Job> jobs() {
    return topicPartitionToScopeAndInflight
        .values()
        .stream()
        .map(item -> item.job)
        .collect(Collectors.toSet());
  }

  private static void publishMetrics(Scope scope, Map<String, Double> metrics, String baseName) {
    for (Map.Entry<String, Double> entry : metrics.entrySet()) {
      scope.gauge(baseName + "." + entry.getKey()).update(entry.getValue());
    }
  }

  private AdaptiveInflightLimiter buildLimiter(boolean logEnabled) {
    return adaptiveInfligtLimiterBuilder.withLogEnabled(logEnabled).build();
  }

  private class NestedPermit implements InflightLimiter.Permit {
    private final List<InflightLimiter.Permit> permits;
    private final Optional<ScopeAndInflight> scopeAndInflight;

    NestedPermit(
        List<InflightLimiter.Permit> permits, Optional<ScopeAndInflight> scopeAndInflight) {
      this.permits = ImmutableList.copyOf(permits);
      this.scopeAndInflight = scopeAndInflight;
      inflightTracker.increase();
      scopeAndInflight.ifPresent(obj -> obj.inflight.incrementAndGet());
    }

    @Override
    public boolean complete(InflightLimiter.Result result) {
      permits.stream().forEach(permit -> permit.complete(result));
      inflightTracker.decrease();
      scopeAndInflight.ifPresent(obj -> obj.inflight.decrementAndGet());
      if (result == InflightLimiter.Result.Dropped) {
        onBackpressure();
      }
      return true;
    }
  }

  private class NestedPermitBuilder {
    private List<InflightLimiter.Permit> permits = new ArrayList<>();
    private Optional<ScopeAndInflight> scopeAndInflight = Optional.empty();

    /**
     * Adds a {@link InflightLimiter.Permit}
     *
     * @param permit
     * @return
     */
    private NestedPermitBuilder withPermit(InflightLimiter.Permit permit) {
      this.permits.add(permit);
      return this;
    }

    /**
     * Set a {@link ScopeAndInflight}
     *
     * @param scopeAndInflight
     * @return
     */
    private NestedPermitBuilder withScopeAndInflight(ScopeAndInflight scopeAndInflight) {
      this.scopeAndInflight = Optional.of(scopeAndInflight);
      return this;
    }

    NestedPermit build() {
      return new NestedPermit(permits, scopeAndInflight);
    }
  }

  private static class MetricNames {

    static final String OUTBOUND_CACHE_SIZE = "processor.outbound-cache.size";
    static final String OUTBOUND_CACHE_SIZE_ONE_MINUTE_MAX =
        "processor.outbound-cache.size.one-minute-max";
    static final String OUTBOUND_CACHE_SIZE_ONE_MINUTE_MIN =
        "processor.outbound-cache.size.one-minute-min";
    static final String OUTBOUND_CACHE_THREAD_INTERRUPTED =
        "processor.outbound-cache.thread.interrupted";
    static final String OUTBOUND_CACHE_CLOSE_SUCCESS = "processor.outbound-cache.close.success";
    static final String OUTBOUND_CACHE_LIMIT = "processor.outbound-cache.limit";
    static final String OUTBOUND_CACHE_QUEUE = "processor.outbound-cache.queue";
    static final String OUTBOUND_CACHE_ADAPTIVE_LIMIT = "processor.outbound-cache.adaptive-limit";
    static final String OUTBOUND_CACHE_SHADOW_ADAPTIVE_LIMIT =
        "processor.outbound-cache.shadow-adaptive-limit";
    static final String OUTBOUND_CACHE_ADAPTIVE_LIMIT_ENABLED =
        "processor.outbound-cache.adaptive-limit-enabled";
  }

  /** TopicPartition related scope and inflight counter; */
  private static class ScopeAndInflight {
    private Scope scope;
    private AtomicInteger inflight;
    private Job job;

    ScopeAndInflight(Scope scope, Job job) {
      this.scope = scope;
      this.inflight = new AtomicInteger();
      this.job = job;
    }
  }

  /** Tracks max/min inflight in one minute window */
  private static class InflightTracker {
    private final WindowedAggregator<Long> oneMinuteMaxInflight =
        WindowedAggregator.newBuilder()
            .withBucketDuration(BUCKET_SECONDS_PER_MINUTE, TimeUnit.SECONDS)
            .withNBuckets(N_BUCKETS_PER_MINUTE)
            .of((a, b) -> Math.max(a, b));
    private final WindowedAggregator<Long> oneMinuteMinInflight =
        WindowedAggregator.newBuilder()
            .withBucketDuration(BUCKET_SECONDS_PER_MINUTE, TimeUnit.SECONDS)
            .withNBuckets(N_BUCKETS_PER_MINUTE)
            .of((a, b) -> Math.min(a, b));
    private final AtomicLong inflight = new AtomicLong(0);

    private void increase() {
      oneMinuteMinInflight.put(inflight.get());
      inflight.incrementAndGet();
      oneMinuteMaxInflight.put(inflight.get());
    }

    private void decrease() {
      inflight.decrementAndGet();
    }

    private long oneMinuteMax() {
      return oneMinuteMaxInflight.get(0L);
    }

    private long oneMinuteMin() {
      return oneMinuteMinInflight.get(0L);
    }
  }

  public static class Builder implements OutboundMessageLimiter.Builder {
    protected final CoreInfra infra;
    protected final AdaptiveInflightLimiter.Builder adaptiveInfligtLimiterBuilder;
    protected final boolean experimentalLimiterEnabled;
    private int maxOutboundCacheCount = 1000;
    private Ticker ticker = Ticker.systemTicker();

    public Builder(
        CoreInfra infra,
        AdaptiveInflightLimiter.Builder adaptiveInfligtLimiterBuilder,
        boolean experimentalLimiterEnabled) {
      this.infra = infra;
      this.adaptiveInfligtLimiterBuilder = adaptiveInfligtLimiterBuilder;
      this.experimentalLimiterEnabled = experimentalLimiterEnabled;
    }

    public Builder withMaxOutboundCacheCount(int maxOutboundCacheCount) {
      if (maxOutboundCacheCount <= 0) {
        throw new IllegalArgumentException("maxInflightPerPartition must be positive");
      }
      this.maxOutboundCacheCount = maxOutboundCacheCount;
      return this;
    }

    public Builder withTicker(Ticker ticker) {
      this.ticker = ticker;
      return this;
    }

    @Override
    public OutboundMessageLimiter build(Job job) {
      return new SimpleOutboundMessageLimiter(this, job);
    }
  }
}
