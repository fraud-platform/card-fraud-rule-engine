package com.fraud.engine.domain;

import com.fraud.engine.config.EvaluationConfig;
import com.fraud.engine.engine.AuthEvaluator;
import com.fraud.engine.engine.RuleEvaluator;
import com.fraud.engine.engine.VelocityEvaluator;
import com.fraud.engine.testing.TransactionDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Boundary Condition Tests")
class BoundaryConditionTest {

    private TransactionContext basicTransaction;
    private Ruleset ruleset;

    @BeforeEach
    void setUp() {
        basicTransaction = TransactionDataGenerator.customTransaction(
            new BigDecimal("100.00"),
            "USD",
            "US",
            "merch_123"
        );

        ruleset = createRulesetWithThresholds();
    }

    private Ruleset createRulesetWithThresholds() {
        List<Rule> rules = new ArrayList<>();

        Rule zeroAmountRule = new Rule();
        zeroAmountRule.setId("zero-amount");
        zeroAmountRule.setName("Zero Amount Test");
        zeroAmountRule.setAction(Decision.DECISION_DECLINE);
        Condition zeroAmountCondition = new Condition("amount", "eq", BigDecimal.ZERO);
        zeroAmountRule.addCondition(zeroAmountCondition);
        rules.add(zeroAmountRule);

        Rule highAmountRule = new Rule();
        highAmountRule.setId("high-amount");
        highAmountRule.setName("High Amount (> $100)");
        highAmountRule.setAction(Decision.DECISION_DECLINE);
        Condition highAmountCondition = new Condition("amount", "gt", new BigDecimal("100.00"));
        highAmountRule.addCondition(highAmountCondition);
        rules.add(highAmountRule);

        Rule maxAmountRule = new Rule();
        maxAmountRule.setId("max-amount");
        maxAmountRule.setName("Max Amount (>$50000)");
        maxAmountRule.setAction(Decision.DECISION_DECLINE);
        Condition maxAmountCondition = new Condition("amount", "gt", new BigDecimal("50000.00"));
        maxAmountRule.addCondition(maxAmountCondition);
        rules.add(maxAmountRule);

        Ruleset rs = new Ruleset("BOUNDARY_TEST", 1);
        rs.setRules(rules);
        return rs;
    }

    /**
     * Creates a properly configured RuleEvaluator with all dependencies injected.
     * This is needed because the evaluators use CDI injection which doesn't work in unit tests.
     */
    private RuleEvaluator createRuleEvaluator() {
        RuleEvaluator evaluator = new RuleEvaluator();
        EvaluationConfig evaluationConfig = new EvaluationConfig();
        evaluationConfig.debugEnabled = false;

        try {
            // Inject velocityService (can be null for these tests)
            Field velocityServiceField = RuleEvaluator.class.getDeclaredField("velocityService");
            velocityServiceField.setAccessible(true);
            velocityServiceField.set(evaluator, null);

            // Inject evaluationConfig
            Field evaluationConfigField = RuleEvaluator.class.getDeclaredField("evaluationConfig");
            evaluationConfigField.setAccessible(true);
            evaluationConfigField.set(evaluator, evaluationConfig);

            // Create and inject authEvaluator
            AuthEvaluator authEvaluator = new AuthEvaluator();
            VelocityEvaluator authVelocityEvaluator = new VelocityEvaluator();
            Field authVelocityField = VelocityEvaluator.class.getDeclaredField("velocityService");
            authVelocityField.setAccessible(true);
            authVelocityField.set(authVelocityEvaluator, null);
            Field authEvaluatorField = AuthEvaluator.class.getDeclaredField("velocityEvaluator");
            authEvaluatorField.setAccessible(true);
            authEvaluatorField.set(authEvaluator, authVelocityEvaluator);
            Field authConfigField = AuthEvaluator.class.getDeclaredField("evaluationConfig");
            authConfigField.setAccessible(true);
            authConfigField.set(authEvaluator, evaluationConfig);
            Field evaluatorAuthField = RuleEvaluator.class.getDeclaredField("authEvaluator");
            evaluatorAuthField.setAccessible(true);
            evaluatorAuthField.set(evaluator, authEvaluator);

            // Create and inject monitoringEvaluator
            com.fraud.engine.engine.MonitoringEvaluator monitoringEvaluator = new com.fraud.engine.engine.MonitoringEvaluator();
            VelocityEvaluator monitoringVelocityEvaluator = new VelocityEvaluator();
            Field monitoringVelocityField = VelocityEvaluator.class.getDeclaredField("velocityService");
            monitoringVelocityField.setAccessible(true);
            monitoringVelocityField.set(monitoringVelocityEvaluator, null);
            Field monitoringEvaluatorField = com.fraud.engine.engine.MonitoringEvaluator.class.getDeclaredField("velocityEvaluator");
            monitoringEvaluatorField.setAccessible(true);
            monitoringEvaluatorField.set(monitoringEvaluator, monitoringVelocityEvaluator);
            Field monitoringConfigField = com.fraud.engine.engine.MonitoringEvaluator.class.getDeclaredField("evaluationConfig");
            monitoringConfigField.setAccessible(true);
            monitoringConfigField.set(monitoringEvaluator, evaluationConfig);
            Field evaluatorMonitoringField = RuleEvaluator.class.getDeclaredField("monitoringEvaluator");
            evaluatorMonitoringField.setAccessible(true);
            evaluatorMonitoringField.set(evaluator, monitoringEvaluator);

        } catch (Exception e) {
            throw new RuntimeException("Failed to configure RuleEvaluator", e);
        }

        return evaluator;
    }

