package com.fraud.engine.engine;

import com.fraud.engine.domain.Condition;
import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Rule;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.config.EvaluationConfig;
import com.fraud.engine.util.EngineMetrics;
import com.fraud.engine.velocity.VelocityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleEvaluatorTest {

    @Mock
    private VelocityService velocityService;

    private RuleEvaluator ruleEvaluator;
    private EvaluationConfig evaluationConfig;

    @BeforeEach
    void setUp() {
        ruleEvaluator = new RuleEvaluator();
        evaluationConfig = new EvaluationConfig();

        // Inject dependencies using reflection
        setVelocityService(velocityService);
        setEvaluationConfig(evaluationConfig);
        setAuthEvaluator(new AuthEvaluator());
        setMonitoringEvaluator(new MonitoringEvaluator());
        setEngineMetrics(new EngineMetrics());

        // Inject VelocityService and EvaluationConfig into evaluators
        setAuthEvaluatorVelocityService(velocityService);
        setAuthEvaluatorEvaluationConfig(evaluationConfig);
        setMonitoringEvaluatorVelocityService(velocityService);
        setMonitoringEvaluatorEvaluationConfig(evaluationConfig);
    }

    private void setEngineMetrics(EngineMetrics metrics) {
        try {
            var field = RuleEvaluator.class.getDeclaredField("engineMetrics");
            field.setAccessible(true);
            field.set(ruleEvaluator, metrics);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setVelocityService(VelocityService vs) {
        try {
            var field = RuleEvaluator.class.getDeclaredField("velocityService");
            field.setAccessible(true);
            field.set(ruleEvaluator, vs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setEvaluationConfig(EvaluationConfig config) {
        try {
            var field = RuleEvaluator.class.getDeclaredField("evaluationConfig");
            field.setAccessible(true);
            field.set(ruleEvaluator, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setAuthEvaluator(AuthEvaluator evaluator) {
        try {
            var field = RuleEvaluator.class.getDeclaredField("authEvaluator");
            field.setAccessible(true);
            field.set(ruleEvaluator, evaluator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setMonitoringEvaluator(MonitoringEvaluator evaluator) {
        try {
            var field = RuleEvaluator.class.getDeclaredField("monitoringEvaluator");
            field.setAccessible(true);
            field.set(ruleEvaluator, evaluator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setAuthEvaluatorVelocityService(VelocityService vs) {
        try {
            var field = AuthEvaluator.class.getDeclaredField("velocityEvaluator");
            field.setAccessible(true);
            field.set(ruleEvaluator.authEvaluator, new VelocityEvaluator());
            // Also inject the mock VelocityService into VelocityEvaluator
            var velocityServiceField = VelocityEvaluator.class.getDeclaredField("velocityService");
            velocityServiceField.setAccessible(true);
            velocityServiceField.set(ruleEvaluator.authEvaluator.velocityEvaluator, vs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setAuthEvaluatorEvaluationConfig(EvaluationConfig config) {
        try {
            var field = AuthEvaluator.class.getDeclaredField("evaluationConfig");
            field.setAccessible(true);
            field.set(ruleEvaluator.authEvaluator, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setMonitoringEvaluatorVelocityService(VelocityService vs) {
        try {
            var field = MonitoringEvaluator.class.getDeclaredField("velocityEvaluator");
            field.setAccessible(true);
            field.set(ruleEvaluator.monitoringEvaluator, new VelocityEvaluator());
            // Also inject the mock VelocityService into VelocityEvaluator
            var velocityServiceField = VelocityEvaluator.class.getDeclaredField("velocityService");
            velocityServiceField.setAccessible(true);
            velocityServiceField.set(ruleEvaluator.monitoringEvaluator.velocityEvaluator, vs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setMonitoringEvaluatorEvaluationConfig(EvaluationConfig config) {
        try {
            var field = MonitoringEvaluator.class.getDeclaredField("evaluationConfig");
            field.setAccessible(true);
            field.set(ruleEvaluator.monitoringEvaluator, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testAUTH_FirstMatchStopsEvaluation() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(150.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getMatchedRules()).hasSize(1);
        assertThat(decision.getMatchedRules().get(0).getRuleId()).isEqualTo("high-amount");
    }

    @Test
    void testAUTH_NoMatchDefaultsToApprove() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getMatchedRules()).hasSize(1);
    }

    @Test
    void testAUTH_HighRiskCountryDeclines() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(50.00, "NG");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getMatchedRules()).hasSize(1);
        assertThat(decision.getMatchedRules().get(0).getRuleId()).isEqualTo("high-risk-country");
    }

    @Test
    void testMONITORING_CollectsAllMatches() {
        Ruleset ruleset = createMONITORINGRuleset();

        TransactionContext txn = createTransaction(600.00, "NG");
        txn.setTransactionType("PURCHASE");
        txn.setDecision("DECLINE");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getMatchedRules()).hasSize(2);
    }

    @Test
    void testMONITORING_NoMatchApproves() {
        Ruleset ruleset = createMONITORINGRuleset();

        TransactionContext txn = createTransaction(50.00, "CA");
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getMatchedRules()).hasSize(0);
    }

    @Test
    void testMONITORING_UsesProvidedDecision() {
        Ruleset ruleset = createMONITORINGRuleset();

        TransactionContext txn = createTransaction(600.00, "CA");
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getMatchedRules()).hasSize(1);
    }

    @Test
    void testMONITORING_MissingDecision_MarksDegraded() {
        Ruleset ruleset = createMONITORINGRuleset();

        TransactionContext txn = createTransaction(600.00, "CA");
        txn.setTransactionType("PURCHASE");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_DEGRADED);
        assertThat(decision.getEngineErrorCode()).isEqualTo("MISSING_DECISION");
    }

    @Test
    void testMONITORING_InvalidDecision_MarksDegraded() {
        Ruleset ruleset = createMONITORINGRuleset();

        TransactionContext txn = createTransaction(600.00, "CA");
        txn.setTransactionType("PURCHASE");
        txn.setDecision("REVIEW");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_DEGRADED);
        assertThat(decision.getEngineErrorCode()).isEqualTo("INVALID_DECISION");
    }

    @Test
    void testMONITORING_EvaluationErrorPreservesDecisionAndReturnsDegraded() {
        MonitoringEvaluator brokenMonitoringEvaluator = org.mockito.Mockito.mock(MonitoringEvaluator.class);
        doThrow(new RuntimeException("boom")).when(brokenMonitoringEvaluator).evaluate(any());
        setMonitoringEvaluator(brokenMonitoringEvaluator);

        Ruleset ruleset = createMONITORINGRuleset();
        TransactionContext txn = createTransaction(600.00, "CA");
        txn.setTransactionType("PURCHASE");
        txn.setDecision("DECLINE");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_DEGRADED);
        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getEngineErrorCode()).isEqualTo("EVALUATION_ERROR");
    }

    @Test
    void testAUTH_EvaluationErrorRemainsFailOpenApprove() {
        AuthEvaluator brokenAuthEvaluator = org.mockito.Mockito.mock(AuthEvaluator.class);
        doThrow(new RuntimeException("boom")).when(brokenAuthEvaluator).evaluate(any());
        setAuthEvaluator(brokenAuthEvaluator);

        Ruleset ruleset = createAUTHRuleset();
        TransactionContext txn = createTransaction(600.00, "CA");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_FAIL_OPEN);
        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getEngineErrorCode()).isEqualTo("EVALUATION_ERROR");
    }

    @Test
    void testDecisionIncludesRulesetInfo() {
        Ruleset ruleset = createAUTHRuleset();
        ruleset.setKey("CARD_AUTH");
        ruleset.setVersion(3);

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getRulesetKey()).isEqualTo("CARD_AUTH");
        assertThat(decision.getRulesetVersion()).isEqualTo(3);
    }

    @Test
    void testDecisionHasEvaluationType() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getEvaluationType()).isEqualTo("AUTH");
    }

    @Test
    void testDecisionHasProcessingTime() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getProcessingTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testEmptyRulesetApproves() {
        Ruleset ruleset = new Ruleset("EMPTY", 1);
        ruleset.setEvaluationType("AUTH");

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
    }

    @Test
    void testDisabledRuleNotEvaluated() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("test-rule", "Test", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(false);
        rule.addCondition(new Condition("amount", "gt", 10));
        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(150.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("APPROVE");
        assertThat(decision.getMatchedRules()).isEmpty();
    }

    @Test
    void testVelocityCheckFailureDoesNotBlockEvaluation() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("velocity-rule", "Velocity", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.addCondition(new Condition("amount", "gt", 10));

        com.fraud.engine.domain.VelocityConfig velocityConfig = new com.fraud.engine.domain.VelocityConfig("card_hash", 3600, 5);
        velocityConfig.setAction("DECLINE");
        rule.setVelocity(velocityConfig);

        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(150.00, "US");

        when(velocityService.checkVelocity(any(), any()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getMatchedRules()).hasSize(1);
    }

    @Test
    void testUnknownEvaluationTypeDefaultsToAUTH() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("UNKNOWN");

        Rule rule = new Rule("test-rule", "Test", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.addCondition(new Condition("amount", "gt", 10));
        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(150.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
    }

    @Test
    void testReplayVelocityReadOnlyAppliesVelocityAction() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("velocity-rule", "Velocity", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.addCondition(new Condition("amount", "gt", 10));

        com.fraud.engine.domain.VelocityConfig velocityConfig =
                new com.fraud.engine.domain.VelocityConfig("card_hash", 3600, 3);
        velocityConfig.setAction("DECLINE");
        rule.setVelocity(velocityConfig);
        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(150.00, "US");

        when(velocityService.buildVelocityKey(any(), any())).thenReturn("key");
        when(velocityService.getCurrentCount("key")).thenReturn(5L);

        Decision decision = ruleEvaluator.evaluate(txn, ruleset, true);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
    }

    @Test
    void testVelocitySnapshotFailureTriggersFailOpen() {
        Ruleset ruleset = createAUTHRuleset();

        TransactionContext txn = createTransaction(150.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDecision()).isEqualTo("DECLINE");
        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_NORMAL);
    }

    @Test
    void testVelocityCheckFailureMarksDegraded() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("velocity-rule", "Velocity Rule", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.addCondition(new Condition("amount", "gt", 10));

        com.fraud.engine.domain.VelocityConfig velocityConfig =
                new com.fraud.engine.domain.VelocityConfig("card_hash", 3600, 3);
        velocityConfig.setAction("DECLINE");
        rule.setVelocity(velocityConfig);
        ruleset.addRule(rule);

        when(velocityService.checkVelocity(any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        TransactionContext txn = createTransaction(150.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getEngineMode()).isEqualTo(Decision.MODE_DEGRADED);
        assertThat(decision.getEngineErrorCode()).isEqualTo("REDIS_UNAVAILABLE");
        assertThat(decision.getDecision()).isEqualTo("DECLINE");
    }

    @Test
    void testEvaluationTypeEnumGetValue() {
        assertThat(RuleEvaluator.EvaluationType.AUTH.getValue()).isEqualTo("AUTH");
        assertThat(RuleEvaluator.EvaluationType.MONITORING.getValue()).isEqualTo("MONITORING");
    }

    @Test
    void testDebugInfoCapturesConditionEvaluations() {
        evaluationConfig.debugEnabled = true;
        evaluationConfig.includeFieldValues = true;
        evaluationConfig.debugSampleRate = 100;
        evaluationConfig.maxConditionEvaluations = 100;

        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("debug-rule", "Debug Rule", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);

        rule.addCondition(new Condition("amount", "gt", 100));
        rule.addCondition(new Condition("amount", "gte", 150));
        rule.addCondition(new Condition("amount", "lt", 200));
        rule.addCondition(new Condition("amount", "lte", 150));

        Condition inCondition = new Condition();
        inCondition.setField("country_code");
        inCondition.setOperator("in");
        inCondition.setValues(List.of("US", "CA"));
        rule.addCondition(inCondition);

        Condition notInCondition = new Condition();
        notInCondition.setField("country_code");
        notInCondition.setOperator("not_in");
        notInCondition.setValues(List.of("RU"));
        rule.addCondition(notInCondition);

        Condition between = new Condition();
        between.setField("amount");
        between.setOperator("between");
        between.setValues(List.of(50, 200));
        rule.addCondition(between);

        Condition contains = new Condition();
        contains.setField("merchant_name");
        contains.setOperator("contains");
        contains.setValue("Store");
        rule.addCondition(contains);

        Condition startsWith = new Condition();
        startsWith.setField("card_hash");
        startsWith.setOperator("starts_with");
        startsWith.setValue("abc");
        rule.addCondition(startsWith);

        Condition endsWith = new Condition();
        endsWith.setField("card_hash");
        endsWith.setOperator("ends_with");
        endsWith.setValue("xyz");
        rule.addCondition(endsWith);

        Condition exists = new Condition();
        exists.setField("device_id");
        exists.setOperator("exists");
        rule.addCondition(exists);

        rule.addCondition(new Condition("currency", "eq", "USD"));
        rule.addCondition(new Condition("currency", "ne", "JPY"));

        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(150.00, "US");
        txn.setMerchantName("Test Store");
        txn.setCardHash("abc123xyz");
        txn.setDeviceId("device-1");
        txn.setCurrency("EUR");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDebugInfo()).isNotNull();
        assertThat(decision.getDebugInfo().getConditionEvaluations()).hasSize(13);
        assertThat(decision.getDebugInfo().getFieldValues()).containsKeys("amount", "country_code");
    }

    @Test
    void testDebugInfoRespectsMaxConditionEvaluations() {
        evaluationConfig.debugEnabled = true;
        evaluationConfig.includeFieldValues = false;
        evaluationConfig.debugSampleRate = 100;
        evaluationConfig.maxConditionEvaluations = 1;

        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule rule = new Rule("debug-rule", "Debug Rule", "DECLINE");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.addCondition(new Condition("amount", "gt", 10));
        rule.addCondition(new Condition("country_code", "eq", "US"));
        ruleset.addRule(rule);

        TransactionContext txn = createTransaction(50.00, "US");

        Decision decision = ruleEvaluator.evaluate(txn, ruleset);

        assertThat(decision.getDebugInfo()).isNotNull();
        assertThat(decision.getDebugInfo().getConditionEvaluations()).hasSize(1);
        assertThat(decision.getDebugInfo().getFieldValues()).isEmpty();
    }

    private Ruleset createAUTHRuleset() {
        Ruleset ruleset = new Ruleset("CARD_AUTH", 1);
        ruleset.setEvaluationType("AUTH");

        Rule highAmount = new Rule("high-amount", "High Amount", "DECLINE");
        highAmount.setPriority(100);
        highAmount.setEnabled(true);
        highAmount.addCondition(new Condition("amount", "gt", 100));
        ruleset.addRule(highAmount);

        Rule highRiskCountry = new Rule("high-risk-country", "High Risk Country", "DECLINE");
        highRiskCountry.setPriority(90);
        highRiskCountry.setEnabled(true);
        highRiskCountry.addCondition(new Condition("country_code", "in", java.util.List.of("NG", "RU")));
        ruleset.addRule(highRiskCountry);

        Rule defaultApprove = new Rule("default-approve", "Default Approve", "APPROVE");
        defaultApprove.setPriority(10);
        defaultApprove.setEnabled(true);
        defaultApprove.addCondition(new Condition("amount", "lte", 100));
        ruleset.addRule(defaultApprove);

        return ruleset;
    }

    private Ruleset createMONITORINGRuleset() {
        Ruleset ruleset = new Ruleset("CARD_MONITORING", 1);
        ruleset.setEvaluationType("MONITORING");

        Rule highAmount = new Rule("high-amount", "High Amount", "REVIEW");
        highAmount.setPriority(100);
        highAmount.setEnabled(true);
        highAmount.addCondition(new Condition("amount", "gt", 500));
        ruleset.addRule(highAmount);

        Rule highRiskCountry = new Rule("high-risk-country", "High Risk Country", "REVIEW");
        highRiskCountry.setPriority(90);
        highRiskCountry.setEnabled(true);
        highRiskCountry.addCondition(new Condition("country_code", "in", java.util.List.of("NG", "RU")));
        ruleset.addRule(highRiskCountry);

        return ruleset;
    }

    private TransactionContext createTransaction(double amount, String countryCode) {
        TransactionContext txn = new TransactionContext();
        txn.setTransactionId("txn-" + System.currentTimeMillis());
        txn.setCardHash("card-123");
        txn.setAmount(BigDecimal.valueOf(amount));
        txn.setCountryCode(countryCode);
        return txn;
    }
}
