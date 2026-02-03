package com.fraud.engine.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SecurityConfigTest {

    private SecurityConfig securityConfig;
    private String originalSkipJwtValue;
    private String originalAppEnvValue;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig();
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
    void testSkipJwtValidation_WhenNotSet_ReturnsFalse() {
        System.clearProperty("SECURITY_SKIP_JWT_VALIDATION");
        assertThat(securityConfig.skipJwtValidation()).isFalse();
    }

    @Test
    void testSkipJwtValidation_WhenSetToTrue_ReturnsTrue() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        assertThat(securityConfig.skipJwtValidation()).isTrue();
    }

    @Test
    void testSkipJwtValidation_WhenSetToFalse_ReturnsFalse() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "false");
        assertThat(securityConfig.skipJwtValidation()).isFalse();
    }

    @Test
    void testSkipJwtValidation_WhenSetToTRUE_CaseInsensitive_ReturnsTrue() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "TRUE");
        assertThat(securityConfig.skipJwtValidation()).isTrue();
    }

    @Test
    void testSkipJwtValidation_WhenSetToFalse_CaseInsensitive_ReturnsFalse() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "FALSE");
        assertThat(securityConfig.skipJwtValidation()).isFalse();
    }

    @Test
    void testGetAppEnvironment_WhenNotSet_ReturnsLocal() {
        System.clearProperty("APP_ENV");
        assertThat(securityConfig.getAppEnvironment()).isEqualTo("local");
    }

    @Test
    void testGetAppEnvironment_WhenSetToLocal_ReturnsLocal() {
        System.setProperty("APP_ENV", "local");
        assertThat(securityConfig.getAppEnvironment()).isEqualTo("local");
    }

    @Test
    void testGetAppEnvironment_WhenSetToTest_ReturnsTest() {
        System.setProperty("APP_ENV", "test");
        assertThat(securityConfig.getAppEnvironment()).isEqualTo("test");
    }

    @Test
    void testGetAppEnvironment_WhenSetToProd_ReturnsProd() {
        System.setProperty("APP_ENV", "prod");
        assertThat(securityConfig.getAppEnvironment()).isEqualTo("prod");
    }

    @Test
    void testIsLocalEnvironment_WhenSetToLocal_ReturnsTrue() {
        System.setProperty("APP_ENV", "local");
        assertThat(securityConfig.isLocalEnvironment()).isTrue();
        assertThat(securityConfig.isProductionEnvironment()).isFalse();
    }

    @Test
    void testIsProductionEnvironment_WhenSetToProd_ReturnsTrue() {
        System.setProperty("APP_ENV", "prod");
        assertThat(securityConfig.isProductionEnvironment()).isTrue();
        assertThat(securityConfig.isLocalEnvironment()).isFalse();
    }

    @Test
    void testValidateSecurityConfiguration_WhenSkipJwtValidationNotSet_NoException() {
        System.clearProperty("SECURITY_SKIP_JWT_VALIDATION");
        System.clearProperty("APP_ENV");
        assertThatCode(() -> securityConfig.validateSecurityConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateSecurityConfiguration_WhenSkipJwtValidationTrueInLocal_NoException() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "local");
        assertThatCode(() -> securityConfig.validateSecurityConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void testValidateSecurityConfiguration_WhenSkipJwtValidationTrueInProd_ThrowsException() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "prod");
        assertThatThrownBy(() -> securityConfig.validateSecurityConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY_SKIP_JWT_VALIDATION can only be set in local environment")
                .hasMessageContaining("prod");
    }

    @Test
    void testValidateSecurityConfiguration_WhenSkipJwtValidationTrueInTest_ThrowsException() {
        System.setProperty("SECURITY_SKIP_JWT_VALIDATION", "true");
        System.setProperty("APP_ENV", "test");
        assertThatThrownBy(() -> securityConfig.validateSecurityConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY_SKIP_JWT_VALIDATION can only be set in local environment")
                .hasMessageContaining("test");
    }
}
