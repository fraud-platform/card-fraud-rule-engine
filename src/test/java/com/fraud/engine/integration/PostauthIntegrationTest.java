package com.fraud.engine.integration;

import com.fraud.engine.ruleset.RulesetRegistry;
import com.fraud.engine.security.ScopeValidator;
import com.fraud.engine.testutil.TestSecuritySetup;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for MONITORING evaluation.
 *
 * <p>These tests use {@link TestSecurity} with mocked {@link ScopeValidator}
 * to bypass authentication checks in test mode.</p>
 */
@QuarkusTest
class MonitoringIntegrationTest {

    @InjectMock
    ScopeValidator scopeValidator;

    @Inject
    RulesetRegistry rulesetRegistry;

    @BeforeEach
    void setUp() {
        // Bypass all scope checks for these tests
        TestSecuritySetup.allowAllScopes(scopeValidator);
    }

    private TransactionContext createTransaction(String txnId, String cardHash, double amount) {
        TransactionContext txn = new TransactionContext();
        txn.setTransactionId(txnId);
        txn.setCardHash(cardHash);
        txn.setAmount(BigDecimal.valueOf(amount));
        return txn;
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringBasicEvaluation() {
        TransactionContext txn = createTransaction("txn-monitoring-001", "card-123", 600.00);
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        given()
            .contentType(ContentType.JSON)
            .body(txn)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(200)
            .body("decision", equalTo("APPROVE"))
            .body("evaluation_type", equalTo("MONITORING"))
            .body("transaction_id", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringLowAmount() {
        TransactionContext txn = createTransaction("txn-monitoring-002", "card-456", 100.00);
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        given()
            .contentType(ContentType.JSON)
            .body(txn)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(200)
            .body("decision", equalTo("APPROVE"));
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringHasDecisionId() {
        TransactionContext txn = createTransaction("txn-monitoring-id", "card-id", 100.00);
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        given()
            .contentType(ContentType.JSON)
            .body(txn)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(200)
            .body("decision_id", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringHasTimestampAndProcessingTime() {
        TransactionContext txn = createTransaction("txn-monitoring-time", "card-time", 100.00);
        txn.setTransactionType("PURCHASE");
        txn.setDecision("APPROVE");

        given()
            .contentType(ContentType.JSON)
            .body(txn)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(200)
            .body("timestamp", notNullValue())
            .body("processing_time_ms", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringMissingDecision_BadRequest() {
        Map<String, Object> payload = Map.of(
                "transaction_id", "txn-monitoring-missing",
                "card_hash", "card-missing",
                "amount", 100.00,
                "currency", "USD",
                "transaction_type", "PURCHASE"
        );

        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(400);
    }

    @Test
    @TestSecurity(user = "test-m2m@clients")
    void testMonitoringInvalidDecision_BadRequest() {
        Map<String, Object> payload = Map.of(
                "transaction_id", "txn-monitoring-invalid",
                "card_hash", "card-invalid",
                "amount", 100.00,
                "currency", "USD",
                "transaction_type", "PURCHASE",
                "decision", "INVALID_DECISION"
        );

        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/v1/evaluate/monitoring")
        .then()
            .statusCode(400);
    }

    /**
     * Simple DTO for test transaction creation.
     */
    public static class TransactionContext {
        @JsonProperty("transaction_id")
        private String transactionId;
        @JsonProperty("card_hash")
        private String cardHash;
        private BigDecimal amount;
        @JsonProperty("transaction_type")
        private String transactionType;
        private String decision;
        @JsonProperty("merchant_name")
        private String merchantName;

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getCardHash() { return cardHash; }
        public void setCardHash(String cardHash) { this.cardHash = cardHash; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getTransactionType() { return transactionType; }
        public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }

        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    }
}
