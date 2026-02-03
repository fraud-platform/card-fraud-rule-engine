package com.fraud.engine.security;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class ScopeValidatorTest {

    private ScopeValidator scopeValidator;
    private JsonWebToken jwt;

    @BeforeEach
    void setUp() {
        scopeValidator = new ScopeValidator();
        jwt = Mockito.mock(JsonWebToken.class);
    }

    // ===== parseScopes Tests =====

    @Test
    void testParseScopes_WithSingleScope() {
        Set<String> scopes = scopeValidator.getScopes(createJwtWithScope("execute:rules"));
        assertThat(scopes).containsExactly("execute:rules");
    }

    @Test
    void testParseScopes_WithMultipleScopes() {
        Set<String> scopes = scopeValidator.getScopes(createJwtWithScope("execute:rules read:results replay:transactions"));
        assertThat(scopes).containsExactlyInAnyOrder("execute:rules", "read:results", "replay:transactions");
    }

    @Test
    void testParseScopes_WithNullScope() {
        Set<String> scopes = scopeValidator.getScopes(jwt);
        assertThat(scopes).isEmpty();
    }

    @Test
    void testParseScopes_WithEmptyScope() {
        Set<String> scopes = scopeValidator.getScopes(createJwtWithScope(""));
        assertThat(scopes).isEmpty();
    }

    // ===== hasScope Tests =====

    @Test
    void testHasScope_WithValidScope() {
        when(jwt.getClaim("scope")).thenReturn("execute:rules read:results");
        assertThat(scopeValidator.hasScope(jwt, "execute:rules")).isTrue();
    }

    @Test
    void testHasScope_WithMissingScope() {
        when(jwt.getClaim("scope")).thenReturn("read:results");
        assertThat(scopeValidator.hasScope(jwt, "execute:rules")).isFalse();
    }

    @Test
    void testHasScope_WithNullJwt() {
        assertThat(scopeValidator.hasScope(null, "execute:rules")).isFalse();
    }

    @Test
    void testHasScope_WithNullScopeClaim() {
        when(jwt.getClaim("scope")).thenReturn(null);
        assertThat(scopeValidator.hasScope(jwt, "execute:rules")).isFalse();
    }

    // ===== hasAllScopes Tests =====

    @Test
    void testHasAllScopes_WithAllScopes() {
        when(jwt.getClaim("scope")).thenReturn("execute:rules read:results replay:transactions");
        assertThat(scopeValidator.hasAllScopes(jwt, "execute:rules", "read:results")).isTrue();
    }

    @Test
    void testHasAllScopes_WithMissingScope() {
        when(jwt.getClaim("scope")).thenReturn("execute:rules read:results");
        assertThat(scopeValidator.hasAllScopes(jwt, "execute:rules", "replay:transactions")).isFalse();
    }

    @Test
    void testHasAllScopes_WithNullJwt() {
        assertThat(scopeValidator.hasAllScopes(null, "execute:rules")).isFalse();
    }

    // ===== hasAnyScope Tests =====

    @Test
    void testHasAnyScope_WithOneMatchingScope() {
        when(jwt.getClaim("scope")).thenReturn("execute:rules read:results");
        assertThat(scopeValidator.hasAnyScope(jwt, "execute:rules", "replay:transactions")).isTrue();
    }

    @Test
    void testHasAnyScope_WithNoMatchingScopes() {
        when(jwt.getClaim("scope")).thenReturn("read:results");
        assertThat(scopeValidator.hasAnyScope(jwt, "execute:rules", "replay:transactions")).isFalse();
    }

    @Test
    void testHasAnyScope_WithNullJwt() {
        assertThat(scopeValidator.hasAnyScope(null, "execute:rules")).isFalse();
    }

    // ===== isM2MToken Tests =====

    @Test
    void testIsM2MToken_WithClientCredentials() {
        when(jwt.getClaim("gty")).thenReturn("client-credentials");
        assertThat(scopeValidator.isM2MToken(jwt)).isTrue();
    }

    @Test
    void testIsM2MToken_WithNullGty() {
        when(jwt.getClaim("gty")).thenReturn(null);
        assertThat(scopeValidator.isM2MToken(jwt)).isFalse();
    }

    @Test
    void testIsM2MToken_WithPasswordGrant() {
        when(jwt.getClaim("gty")).thenReturn("password");
        assertThat(scopeValidator.isM2MToken(jwt)).isFalse();
    }

    @Test
    void testIsM2MToken_WithNullJwt() {
        assertThat(scopeValidator.isM2MToken(null)).isFalse();
    }

    // ===== isNotHumanUser Tests =====

    @Test
    void testIsNotHumanUser_WithM2MToken() {
        when(jwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        when(jwt.getClaim("roles")).thenReturn(null);
        assertThat(scopeValidator.isNotHumanUser(jwt)).isTrue();
    }

    @Test
    void testIsNotHumanUser_WithRolesClaim() {
        when(jwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(Set.of("admin"));
        assertThat(scopeValidator.isNotHumanUser(jwt)).isFalse();
    }

    @Test
    void testIsNotHumanUser_WithStandardRolesClaim() {
        when(jwt.getClaim("roles")).thenReturn(Set.of("user"));
        assertThat(scopeValidator.isNotHumanUser(jwt)).isFalse();
    }

    @Test
    void testIsNotHumanUser_WithNullJwt() {
        assertThat(scopeValidator.isNotHumanUser(null)).isFalse();
    }

    // ===== getClientId Tests =====

    @Test
    void testGetClientId_WithM2MToken() {
        when(jwt.getSubject()).thenReturn("abc123@clients");
        assertThat(scopeValidator.getClientId(jwt)).isEqualTo("abc123");
    }

    @Test
    void testGetClientId_WithUserToken() {
        when(jwt.getSubject()).thenReturn("user|123");
        assertThat(scopeValidator.getClientId(jwt)).isNull();
    }

    @Test
    void testGetClientId_WithNullSubject() {
        when(jwt.getSubject()).thenReturn(null);
        assertThat(scopeValidator.getClientId(jwt)).isNull();
    }

    @Test
    void testGetClientId_WithNullJwt() {
        assertThat(scopeValidator.getClientId(null)).isNull();
    }

    // ===== requireM2MWithScope Tests =====

    @Test
    void testRequireM2MWithScope_WithValidM2MTokenAndScope() {
        JsonWebToken m2mJwt = createM2MJwt("execute:rules read:results");
        assertThatCode(() -> scopeValidator.requireM2MWithScope(m2mJwt, "execute:rules"))
                .doesNotThrowAnyException();
    }

    @Test
    void testRequireM2MWithScope_WithNullJwt() {
        assertThatThrownBy(() -> scopeValidator.requireM2MWithScope(null, "execute:rules"))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("Missing authentication token");
    }

    @Test
    void testRequireM2MWithScope_WithMissingGtyClaim() {
        JsonWebToken humanJwt = createJwtWithScope("execute:rules");
        when(humanJwt.getClaim("gty")).thenReturn(null);
        when(humanJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        when(humanJwt.getClaim("roles")).thenReturn(null);

        assertThatThrownBy(() -> scopeValidator.requireM2MWithScope(humanJwt, "execute:rules"))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("client-credentials");
    }

    @Test
    void testRequireM2MWithScope_WithPasswordGrant() {
        JsonWebToken passwordJwt = createJwtWithScope("execute:rules");
        when(passwordJwt.getClaim("gty")).thenReturn("password");
        when(passwordJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        when(passwordJwt.getClaim("roles")).thenReturn(null);

        assertThatThrownBy(() -> scopeValidator.requireM2MWithScope(passwordJwt, "execute:rules"))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("client-credentials");
    }

    @Test
    void testRequireM2MWithScope_WithHumanTokenWithRoles() {
        JsonWebToken m2mJwt = createJwtWithScope("execute:rules");
        when(m2mJwt.getClaim("gty")).thenReturn("client-credentials");
        when(m2mJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(Set.of("admin"));

        assertThatCode(() -> scopeValidator.requireM2MWithScope(m2mJwt, "execute:rules"))
                .doesNotThrowAnyException();
    }

    @Test
    void testRequireM2MWithScope_WithMissingScope() {
        JsonWebToken m2mJwt = createM2MJwt("read:results");
        assertThatThrownBy(() -> scopeValidator.requireM2MWithScope(m2mJwt, "execute:rules"))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("Missing required scope");
    }

    // ===== requireM2MToken Tests =====

    @Test
    void testRequireM2MToken_WithValidM2MToken() {
        JsonWebToken m2mJwt = createM2MJwt("execute:rules");
        assertThatCode(() -> scopeValidator.requireM2MToken(m2mJwt))
                .doesNotThrowAnyException();
    }

    @Test
    void testRequireM2MToken_WithNullJwt() {
        assertThatThrownBy(() -> scopeValidator.requireM2MToken(null))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("Missing authentication token");
    }

    @Test
    void testRequireM2MToken_WithHumanToken() {
        JsonWebToken humanJwt = Mockito.mock(JsonWebToken.class);
        when(humanJwt.getClaim("gty")).thenReturn(null);
        when(humanJwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        when(humanJwt.getClaim("roles")).thenReturn(null);

        assertThatThrownBy(() -> scopeValidator.requireM2MToken(humanJwt))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class)
                .hasMessageContaining("client-credentials");
    }

    // ===== Helper Methods =====

    private JsonWebToken createJwtWithScope(String scope) {
        JsonWebToken token = Mockito.mock(JsonWebToken.class);
        when(token.getClaim("scope")).thenReturn(scope);
        return token;
    }

    private JsonWebToken createM2MJwt(String scope) {
        JsonWebToken token = Mockito.mock(JsonWebToken.class);
        when(token.getClaim("scope")).thenReturn(scope);
        when(token.getClaim("gty")).thenReturn("client-credentials");
        when(token.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        when(token.getClaim("roles")).thenReturn(null);
        when(token.getSubject()).thenReturn("client-id@clients");
        return token;
    }
}
