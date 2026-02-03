package com.fraud.engine.engine;

import com.fraud.engine.config.EvaluationConfig;
import com.fraud.engine.domain.Condition;
import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Rule;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.testing.TransactionDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Real-World Fraud Pattern Tests")
class FraudPatternTest {

    private Ruleset fraudDetectionRuleset;

    @BeforeEach
    void setUp() {
        fraudDetectionRuleset = createFraudDetectionRuleset();
    }

    private Ruleset createFraudDetectionRuleset() {
        List<Rule> rules = new ArrayList<>();

        Rule cardTesting = new Rule();
        cardTesting.setId("card-testing");
        cardTesting.setName("Card Testing Detection");
        cardTesting.setAction(Decision.DECISION_DECLINE);
        Condition cardTestingAmount = new Condition("amount", "lte", new BigDecimal("1.00"));
        cardTesting.addCondition(cardTestingAmount);
        rules.add(cardTesting);

        Rule gamblingHighAmount = new Rule();
        gamblingHighAmount.setId("gambling-high-amount");
        gamblingHighAmount.setName("Gambling + High Amount");
        gamblingHighAmount.setAction(Decision.DECISION_DECLINE);
        Condition gamblingMcc = new Condition("merchant_category_code", "eq", "7995");
        Condition highAmount = new Condition("amount", "gt", new BigDecimal("1000.00"));
        List<Condition> gamblingConditions = new ArrayList<>();
        gamblingConditions.add(gamblingMcc);
        gamblingConditions.add(highAmount);
        gamblingHighAmount.setConditions(gamblingConditions);
        rules.add(gamblingHighAmount);

        Rule wireTransferHighRisk = new Rule();
        wireTransferHighRisk.setId("wire-transfer-high-risk");
        wireTransferHighRisk.setName("Wire Transfer - First Time Merchant");
        wireTransferHighRisk.setAction(Decision.DECISION_DECLINE);
        Condition wireMcc = new Condition("merchant_category_code", "eq", "4829");
        Condition firstTimeMerchant = new Condition("merchant_id", "starts_with", "new_merchant_");
        List<Condition> wireConditions = new ArrayList<>();
        wireConditions.add(wireMcc);
        wireConditions.add(firstTimeMerchant);
        wireTransferHighRisk.setConditions(wireConditions);
        rules.add(wireTransferHighRisk);

        Rule bustOutPattern = new Rule();
        bustOutPattern.setId("bust-out-sudden-spike");
        bustOutPattern.setName("Bust-Out Pattern - Sudden Spike");
        bustOutPattern.setAction(Decision.DECISION_DECLINE);
        Condition spikeAmount = new Condition("amount", "gt", new BigDecimal("5000.00"));
        Condition spikeVelocity = new Condition("transaction_velocity_24h", "gt", "50");
        List<Condition> bustOutConditions = new ArrayList<>();
        bustOutConditions.add(spikeAmount);
        bustOutConditions.add(spikeVelocity);
        bustOutPattern.setConditions(bustOutConditions);
        rules.add(bustOutPattern);

        Ruleset rs = new Ruleset("FRAUD_DETECTION", 1);
        rs.setRules(rules);
        return rs;
    }

    private TransactionContext createTransaction(String cardHash, BigDecimal amount,
                                                   String countryCode, String mcc) {
        TransactionContext txn = TransactionDataGenerator.customTransaction(
            amount != null ? amount : new BigDecimal("100.00"),
            "USD",
            countryCode != null ? countryCode : "US",
            "merch_test"
        );
        txn.setCardHash(cardHash != null ? cardHash : "card_test_001");
        if (mcc != null) {
            txn.setMerchantCategoryCode(mcc);
        }
        return txn;
    }

