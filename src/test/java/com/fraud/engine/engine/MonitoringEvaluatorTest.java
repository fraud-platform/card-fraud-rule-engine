package com.fraud.engine.engine;

import com.fraud.engine.config.EvaluationConfig;
import com.fraud.engine.domain.Condition;
import com.fraud.engine.domain.DebugInfo;
import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Rule;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.domain.VelocityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringEvaluatorTest {

    @Mock
    VelocityEvaluator velocityEvaluator;

    private MonitoringEvaluator evaluator;
    private EvaluationConfig evaluationConfig;

    @BeforeEach
    void setUp() throws Exception {
        evaluator = new MonitoringEvaluator();
        evaluationConfig = new EvaluationConfig();
        setField(evaluator, "velocityEvaluator", velocityEvaluator);
        setField(evaluator, "evaluationConfig", evaluationConfig);
    }

    @Test
    void evaluatePreservesProvidedDecisionAndCollectsMatches() {
        Ruleset ruleset = ruleset(rule("r1", "REVIEW", condition("amount", "gt", 100)));
        TransactionContext tx = transaction("txn-1", "DECLINE", 150);
        Decision decision = new Decision("txn-1", RuleEvaluator.EVAL_MONITORING);
        decision.setEngineMode(Decision.MODE_NORMAL);

        evaluator.evaluate(context(tx, ruleset, decision, false, null));

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getMatchedRules()).hasSize(1);
        assertThat(decision.getEngineErrorCode()).isNull();
    }

    @Test
    void evaluateInvalidDecisionSetsDegraded() {
        Ruleset ruleset = ruleset(rule("r1", "REVIEW", condition("amount", "gt", 100)));
        TransactionContext tx = transaction("txn-2", "REVIEW", 150);
        Decision decision = new Decision("txn-2", RuleEvaluator.EVAL_MONITORING);
        decision.setEngineMode(Decision.MODE_NORMAL);

        evaluator.evaluate(context(tx, ruleset, decision, false, null));

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_DEGRADED);
        assertThat(decision.getEngineErrorCode()).isEqualTo("INVALID_DECISION");
    }

    @Test
    void velocityExceededUsesVelocityAction() {
        Rule rule = rule("velocity-rule", "APPROVE", condition("amount", "gt", 100));
        VelocityConfig velocity = new VelocityConfig("card_hash", 60, 2);
        velocity.setAction("DECLINE");
        rule.setVelocity(velocity);
        Ruleset ruleset = ruleset(rule);

        TransactionContext tx = transaction("txn-3", "APPROVE", 150);
        Decision decision = new Decision("txn-3", RuleEvaluator.EVAL_MONITORING);
        Decision.VelocityResult exceeded = new Decision.VelocityResult("card_hash", "hash", 3, 2, 60);
        when(velocityEvaluator.checkVelocity(any(), any(), any())).thenReturn(exceeded);

        evaluator.evaluate(context(tx, ruleset, decision, false, null));

        assertThat(decision.getVelocityResults()).containsKey("velocity-rule");
        assertThat(decision.getMatchedRules()).hasSize(1);
        assertThat(decision.getMatchedRules().get(0).getAction()).isEqualTo("DECLINE");
    }

    @Test
    void replayModeUsesReadOnlyVelocityCheck() {
        Rule rule = rule("velocity-rule", "APPROVE", condition("amount", "gt", 100));
        rule.setVelocity(new VelocityConfig("card_hash", 60, 2));
        Ruleset ruleset = ruleset(rule);

        TransactionContext tx = transaction("txn-4", "APPROVE", 150);
        Decision decision = new Decision("txn-4", RuleEvaluator.EVAL_MONITORING);
        when(velocityEvaluator.checkVelocityReadOnly(any(), any(), any()))
                .thenReturn(new Decision.VelocityResult("card_hash", "hash", 1, 2, 60));

        evaluator.evaluate(context(tx, ruleset, decision, true, null));

        verify(velocityEvaluator).checkVelocityReadOnly(any(), any(), any());
        verify(velocityEvaluator, never()).checkVelocity(any(), any(), any());
    }

    @Test
    void debugBuilderTracksLimitedConditionEvaluations() {
        evaluationConfig.includeFieldValues = true;
        evaluationConfig.maxConditionEvaluations = 1;

        Rule rule = rule("r1", "REVIEW",
                condition("amount", "gt", 100),
                condition("amount", "lt", 500));
        Ruleset ruleset = ruleset(rule);
        TransactionContext tx = transaction("txn-5", "APPROVE", 150);
        Decision decision = new Decision("txn-5", RuleEvaluator.EVAL_MONITORING);
        DebugInfo.Builder debugBuilder = new DebugInfo.Builder();

        evaluator.evaluate(context(tx, ruleset, decision, false, debugBuilder));

        assertThat(debugBuilder.getConditionEvaluationCount()).isEqualTo(1);
        DebugInfo debugInfo = debugBuilder.build();
        assertThat(debugInfo.getFieldValues()).containsKey("amount");
    }

    @Test
    void compiledConditionPathIsUsedWhenPresent() {
        Rule compiledRule = new Rule("compiled", "compiled", "APPROVE");
        compiledRule.setEnabled(true);
        compiledRule.setCompiledCondition(tx -> true);
        Ruleset ruleset = ruleset(compiledRule);
        TransactionContext tx = transaction("txn-6", "APPROVE", 150);
        Decision decision = new Decision("txn-6", RuleEvaluator.EVAL_MONITORING);

        evaluator.evaluate(context(tx, ruleset, decision, false, null));

        assertThat(decision.getMatchedRules()).hasSize(1);
        assertThat(decision.getMatchedRules().get(0).getRuleId()).isEqualTo("compiled");
    }

    private static EvaluationContext context(
            TransactionContext tx, Ruleset ruleset, Decision decision, boolean replay, DebugInfo.Builder debugBuilder) {
        return EvaluationContext.create(tx, ruleset, decision, replay, System.currentTimeMillis(),
                Decision.MODE_NORMAL, debugBuilder);
    }

    private static Ruleset ruleset(Rule... rules) {
        Ruleset ruleset = new Ruleset("CARD_MONITORING", 1);
        ruleset.setEvaluationType(RuleEvaluator.EVAL_MONITORING);
        ruleset.setRules(List.of(rules));
        return ruleset;
    }

    private static Rule rule(String id, String action, Condition... conditions) {
        Rule rule = new Rule(id, id, action);
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.setConditions(List.of(conditions));
        return rule;
    }

    private static Condition condition(String field, String operator, Object value) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    private static TransactionContext transaction(String id, String decision, double amount) {
        TransactionContext tx = new TransactionContext();
        tx.setTransactionId(id);
        tx.setDecision(decision);
        tx.setAmount(BigDecimal.valueOf(amount));
        tx.setCardHash("card-123");
        return tx;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
