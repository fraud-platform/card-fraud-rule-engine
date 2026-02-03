package com.fraud.engine.security;

import com.fraud.engine.config.SecurityConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Security utilities for JWT validation and scope checking.
 *
 * The Rule Engine uses M2M scope-based authorization:
 * - Tokens are obtained via Client Credentials flow
 * - Scopes are checked in the `scope` claim (space-separated)
 * - No roles are used (roles are for human users)
 *
 * When {@code SECURITY_SKIP_JWT_VALIDATION=true} (local mode) or
 * {@code quarkus.smallrye-jwt.enabled=false} (dev/test profile),
 * all validation is skipped to allow local testing without JWT.
 */
@ApplicationScoped
public class ScopeValidator {

    private static final Logger LOG = Logger.getLogger(ScopeValidator.class);

    @Inject
    SecurityConfig securityConfig;

    @ConfigProperty(name = "quarkus.smallrye-jwt.enabled", defaultValue = "true")
    boolean jwtEnabled = true; // default true so 'new ScopeValidator()' in unit tests validates

    public boolean skipJwtValidation() {
        if (securityConfig != null && securityConfig.skipJwtValidation()) {
            return true;
        }
        return !jwtEnabled;
    }

    /**
     * Parses scopes from a space-separated scope string.
     *
     * @param scopeString the space-separated scope string
     * @return set of scopes, or empty set if none
     */
    private Set<String> parseScopes(String scopeString) {
        if (scopeString == null || scopeString.isEmpty()) {
            return Set.of();
        }
        return Set.of(scopeString.split("\\s+"));
    }

    /**
     * Checks if the JWT has the required scope.
     *
     * @param jwt      the JWT token
     * @param required the required scope
     * @return true if the scope is present
     */
    public boolean hasScope(JsonWebToken jwt, String required) {
        if (jwt == null) {
            LOG.debug("JWT is null");
            return false;
        }

        Set<String> scopes = parseScopes(jwt.getClaim("scope"));
        boolean hasScope = scopes.contains(required);

        LOG.debugf("Scope check: required=%s, has=%s, token_scopes=%s",
                required, hasScope, scopes);

        return hasScope;
    }

    /**
     * Checks if the JWT has all required scopes.
     *
     * @param jwt       the JWT token
     * @param requireds the required scopes
     * @return true if all scopes are present
     */
    public boolean hasAllScopes(JsonWebToken jwt, String... requireds) {
        if (jwt == null) {
            return false;
        }

        Set<String> scopes = parseScopes(jwt.getClaim("scope"));

        for (String required : requireds) {
            if (!scopes.contains(required)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if the JWT has any of the required scopes.
     *
     * @param jwt       the JWT token
     * @param requireds the required scopes
     * @return true if any scope is present
     */
    public boolean hasAnyScope(JsonWebToken jwt, String... requireds) {
        if (jwt == null) {
            return false;
        }

        Set<String> scopes = parseScopes(jwt.getClaim("scope"));

        for (String required : requireds) {
            if (scopes.contains(required)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets all scopes from the JWT.
     *
     * @param jwt the JWT token
     * @return set of scopes, or empty set if none
     */
    public Set<String> getScopes(JsonWebToken jwt) {
        if (jwt == null) {
            return Set.of();
        }

        return parseScopes(jwt.getClaim("scope"));
    }

    /**
     * Validates that the token is an M2M token (client credentials grant).
     *
     * M2M tokens have the `gty` claim set to "client-credentials".
     *
     * @param jwt the JWT token
     * @return true if it's an M2M token
     */
    public boolean isM2MToken(JsonWebToken jwt) {
        if (jwt == null) {
            return false;
        }

        String gty = jwt.getClaim("gty");
        return "client-credentials".equals(gty);
    }

    /**
     * Validates that this is NOT a human user token.
     *
     * Human user tokens have roles but no `gty` claim or `gty` != "client-credentials".
     *
     * @param jwt the JWT token
     * @return true if it's NOT a human user (i.e., it's M2M)
     */
    public boolean isNotHumanUser(JsonWebToken jwt) {
        if (jwt == null) {
            return false;
        }

        String grantType = jwt.getClaim("gty");
        if ("client-credentials".equals(grantType)) {
            return true;
        }

        return !hasRolesClaim(jwt);
    }

    private boolean hasRolesClaim(JsonWebToken jwt) {
        Object roles = jwt.getClaim("https://fraud-rule-engine-api/roles");
        Object roles2 = jwt.getClaim("roles");
        return roles != null || roles2 != null;
    }

    private void logRolesIgnored() {
        LOG.warn("M2M token contains roles claim; ignoring roles for validation");
    }

    /**
     * Gets the client ID from the JWT subject.
     *
     * M2M tokens have subject format: "{clientId}@clients"
     *
     * @param jwt the JWT token
     * @return the client ID, or null if not an M2M token
     */
    public String getClientId(JsonWebToken jwt) {
        if (jwt == null) {
            return null;
        }

        String subject = jwt.getSubject();
        if (subject != null && subject.endsWith("@clients")) {
            return subject.substring(0, subject.length() - "@clients".length());
        }

        return null;
    }

    /**
     * Validates that the token is an M2M token and has the required scope.
     * Throws ForbiddenException if validation fails.
     *
     * @param jwt the JWT token
     * @param requiredScope the required scope
     * @throws ForbiddenException if the token is not M2M or missing the required scope
     */
    public void requireM2MWithScope(JsonWebToken jwt, String requiredScope) {
        if (skipJwtValidation()) {
            LOG.debug("JWT validation skipped — skipping M2M scope validation");
            return;
        }
        if (jwt == null) {
            throw new ForbiddenException("Missing authentication token");
        }

        // Check for M2M token (client-credentials grant)
        String grantType = jwt.getClaim("gty");
        if (!"client-credentials".equals(grantType)) {
            LOG.warn("Non-M2M token rejected - Rule Engine only accepts M2M tokens");
            throw new ForbiddenException("Only M2M tokens are accepted (client-credentials grant required)");
        }

        // Warn if roles are present on an M2M token (Auth0 Actions may inject roles)
        if (hasRolesClaim(jwt)) {
            logRolesIgnored();
        }

        // Check scope
        Set<String> scopes = parseScopes(jwt.getClaim("scope"));
        if (scopes == null || !scopes.contains(requiredScope)) {
            LOG.warnf("Missing required scope: %s", requiredScope);
            throw new ForbiddenException("Missing required scope: " + requiredScope);
        }
    }

    /**
     * Validates that the token is an M2M token.
     * Throws ForbiddenException if validation fails.
     *
     * @param jwt the JWT token
     * @throws ForbiddenException if the token is not M2M
     */
    public void requireM2MToken(JsonWebToken jwt) {
        if (skipJwtValidation()) {
            LOG.debug("JWT validation skipped — skipping M2M token validation");
            return;
        }
        if (jwt == null) {
            throw new ForbiddenException("Missing authentication token");
        }

        // Check for M2M token (client-credentials grant)
        String grantType = jwt.getClaim("gty");
        if (!"client-credentials".equals(grantType)) {
            LOG.warn("Non-M2M token rejected - Rule Engine only accepts M2M tokens");
            throw new ForbiddenException("Only M2M tokens are accepted (client-credentials grant required)");
        }

        // Warn if roles are present on an M2M token (Auth0 Actions may inject roles)
        if (hasRolesClaim(jwt)) {
            logRolesIgnored();
        }
    }
}
