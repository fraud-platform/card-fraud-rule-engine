package com.fraud.engine.outbox;

import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.engine.RuleEvaluator;
import com.fraud.engine.kafka.DecisionPublisher;
import com.fraud.engine.ruleset.RulesetRegistry;
import com.fraud.engine.util.RulesetKeyResolver;
import com.fraud.engine.velocity.VelocityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MonitoringOutboxWorkerTest {

    private InMemoryOutboxClient inMemoryOutbox;
    private OutboxFacade facade;
    private MonitoringOutboxWorker worker;
    private DecisionPublisher publisher;
    private RuleEvaluator ruleEvaluator;
    private RulesetRegistry rulesetRegistry;
    private RulesetKeyResolver keyResolver;
    private VelocityService velocityService;

    @BeforeEach
    void setup() throws Exception {
        inMemoryOutbox = new InMemoryOutboxClient();
        setField(inMemoryOutbox, "mode", "in-memory");
        inMemoryOutbox.init();

        facade = new OutboxFacade();
        setField(facade, "mode", "in-memory");
        setField(facade, "inMemoryOutbox", inMemoryOutbox);
        setField(facade, "redisOutbox", null); // unused in test

        publisher = Mockito.mock(DecisionPublisher.class);
        ruleEvaluator = Mockito.mock(RuleEvaluator.class);
        rulesetRegistry = Mockito.mock(RulesetRegistry.class);
        keyResolver = new RulesetKeyResolver();
        velocityService = Mockito.mock(VelocityService.class);

        worker = new MonitoringOutboxWorker();
        setField(worker, "workerEnabled", true);
        setField(worker, "outboxClient", facade);
        setField(worker, "decisionPublisher", publisher);
        setField(worker, "ruleEvaluator", ruleEvaluator);
        setField(worker, "rulesetRegistry", rulesetRegistry);
        setField(worker, "rulesetKeyResolver", keyResolver);
        setField(worker, "velocityService", velocityService);
    }

    @Test
    void processesAuthThenMonitoring() {
        TransactionContext tx = new TransactionContext();
        tx.setTransactionId("txn-123");
        tx.setTransactionType("AUTHORIZATION");

        Decision authDecision = new Decision("txn-123", RuleEvaluator.EVAL_AUTH);
        authDecision.setDecision(Decision.DECISION_APPROVE);
        authDecision.setRulesetKey("CARD_AUTH");
        authDecision.setTransactionContext(tx.toEvaluationContext());

        OutboxEvent event = new OutboxEvent(tx, authDecision);
        facade.append(event);
        List<OutboxEntry> entries = facade.readBatch();
        assertEquals(1, entries.size());

        Ruleset monitoringRuleset = new Ruleset("CARD_MONITORING", 1);
        monitoringRuleset.setEvaluationType(RuleEvaluator.EVAL_MONITORING);
        when(rulesetRegistry.getRulesetWithFallback(any(), eq("CARD_MONITORING"))).thenReturn(monitoringRuleset);

        Decision monitoringDecision = new Decision("txn-123", RuleEvaluator.EVAL_MONITORING);
        monitoringDecision.setDecision(Decision.DECISION_APPROVE);
        when(ruleEvaluator.evaluate(any(TransactionContext.class), any(Ruleset.class))).thenReturn(monitoringDecision);

        worker.processEntry(entries.get(0));

        verify(publisher, times(2)).publishDecisionAwait(any(Decision.class));
        verify(ruleEvaluator, times(1)).evaluate(any(TransactionContext.class), any(Ruleset.class));
    }

    @Test
    void missingPayloadIsAckedAndSkipped() {
        OutboxEntry entry = new OutboxEntry("1-0", null);

        worker.processEntry(entry);

        verify(publisher, never()).publishDecisionAwait(any());
    }

    @Test
    void missingRulesetBuildsDegradedMonitoringDecision() {
        TransactionContext tx = new TransactionContext();
        tx.setTransactionId("txn-404");
        tx.setTransactionType("AUTHORIZATION");

        Decision authDecision = new Decision("txn-404", RuleEvaluator.EVAL_AUTH);
        authDecision.setDecision(Decision.DECISION_DECLINE);
        authDecision.setRulesetKey("CARD_AUTH");
        authDecision.setTransactionContext(tx.toEvaluationContext());

        OutboxEntry entry = new OutboxEntry("1-1", new OutboxEvent(tx, authDecision));
        when(rulesetRegistry.getRulesetWithFallback(any(), eq("CARD_MONITORING"))).thenReturn(null);

        worker.processEntry(entry);

        verify(publisher, times(2)).publishDecisionAwait(any(Decision.class));
        verify(ruleEvaluator, never()).evaluate(any(TransactionContext.class), any(Ruleset.class));
    }

    @Test
    void publisherFailureDoesNotAckEntry() {
        TransactionContext tx = new TransactionContext();
        tx.setTransactionId("txn-fail");
        tx.setTransactionType("AUTHORIZATION");

        Decision authDecision = new Decision("txn-fail", RuleEvaluator.EVAL_AUTH);
        authDecision.setDecision(Decision.DECISION_APPROVE);
        authDecision.setRulesetKey("CARD_AUTH");
        authDecision.setTransactionContext(tx.toEvaluationContext());

        OutboxEntry entry = new OutboxEntry("1-2", new OutboxEvent(tx, authDecision));
        doThrow(new RuntimeException("kafka down")).when(publisher).publishDecisionAwait(authDecision);

        worker.processEntry(entry);

        verify(publisher, times(1)).publishDecisionAwait(authDecision);
        verify(ruleEvaluator, never()).evaluate(any(TransactionContext.class), any(Ruleset.class));
    }

    @Test
    void pollSkipsReadWhenWorkerDisabled() throws Exception {
        OutboxFacade mockFacade = Mockito.mock(OutboxFacade.class);
        setField(worker, "workerEnabled", false);
        setField(worker, "outboxClient", mockFacade);

        worker.poll();

        verify(mockFacade, never()).readBatch();
    }

    @Test
    void missingTransactionOrAuthDecisionIsAckedAndSkipped() throws Exception {
        OutboxFacade mockFacade = Mockito.mock(OutboxFacade.class);
        setField(worker, "outboxClient", mockFacade);

        Decision authDecision = new Decision("txn-missing", RuleEvaluator.EVAL_AUTH);
        OutboxEntry missingTx = new OutboxEntry("3-1", new OutboxEvent(null, authDecision));
        worker.processEntry(missingTx);

        verify(mockFacade).ack("3-1");
        verify(publisher, never()).publishDecisionAwait(any());
        verify(ruleEvaluator, never()).evaluate(any(TransactionContext.class), any(Ruleset.class));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
