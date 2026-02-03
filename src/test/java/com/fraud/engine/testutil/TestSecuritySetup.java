package com.fraud.engine.testutil;

import com.fraud.engine.security.ScopeValidator;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.mockito.Mockito;
import jakarta.ws.rs.ForbiddenException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Test utility for setting up security in integration tests.
 *
 * <p>This class provides helper methods to mock the {@link ScopeValidator}
 * for tests that need to bypass authentication or test authorization behavior.</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * @InjectMock
 * ScopeValidator scopeValidator;
 *
 * @BeforeEach
 * void setUp() {
 *     TestSecuritySetup.allowAllScopes(scopeValidator);
 * }
 *
 * @Test
 * @TestSecurity(user = "test-m2m@clients")
 * void testSomething() {
 *     // Test code here - auth is bypassed
 * }
 * }</pre>
 */
public final class TestSecuritySetup {
    private TestSecuritySetup() {}

    /**
     * Configure ScopeValidator to allow all scope checks.
     *
     * <p>Use this for business logic tests where authentication is not the focus.</p>
     *
     * @param mock the mocked ScopeValidator
     */
    public static void allowAllScopes(ScopeValidator mock) {
        doNothing().when(mock).requireM2MWithScope(any(), anyString());
        doNothing().when(mock).requireM2MToken(any());
    }

    /**
     * Configure ScopeValidator to reject all scope checks.
     *
     * <p>Use this for testing authorization rejection scenarios.</p>
     *
     * @param mock the mocked ScopeValidator
     */
    public static void rejectAllScopes(ScopeValidator mock) {
        doThrow(new ForbiddenException("Missing authentication token"))
            .when(mock).requireM2MWithScope(any(), anyString());
    }

    /**
     * Create a mock M2M JsonWebToken with the given scope.
     *
     * <p>The token is configured as a valid M2M token with:
     * <ul>
     *   <li>{@code gty: "client-credentials"} claim</li>
     *   <li>The specified scope</li>
     *   <li>No roles claims (null)</li>
     *   <li>Subject: {@code test-m2m@clients}</li>
     * </ul>
     *
     * @param scope the scope to include in the token (e.g., "execute:rules")
     * @return a mock M2M JsonWebToken
     */
    public static JsonWebToken createM2MToken(String scope) {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);
        Mockito.when(jwt.getClaim("gty")).thenReturn("client-credentials");
        Mockito.when(jwt.getClaim("scope")).thenReturn(scope);
        Mockito.when(jwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        Mockito.when(jwt.getClaim("roles")).thenReturn(null);
        Mockito.when(jwt.getSubject()).thenReturn("test-m2m@clients");
        return jwt;
    }

    /**
     * Create a mock M2M JsonWebToken with multiple scopes.
     *
     * @param scopes the scopes to include in the token (space-separated)
     * @return a mock M2M JsonWebToken
     */
    public static JsonWebToken createM2MTokenWithScopes(String scopes) {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);
        Mockito.when(jwt.getClaim("gty")).thenReturn("client-credentials");
        Mockito.when(jwt.getClaim("scope")).thenReturn(scopes);
        Mockito.when(jwt.getClaim("https://fraud-rule-engine-api/roles")).thenReturn(null);
        Mockito.when(jwt.getClaim("roles")).thenReturn(null);
        Mockito.when(jwt.getSubject()).thenReturn("test-m2m@clients");
        return jwt;
    }
}
