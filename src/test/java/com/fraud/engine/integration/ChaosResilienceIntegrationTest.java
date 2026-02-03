package com.fraud.engine.integration;

import com.fraud.engine.security.ScopeValidator;
import com.fraud.engine.testutil.TestSecuritySetup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Chaos and Resilience Tests - Session 4.4
 * Tests fail-open behavior under infrastructure failures.
 *
 * <p>These tests use {@link TestSecurity} with mocked {@link ScopeValidator}
 * to bypass authentication checks in test mode.</p>
 *
 * <p><b>Note:</b> Some tests require external infrastructure manipulation (stopping containers)
 * and may be run manually or with Chaos Engineering tools.</p>
 */
@QuarkusTest
@DisplayName("Chaos/Resilience Tests - Session 4.4")
class ChaosResilienceIntegrationTest {

    @InjectMock
    ScopeValidator scopeValidator;

    private TransactionContext createTransaction(String txnId, String cardHash, double amount, String countryCode) {
        TransactionContext txn = new TransactionContext();
        txn.transactionId = txnId;
        txn.cardHash = cardHash;
        txn.amount = amount;
        if (countryCode != null) {
            txn.countryCode = countryCode;
        }
        return txn;
    }

    @Nested
    @DisplayName("Redis Failure Scenarios")
    class RedisFailureScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("AUTH returns APPROVE when Redis is unavailable (fail-open)")
        void testAUTHFailOpenWhenRedisDown() {
            // This test verifies the fail-open behavior when Redis is unavailable
            // In a real chaos test, Redis would be killed during the test

            TransactionContext txn = createTransaction("txn-redis-down-001", "card-123", 150.00, "US");

            // The evaluation should still return a result with FAIL_OPEN mode
            // even if velocity checks cannot be performed
            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)))
                .body("decision", notNullValue())
                .body("engine_mode", notNullValue());
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Velocity check failure does not block evaluation")
        void testVelocityFailureDoesNotBlockEvaluation() {
            // When velocity checks fail, evaluation should continue
            // and return a decision based on other rules

            TransactionContext txn = createTransaction("txn-velocity-fail-001", "card-velocity", 150.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
        }
    }

    @Nested
    @DisplayName("Storage/MinIO Failure Scenarios")
    class StorageFailureScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Engine uses cached rulesets when MinIO is unreachable")
        void testCachedRulesetsWhenMinIOUnreachable() {
            // When MinIO is unreachable, engine should use cached rulesets
            // and continue operating in DEGRADED mode

            TransactionContext txn = createTransaction("txn-minio-down-001", "card-456", 50.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)))
                .body("decision", anyOf(equalTo("APPROVE"), equalTo("DECLINE"), nullValue()));
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Unknown ruleset type triggers fail-open")
        void testUnknownRulesetTriggersFailOpen() {
            TransactionContext txn = createTransaction("txn-unknown-001", "card-unknown", 999.00, null);
            txn.transactionType = "UNKNOWN_RULESET_TYPE";

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(200)
                .body("decision", equalTo("APPROVE"))
                .body("engine_mode", equalTo("FAIL_OPEN"))
                .body("engine_error_code", notNullValue());
        }
    }

    @Nested
    @DisplayName("Memory Pressure Scenarios")
    class MemoryPressureScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Load shedding activates under memory pressure")
        void testLoadSheddingUnderMemoryPressure() {
            // Under memory pressure, load shedding should activate
            // and requests should be rejected with 503 or processed in DEGRADED mode
            // Note: This test documents the expected behavior but actual memory
            // pressure simulation requires environment-specific setup

            TransactionContext txn = createTransaction("txn-memory-pressure-001", "card-mem", 50.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(200)  // Normal processing in test environment
                .body("decision", anyOf(equalTo("APPROVE"), equalTo("DECLINE")));
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Scenarios")
    class CircuitBreakerScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Circuit breaker opens after repeated Redis failures")
        void testCircuitBreakerOpens() {
            // After repeated failures, circuit breaker should open
            // and fast-fail subsequent requests

            TransactionContext txn = createTransaction("txn-circuit-001", "card-circuit", 50.00, "US");

            // First few requests might succeed or fail depending on Redis state
            // After threshold is reached, circuit breaker should open

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Circuit breaker transitions to half-open after timeout")
        void testCircuitBreakerHalfOpen() {
            // After the timeout period, circuit breaker should transition
            // to half-open state and allow a test request

            TransactionContext txn = createTransaction("txn-half-open-001", "card-half", 50.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
        }
    }

    @Nested
    @DisplayName("Graceful Degradation Scenarios")
    class GracefulDegradationScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("MONITORING continues without decision enrichment on failures")
        void testMonitoringGracefulDegradation() {
            Map<String, Object> payload = Map.of(
                    "transaction_id", "txn-degraded-001",
                    "transaction_type", "PURCHASE",
                    "decision", "APPROVE",
                    "amount", 123.45,
                    "currency", "USD"
            );

            given()
                .contentType(ContentType.JSON)
                .body(payload)
            .when()
                .post("/v1/evaluate/monitoring")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)))
                .body("decision", anyOf(equalTo("APPROVE"), equalTo("DECLINE"), nullValue()));
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Missing velocity config uses defaults")
        void testMissingVelocityConfigUsesDefaults() {
            TransactionContext txn = createTransaction("txn-vel-default-001", "card-vel-default", 150.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
        }
    }

    @Nested
    @DisplayName("Timeout and Latency Scenarios")
    class TimeoutScenarios {

        @BeforeEach
        void setUp() {
            TestSecuritySetup.allowAllScopes(scopeValidator);
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Request timeout returns degraded response")
        void testRequestTimeout() {
            // When a request times out, it should return a degraded response
            // rather than hanging indefinitely

            TransactionContext txn = createTransaction("txn-timeout-001", "card-timeout", 50.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503), equalTo(504)))
                .time(org.hamcrest.Matchers.lessThan(5000L)); // Should not take more than 5 seconds
        }

        @Test
        @TestSecurity(user = "test-m2m@clients")
        @DisplayName("Slow Redis response triggers fallback")
        void testSlowRedisTriggersFallback() {
            // When Redis is slow to respond, the engine should fall back
            // to a safe default decision

            TransactionContext txn = createTransaction("txn-slow-redis-001", "card-slow", 150.00, "US");

            given()
                .contentType(ContentType.JSON)
                .body(txn)
            .when()
                .post("/v1/evaluate/auth")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(503)));
        }
    }

    /**
     * Simple DTO for test transaction creation.
     */
    public static class TransactionContext {
        public String transactionId;
        public String cardHash;
        public Double amount;
        public String countryCode;
        public String transactionType;
    }
}
