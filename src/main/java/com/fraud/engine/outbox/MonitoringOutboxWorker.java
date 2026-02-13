package com.fraud.engine.outbox;

import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.engine.RuleEvaluator;
import com.fraud.engine.kafka.DecisionPublisher;
import com.fraud.engine.ruleset.RulesetRegistry;
import com.fraud.engine.util.RulesetKeyResolver;
import com.fraud.engine.velocity.VelocityService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker that drains Redis Streams outbox, runs MONITORING evaluation,
 * and publishes auth + monitoring decisions to Kafka.
 */
@ApplicationScoped
public class MonitoringOutboxWorker {

    private static final Logger LOG = Logger.getLogger(MonitoringOutboxWorker.class);

    @ConfigProperty(name = "app.outbox.worker.enabled", defaultValue = "false")
    boolean workerEnabled;

    @ConfigProperty(name = "app.outbox.pending-claim-min-idle-ms", defaultValue = "60000")
    long pendingMinIdleMs;

    @ConfigProperty(name = "app.outbox.pending-claim-count", defaultValue = "50")
    int pendingClaimCount;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "outbox-monitoring-worker"));

    @Inject
    OutboxFacade outboxClient;

    @Inject
    RulesetRegistry rulesetRegistry;

    @Inject
    RuleEvaluator ruleEvaluator;

    @Inject
    DecisionPublisher decisionPublisher;

    @Inject
    RulesetKeyResolver rulesetKeyResolver;

    @Inject
    VelocityService velocityService;

    @PostConstruct
    void start() {
        if (workerEnabled) {
            scheduler.scheduleWithFixedDelay(this::safePoll, 100, 100, TimeUnit.MILLISECONDS);
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
            LOG.errorf(e, "Monitoring outbox worker poll failed");
        }
    }

    void poll() {
        if (!workerEnabled) {
            return;
        }
        List<OutboxEntry> reclaimed = outboxClient.claimPendingBatch(pendingMinIdleMs, pendingClaimCount);
        for (OutboxEntry entry : reclaimed) {
            processEntry(entry);
        }
        List<OutboxEntry> entries = outboxClient.readBatch();
        for (OutboxEntry entry : entries) {
            processEntry(entry);
        }
    }

    void processEntry(OutboxEntry entry) {
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
            // Capture velocity snapshot on worker thread (ADR-0017)
            captureVelocitySnapshot(tx, authDecision);

            // Populate transactionContext for Kafka event (moved from AUTH hot path to background worker)
            if (authDecision.getTransactionContext() == null) {
                authDecision.setTransactionContext(tx.toEvaluationContext());
            }

            // Publish AUTH decision first (as before)
            decisionPublisher.publishDecisionAwait(authDecision);

            // Prepare MONITORING evaluation (ADR-0016: country-aware lookup)
            tx.setDecision(authDecision.getDecision());
            String rulesetKey = rulesetKeyResolver.resolve(tx, RuleEvaluator.EVAL_MONITORING);
            String country = tx.getCountryCode();
            Ruleset ruleset = rulesetRegistry.getRulesetWithFallback(country, rulesetKey);
            Decision monitoringDecision = ruleset != null
                    ? ruleEvaluator.evaluate(tx, ruleset)
                    : buildFailOpenDecision(tx, rulesetKey);

            // Share velocity snapshot with MONITORING decision
            if (authDecision.getVelocitySnapshot() != null) {
                monitoringDecision.setVelocitySnapshot(authDecision.getVelocitySnapshot());
            }

            decisionPublisher.publishDecisionAwait(monitoringDecision);

            outboxClient.ack(entry.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process outbox entry %s", entry.getId());
            // Do not ack; entry will be retried.
        }
    }

    private void captureVelocitySnapshot(TransactionContext tx, Decision authDecision) {
        try {
            Map<String, Decision.VelocityResult> snapshot = velocityService.captureVelocitySnapshot(tx);
            authDecision.setVelocitySnapshot(snapshot);
        } catch (Exception ex) {
            LOG.warnf(ex, "Failed to capture velocity snapshot for %s", tx.getTransactionId());
        }
    }

    private Decision buildFailOpenDecision(TransactionContext tx, String rulesetKey) {
        Decision decision = new Decision(tx.getTransactionId(), RuleEvaluator.EVAL_MONITORING);
        decision.setDecision(tx.getDecision() != null ? tx.getDecision() : Decision.DECISION_APPROVE);
        decision.setEngineMode(Decision.MODE_DEGRADED);
        decision.setEngineErrorCode("RULESET_NOT_LOADED");
        decision.setEngineErrorMessage("Ruleset not loaded in registry: " + rulesetKey);
        decision.setRulesetKey(rulesetKey);
        decision.setTransactionContext(tx.toEvaluationContext());
        return decision;
    }
}
