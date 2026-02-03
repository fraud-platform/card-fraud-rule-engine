package com.fraud.engine.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SecurityConfig {

    private static final Logger LOG = Logger.getLogger(SecurityConfig.class);

    private static final String PROP_SKIP_JWT_VALIDATION = "SECURITY_SKIP_JWT_VALIDATION";
    private static final String PROP_APP_ENV = "APP_ENV";

    public static final String ENV_LOCAL = "local";
    public static final String ENV_TEST = "test";
    public static final String ENV_PROD = "prod";

    public boolean skipJwtValidation() {
        String value = getConfigValue(PROP_SKIP_JWT_VALIDATION);
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase("true");
    }

    public String getAppEnvironment() {
        String value = getConfigValue(PROP_APP_ENV);
        return value != null ? value : ENV_LOCAL;
    }

    private String getConfigValue(String key) {
        String value = System.getProperty(key);
        if (value != null) {
            return value;
        }
        return System.getenv(key);
    }

    public boolean isLocalEnvironment() {
        return ENV_LOCAL.equals(getAppEnvironment());
    }

    public boolean isProductionEnvironment() {
        return ENV_PROD.equals(getAppEnvironment());
    }

    public void validateSecurityConfiguration() {
        if (skipJwtValidation()) {
            String env = getAppEnvironment();
            if (!ENV_LOCAL.equals(env)) {
                String message = String.format(
                    "SECURITY_SKIP_JWT_VALIDATION can only be set in %s environment. Current environment: %s",
                    ENV_LOCAL, env
                );
                LOG.error(message);
                throw new IllegalStateException(message);
            }
            LOG.warn("JWT validation is bypassed - running in local development mode");
        }
    }
}