    /**
     * Creates a properly configured RuleEvaluator with all dependencies injected.
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
            MonitoringEvaluator monitoringEvaluator = new MonitoringEvaluator();
            VelocityEvaluator monitoringVelocityEvaluator = new VelocityEvaluator();
            Field monitoringVelocityField = VelocityEvaluator.class.getDeclaredField("velocityService");
            monitoringVelocityField.setAccessible(true);
            monitoringVelocityField.set(monitoringVelocityEvaluator, null);
            Field monitoringEvaluatorField = MonitoringEvaluator.class.getDeclaredField("velocityEvaluator");
            monitoringEvaluatorField.setAccessible(true);
            monitoringEvaluatorField.set(monitoringEvaluator, monitoringVelocityEvaluator);
            Field monitoringConfigField = MonitoringEvaluator.class.getDeclaredField("evaluationConfig");
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
    @DisplayName("Card Testing / Carding Pattern")
    class CardTestingTests {

        @Test
        @DisplayName("Rapid small transactions should trigger card testing detection")
        void testCardTestingRapidSmallTransactions() {
            RuleEvaluator evaluator = createRuleEvaluator();

            for (int i = 0; i < 5; i++) {
                TransactionContext txn = createTransaction(
                    "card_testing_001",
                    new BigDecimal("0.99"),
                    "US",
                    "5411"
                );
                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

                assertEquals(Decision.DECISION_DECLINE, decision.getDecision(),
                    "Transaction " + (i+1) + " should be declined (card testing)");
                assertFalse(decision.getMatchedRules().isEmpty(),
                    "Should match card-testing rule");
            }
        }

        @Test
        @DisplayName("Incrementing amounts testing card limits should be detected")
        void testIncrementingAmounts() {
            RuleEvaluator evaluator = createRuleEvaluator();

            BigDecimal[] amounts = {
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                new BigDecimal("5.00"),
                new BigDecimal("10.00"),
                new BigDecimal("25.00")
            };

            for (BigDecimal amount : amounts) {
                TransactionContext txn = createTransaction(
                    "increment_test_001",
                    amount,
                    "US",
                    "5411"
                );
                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

                if (amount.compareTo(new BigDecimal("1.00")) <= 0) {
                    assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
                    assertFalse(decision.getMatchedRules().isEmpty());
                }
            }
        }

        @Test
        @DisplayName("Different merchants, same card - card testing across merchants")
        void testCardTestingAcrossMerchants() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "card_merch_test_001";

            String[] merchantIds = {"merch_001", "merch_002", "merch_003", "merch_004", "merch_005"};

            for (int i = 0; i < merchantIds.length; i++) {
                TransactionContext txn = TransactionDataGenerator.customTransaction(
                    new BigDecimal("0.50"),
                    "USD",
                    "US",
                    merchantIds[i]
                );
                txn.setCardHash(cardHash);

                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

                assertEquals(Decision.DECISION_DECLINE, decision.getDecision(),
                    "Transaction " + (i+1) + " to merchant " + merchantIds[i] + " should be declined");
            }
        }
    }

    @Nested
    @DisplayName("Cross-Border Velocity (Impossible Travel)")
    class ImpossibleTravelTests {

        @Test
        @DisplayName("Same card used in US then Russia within 5 minutes")
        void testImpossibleTravelUStoRussia() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "impossible_travel_001";

            TransactionContext txnUS = createTransaction(cardHash, new BigDecimal("50.00"), "US", "5411");
            txnUS.setTimestamp(Instant.now().minusSeconds(300));
            Decision decisionUS = evaluator.evaluate(txnUS, fraudDetectionRuleset);
            assertEquals(Decision.DECISION_APPROVE, decisionUS.getDecision());

            TransactionContext txnRussia = createTransaction(cardHash, new BigDecimal("75.00"), "RU", "5411");
            txnRussia.setTimestamp(Instant.now());
            Decision decisionRU = evaluator.evaluate(txnRussia, fraudDetectionRuleset);
            assertEquals(Decision.DECISION_APPROVE, decisionRU.getDecision());
        }

        @Test
        @DisplayName("Same card in 3+ countries within 1 hour")
        void testMultipleCountriesInOneHour() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "multi_country_001";

            String[] countries = {"US", "GB", "DE", "FR"};

            for (int i = 0; i < countries.length; i++) {
                TransactionContext txn = createTransaction(cardHash, new BigDecimal("100.00"), countries[i], "5411");
                txn.setTimestamp(Instant.now().minusSeconds((countries.length - i) * 900));

                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);
                assertEquals(Decision.DECISION_APPROVE, decision.getDecision(),
                    "Transaction in " + countries[i] + " should be approved");
            }
        }
    }

    @Nested
    @DisplayName("Bust-Out Pattern")
    class BustOutTests {

        @Test
        @DisplayName("Gradual increase pattern: $50, $100, $500, $2000, $5000 over days")
        void testGradualIncrease() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "bust_out_gradual_001";

            BigDecimal[] amounts = {
                new BigDecimal("50.00"),
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("5000.00")
            };

            for (int i = 0; i < amounts.length; i++) {
                TransactionContext txn = createTransaction(cardHash, amounts[i], "US", "5411");
                txn.setTimestamp(Instant.now().minusSeconds((amounts.length - i) * 86400));

                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

                assertEquals(Decision.DECISION_APPROVE, decision.getDecision(),
                    "Transaction " + (i+1) + " for $" + amounts[i] + " should be approved");
            }
        }

        @Test
        @DisplayName("Sudden spike after period of normal activity")
        void testSuddenSpike() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "bust_out_spike_001";

            TransactionContext normalTxn = createTransaction(cardHash, new BigDecimal("75.00"), "US", "5411");
            normalTxn.getCustomFields().put("transaction_velocity_24h", 3);
            Decision normalDecision = evaluator.evaluate(normalTxn, fraudDetectionRuleset);
            assertEquals(Decision.DECISION_APPROVE, normalDecision.getDecision());

            TransactionContext spikeTxn = createTransaction(cardHash, new BigDecimal("5500.00"), "US", "5411");
            spikeTxn.getCustomFields().put("transaction_velocity_24h", 60);
            Decision spikeDecision = evaluator.evaluate(spikeTxn, fraudDetectionRuleset);

            assertEquals(Decision.DECISION_DECLINE, spikeDecision.getDecision());
            assertFalse(spikeDecision.getMatchedRules().isEmpty());
        }
    }

    @Nested
    @DisplayName("High-Risk MCC + Amount Combination")
    class HighRiskMCCTests {

        @Test
        @DisplayName("Gambling MCC (7995) + amount > $1000 should trigger rule")
        void testGamblingHighAmount() {
            TransactionContext txn = createTransaction("gambling_test_001", new BigDecimal("1500.00"), "US", "7995");
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

            assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
            assertFalse(decision.getMatchedRules().isEmpty());
        }

        @Test
        @DisplayName("Gambling MCC (7995) + amount <= $1000 should be approved")
        void testGamblingLowAmount() {
            TransactionContext txn = createTransaction("gambling_test_002", new BigDecimal("500.00"), "US", "7995");
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
            assertFalse(decision.getMatchedRules().stream()
                .anyMatch(r -> "gambling-high-amount".equals(r.getRuleId())));
        }

        @Test
        @DisplayName("Wire transfer (4829) + first-time merchant should trigger rule")
        void testWireTransferFirstTimeMerchant() {
            TransactionContext txn = createTransaction("wire_test_001", new BigDecimal("500.00"), "US", "4829");
            txn.setMerchantId("new_merchant_abc123");
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

            assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
            assertFalse(decision.getMatchedRules().isEmpty());
        }

        @Test
        @DisplayName("Wire transfer (4829) + established merchant should be approved")
        void testWireTransferEstablishedMerchant() {
            TransactionContext txn = createTransaction("wire_test_002", new BigDecimal("500.00"), "US", "4829");
            txn.setMerchantId("established_wire_service_001");
            RuleEvaluator evaluator = createRuleEvaluator();
            Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);

            assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
            assertFalse(decision.getMatchedRules().stream()
                .anyMatch(r -> "wire-transfer-high-risk".equals(r.getRuleId())));
        }
    }

    @Nested
    @DisplayName("Velocity Spike Pattern")
    class VelocitySpikeTests {

        @Test
        @DisplayName("Normal activity (2-3 txns/day) then 20 txns in 1 hour")
        void testVelocitySpike() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "velocity_spike_001";

            for (int i = 0; i < 20; i++) {
                TransactionContext txn = createTransaction(cardHash, new BigDecimal("50.00"), "US", "5411");
                Decision decision = evaluator.evaluate(txn, fraudDetectionRuleset);
                assertEquals(Decision.DECISION_APPROVE, decision.getDecision());
            }
        }

        @Test
        @DisplayName("Multiple declined transactions followed by approved one")
        void testDeclinedThenApproved() {
            RuleEvaluator evaluator = createRuleEvaluator();
            String cardHash = "decline_then_approve_001";

            for (int i = 0; i < 3; i++) {
                TransactionContext declinedTxn = createTransaction(cardHash, new BigDecimal("0.50"), "US", "7995");
                Decision decision = evaluator.evaluate(declinedTxn, fraudDetectionRuleset);
                assertEquals(Decision.DECISION_DECLINE, decision.getDecision());
            }

            TransactionContext approvedTxn = createTransaction(cardHash, new BigDecimal("100.00"), "US", "5411");
            Decision finalDecision = evaluator.evaluate(approvedTxn, fraudDetectionRuleset);
            assertEquals(Decision.DECISION_APPROVE, finalDecision.getDecision());
        }
    }
}
