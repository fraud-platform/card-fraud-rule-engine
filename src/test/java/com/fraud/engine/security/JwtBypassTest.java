package com.fraud.engine.security;

import com.fraud.engine.config.SecurityConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class JwtBypassTest {

    @Inject
    ScopeValidator scopeValidator;

    @Inject
    SecurityConfig securityConfig;

    private String originalSkipJwtValue;
    private String originalAppEnvValue;

    @BeforeEach
    void setUp() {
        originalSkipJwtValue = System.getenv("SECURITY_SKIP_JWT_VALIDATION");
        originalAppEnvValue = System.getenv("APP_ENV");
    }

    @AfterEach
    void tearDown() {
        if (originalSkipJwtValue != null) {
            System.setProperty("SECURITY_SKIP_JWT_VALIDATION", originalSkipJwtValue);
        } else {
            System.clearProperty("SECURITY_SKIP_JWT_VALIDATION");
        }
        if (originalAppEnvValue != null) {
            System.setProperty("APP_ENV", originalAppEnvValue);
        } else {
            System.clearProperty("APP_ENV");
        }
    }

    @Test
    void testSkipJwtValidation_WhenEnvVarSet_ValidatorReturnsTrue() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "local");

        assertThat(scopeValidator.skipJwtValidation()).isTrue();
    }

    @Test
    void testSkipJwtValidation_WhenEnvVarNotSet_ValidatorReturnsFalse() {
        System.clearProperty("SECURITY_SKIP_JWT_VALIDATION");
        System.clearProperty("APP_ENV");

        assertThat(securityConfig.skipJwtValidation()).isFalse();
    }

    @Test
    void testRequireM2MWithScope_WhenBypassEnabled_SkipsValidation() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "local");

        assertThatCode(() -> scopeValidator.requireM2MWithScope(null, "execute:rules"))
                .doesNotThrowAnyException();
    }

    @Test
    void testRequireM2MToken_WhenBypassEnabled_SkipsValidation() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "local");

        assertThatCode(() -> scopeValidator.requireM2MToken(null))
                .doesNotThrowAnyException();
    }

    @Test
    void testMockUser_HasAllRequiredScopes() {
        assertThat(MockUser.ALL_SCOPES).contains(
                "execute:rules",
                "read:results",
                "replay:transactions",
                "read:metrics"
        );
    }

    @Test
    void testMockUser_HasM2MGrantType() {
        assertThat(MockUser.GTY).isEqualTo("client-credentials");
    }

    @Test
    void testMockUser_HasScopesString() {
        assertThat(MockUser.SCOPES_STRING).contains("execute:rules");
    }
}