    @Nested
    @DisplayName("Amount Boundary Conditions")
    class AmountBoundaryTests {

        @Test
        @DisplayName("Zero amount transaction should be handled correctly")
        void testZeroAmount() {
            basicTransaction.setAmount(BigDecimal.ZERO);
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(basicTransaction, ruleset);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
            assertFalse(decision.getMatchedRules().isEmpty());
        }

        @Test
        @DisplayName("Negative amount (refund scenario) should be handled")
        void testNegativeAmount() {
            basicTransaction.setAmount(new BigDecimal("-50.00"));
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(basicTransaction, ruleset);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("Amount at exact threshold ($100.00) should not trigger > rule")
        void testAmountAtThreshold() {
            basicTransaction.setAmount(new BigDecimal("100.00"));
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(basicTransaction, ruleset);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("Amount just above threshold ($100.01) should trigger rule")
        void testAmountJustAboveThreshold() {
            basicTransaction.setAmount(new BigDecimal("100.01"));
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(basicTransaction, ruleset);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
        }

        @Test
        @DisplayName("High value transaction (> $50,000) should trigger max rule")
        void testHighValueTransaction() {
            basicTransaction.setAmount(new BigDecimal("50001.00"));
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(basicTransaction, ruleset);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
        }

        @Test
        @DisplayName("Maximum threshold amount ($50,000) should not trigger max rule")
        void testMaximumThreshold() {
            basicTransaction.setAmount(new BigDecimal("50000.00"));
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("MAX_THRESHOLD_TEST", 1);
            Rule maxRule = new Rule();
            maxRule.setId("max-amount");
            maxRule.setName("Max Amount (>$50000)");
            maxRule.setAction(Decision.DECISION_DECLINE);
            Condition maxAmountCondition = new Condition("amount", "gt", new BigDecimal("50000.00"));
            maxRule.addCondition(maxAmountCondition);
            rs.addRule(maxRule);

            Decision decision = evaluator.evaluate(basicTransaction, rs);

            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }
    }

    @Nested
    @DisplayName("String Field Boundary Conditions")
    class StringFieldBoundaryTests {

        @Test
        @DisplayName("Empty merchant category code should be handled")
        void testEmptyMerchantCategoryCode() {
            basicTransaction.setMerchantCategoryCode("");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("EMPTY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("Null merchant category code should be handled")
        void testNullMerchantCategoryCode() {
            basicTransaction.setMerchantCategoryCode(null);
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("NULL_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("Very long merchant name should be handled")
        void testLongMerchantName() {
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                longName.append("X");
            }
            basicTransaction.setMerchantName(longName.toString());
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("LONG_NAME_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }
    }

    @Nested
    @DisplayName("Currency Boundary Conditions")
    class CurrencyBoundaryTests {

        @Test
        @DisplayName("JPY currency (no decimal places) should be handled")
        void testJPYCurrency() {
            basicTransaction.setCurrency("JPY");
            basicTransaction.setAmount(new BigDecimal("10000"));
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("JPY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("BHD currency (3 decimal places) should be handled")
        void testBHDCurrency() {
            basicTransaction.setCurrency("BHD");
            basicTransaction.setAmount(new BigDecimal("100.500"));
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("BHD_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
        }

        @Test
        @DisplayName("Invalid currency code should be handled gracefully")
        void testInvalidCurrencyCode() {
            basicTransaction.setCurrency("XXX");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("INVALID_CURRENCY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
        }

        @Test
        @DisplayName("Empty currency code should be handled")
        void testEmptyCurrencyCode() {
            basicTransaction.setCurrency("");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("EMPTY_CURRENCY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
        }
    }

    @Nested
    @DisplayName("Country Code Boundary Conditions")
    class CountryCodeBoundaryTests {

        @Test
        @DisplayName("Invalid country code (2 letters) should be handled")
        void testInvalidCountryCodeLength() {
            basicTransaction.setCountryCode("XX");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("INVALID_COUNTRY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
        }

        @Test
        @DisplayName("Three-letter country code should be handled")
        void testThreeLetterCountryCode() {
            basicTransaction.setCountryCode("USA");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("THREE_LETTER_COUNTRY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
        }

        @Test
        @DisplayName("Empty country code should be handled")
        void testEmptyCountryCode() {
            basicTransaction.setCountryCode("");
            RuleEvaluator evaluator = createRuleEvaluator();

            Ruleset rs = new Ruleset("EMPTY_COUNTRY_TEST", 1);
            rs.setRules(new ArrayList<>());

            Decision decision = evaluator.evaluate(basicTransaction, rs);
            assertNotNull(decision);
        }
    }
}
