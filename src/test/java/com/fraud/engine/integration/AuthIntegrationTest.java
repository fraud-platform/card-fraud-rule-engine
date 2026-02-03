package com.fraud.engine.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for authentication and ScopeValidator behavior.
 *
 * <p><b>Auth model:</b> ScopeValidator is the sole auth gate.
 * When {@code quarkus.smallrye-jwt.enabled=false} (dev/test mode),
 * ScopeValidator skips all validation. When enabled (prod/load-test),
 * it enforces M2M token + scope checks.</p>
 *
 * <p><b>Auth rejection E2E testing:</b> Use {@code uv run test-e2e} (JWT mode)
 * to verify endpoints reject unauthenticated requests in production-like config.</p>
 *
 * <p>This file tests ScopeValidator claim extraction logic using direct instantiation
 * (not CDI), which means {@code jwtEnabled} defaults to {@code true} and validation runs.</p>
 */
@QuarkusTest
class AuthIntegrationTest {

    // =========================================================================
    // Health endpoint (no auth required in any mode)
    // =========================================================================

    @Test
    void testHealth_NoAuthRequired_Returns200() {
        given()
        .when()
            .get("/v1/evaluate/health")
        .then()
            .statusCode(200);
    }

    // =========================================================================
    // ScopeValidator Unit Tests
    // These test claim extraction logic via direct instantiation (not CDI).
    // jwtEnabled defaults to true, so all validation logic executes.
    // =========================================================================

    @Test
    void testScopeValidator_M2MTokenValidation() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken m2mJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(m2mJwt.getClaim("gty")).thenReturn("client-credentials");
        org.mockito.Mockito.when(m2mJwt.getClaim("scope")).thenReturn("execute:rules read:results");
        org.mockito.Mockito.when(m2mJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        org.mockito.Mockito.when(m2mJwt.getClaim("roles")).thenReturn(null);

        assertTrue(validator.isM2MToken(m2mJwt));
        assertTrue(validator.isNotHumanUser(m2mJwt));
        assertTrue(validator.hasScope(m2mJwt, "execute:rules"));
    }

    @Test
    void testScopeValidator_HumanTokenRejection() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken humanJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(humanJwt.getClaim("gty")).thenReturn(null);
        org.mockito.Mockito.when(humanJwt.getClaim("scope")).thenReturn("execute:rules");
        org.mockito.Mockito.when(humanJwt.getClaim("https://fraud-rule-engine-api/roles"))
                .thenReturn(java.util.Set.of("admin"));

        assertFalse(validator.isM2MToken(humanJwt));
        assertFalse(validator.isNotHumanUser(humanJwt));
    }

    @Test
    void testScopeValidator_RequireM2MWithScope_ValidToken() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken m2mJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(m2mJwt.getClaim("gty")).thenReturn("client-credentials");
        org.mockito.Mockito.when(m2mJwt.getClaim("scope")).thenReturn("execute:rules");
        org.mockito.Mockito.when(m2mJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        org.mockito.Mockito.when(m2mJwt.getClaim("roles")).thenReturn(null);

        assertDoesNotThrow(() -> validator.requireM2MWithScope(m2mJwt, "execute:rules"));
    }

    @Test
    void testScopeValidator_RequireM2MWithScope_MissingGty() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken humanJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(humanJwt.getClaim("gty")).thenReturn(null);
        org.mockito.Mockito.when(humanJwt.getClaim("scope")).thenReturn("execute:rules");
        org.mockito.Mockito.when(humanJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        org.mockito.Mockito.when(humanJwt.getClaim("roles")).thenReturn(null);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> validator.requireM2MWithScope(humanJwt, "execute:rules"));
        assertTrue(ex.getMessage().contains("client-credentials"));
    }

    @Test
    void testScopeValidator_RequireM2MWithScope_HasRoles() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken m2mJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(m2mJwt.getClaim("gty")).thenReturn("client-credentials");
        org.mockito.Mockito.when(m2mJwt.getClaim("scope")).thenReturn("execute:rules");
        org.mockito.Mockito.when(m2mJwt.getClaim("https://fraud-rule-engine-api/roles"))
                .thenReturn(java.util.Set.of("admin"));

        assertDoesNotThrow(() -> validator.requireM2MWithScope(m2mJwt, "execute:rules"));
    }

    @Test
    void testScopeValidator_RequireM2MWithScope_MissingScope() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken m2mJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(m2mJwt.getClaim("gty")).thenReturn("client-credentials");
        org.mockito.Mockito.when(m2mJwt.getClaim("scope")).thenReturn("read:results");
        org.mockito.Mockito.when(m2mJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        org.mockito.Mockito.when(m2mJwt.getClaim("roles")).thenReturn(null);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> validator.requireM2MWithScope(m2mJwt, "execute:rules"));
        assertTrue(ex.getMessage().contains("Missing required scope"));
    }

    @Test
    void testScopeValidator_GetClientId() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        org.eclipse.microprofile.jwt.JsonWebToken m2mJwt =
                org.mockito.Mockito.mock(org.eclipse.microprofile.jwt.JsonWebToken.class);
        org.mockito.Mockito.when(m2mJwt.getSubject()).thenReturn("abc123-client@clients");

        assertEquals("abc123-client", validator.getClientId(m2mJwt));
    }

    @Test
    void testScopeValidator_NullJwt_ThrowsForbidden() {
        com.fraud.engine.security.ScopeValidator validator = new com.fraud.engine.security.ScopeValidator();

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> validator.requireM2MWithScope(null, "execute:rules"));
        assertTrue(ex.getMessage().contains("Missing authentication token"));
    }
}
