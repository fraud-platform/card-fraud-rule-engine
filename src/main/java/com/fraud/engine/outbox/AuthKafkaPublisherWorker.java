package com.fraud.engine.outbox;

import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.kafka.DecisionPublisher;
import com.fraud.engine.util.EngineMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker that publishes AUTH decisions from the Redis Streams outbox to Kafka.
 * Implements pending recovery via XAUTOCLAIM.
 */
@ApplicationScoped
public class AuthKafkaPublisherWorker {

    private static final Logger LOG = Logger.getLogger(AuthKafkaPublisherWorker.class);

    @ConfigProperty(name = "app.outbox.auth-publisher.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "app.outbox.auth-publisher.poll-interval-ms", defaultValue = "50")
    int pollIntervalMs;

    @ConfigProperty(name = "app.outbox.pending-claim-min-idle-ms", defaultValue = "60000")
    long pendingMinIdleMs;

    @ConfigProperty(name = "app.outbox.pending-claim-count", defaultValue = "50")
    int pendingClaimCount;

    @ConfigProperty(name = "app.outbox.pending-summary-interval-ms", defaultValue = "5000")
    int pendingSummaryIntervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "outbox-auth-publisher-" + UUID.randomUUID()));

    @Inject
    OutboxFacade outboxClient;

    @Inject
    DecisionPublisher decisionPublisher;

    @Inject
    EngineMetrics engineMetrics;

    private volatile long nextPendingSummaryAtMs;

    @PostConstruct
    void start() {
        if (enabled) {
            scheduler.scheduleWithFixedDelay(this::safePoll, 100, Math.max(1, pollIntervalMs), TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    private void safePoll() {
        try {
            poll();
        } catch (Exception e) {
            LOG.errorf(e, "AUTH outbox publisher poll failed");
        }
    }

    void poll() {
        if (!enabled) {
            return;
        }

        refreshPendingSummaryIfDue();

        List<OutboxEntry> reclaimed = outboxClient.claimPendingBatch(pendingMinIdleMs, pendingClaimCount);
        if (!reclaimed.isEmpty()) {
            engineMetrics.incrementPendingReclaimed(reclaimed.size());
            for (OutboxEntry entry : reclaimed) {
                processEntry(entry);
            }
        }

        List<OutboxEntry> entries = outboxClient.readBatch();
        for (OutboxEntry entry : entries) {
            processEntry(entry);
        }
    }

    private void processEntry(OutboxEntry entry) {
        OutboxEvent event = entry.getEvent();
        if (event == null) {
            LOG.warn("Outbox entry missing payload, acking");
            outboxClient.ack(entry.getId());
            return;
        }

        TransactionContext tx = event.getTransaction();
        Decision authDecision = event.getAuthDecision();
        if (tx == null || authDecision == null) {
            LOG.warn("Outbox event missing transaction or auth decision, acking");
            outboxClient.ack(entry.getId());
            return;
        }

        try {
            if (authDecision.getTransactionContext() == null) {
                authDecision.setTransactionContext(tx.toEvaluationContext());
            }

            long start = System.nanoTime();
            decisionPublisher.publishDecisionAwait(authDecision);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            engineMetrics.incrementKafkaPublishSuccess(latencyMs);
            updateOutboxLag(entry.getId());

            outboxClient.ack(entry.getId());
        } catch (Exception e) {
            engineMetrics.incrementKafkaPublishFailure();
            LOG.errorf(e, "Failed to publish AUTH decision for outbox entry %s", entry.getId());
            // Do not ack; entry will be retried.
        }
    }

    private void updateOutboxLag(String entryId) {
        long entryMs = parseEntryTimestampMs(entryId);
        if (entryMs > 0) {
            long lagSeconds = Math.max(0, (Instant.now().toEpochMilli() - entryMs) / 1000);
            engineMetrics.setOutboxLagSeconds(lagSeconds);
        }
    }

    private long parseEntryTimestampMs(String entryId) {
        if (entryId == null) {
            return 0;
        }
        int dash = entryId.indexOf('-');
        if (dash <= 0) {
            return 0;
        }
        try {
            return Long.parseLong(entryId.substring(0, dash));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refreshPendingSummaryIfDue() {
        long now = System.currentTimeMillis();
        if (now < nextPendingSummaryAtMs) {
            return;
        }
        nextPendingSummaryAtMs = now + Math.max(1000, pendingSummaryIntervalMs);
        OutboxPendingSummary summary = outboxClient.pendingSummary();
        if (summary != null) {
            engineMetrics.setOutboxPendingSummary(summary.getTotalPending(), summary.getOldestIdleMs());
        }
    }
}
