# Auth0 Setup Guide - Card Fraud Platform

> **Single source of truth** for Auth0 configuration across all projects.

**Last Updated:** 2026-01-31

> **Important:** All Auth0 values are available via Doppler. Run `uv run doppler-secrets-verify` to check.
> This guide uses `<placeholder>` values - replace with your actual Auth0 tenant details.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Manual Setup (One-Time)](#2-manual-setup-one-time)
3. [Automated Setup (Bootstrap Scripts)](#3-automated-setup-bootstrap-scripts)
4. [SPA (React UI) Configuration](#4-spa-react-ui-configuration)
5. [Cleanup and Reset](#5-cleanup-and-reset)
6. [Troubleshooting](#6-troubleshooting)

---

## 1. Overview

### What's Manual vs Automated

| Resource | Setup Method | Notes |
|----------|--------------|-------|
| Auth0 Tenant | Manual (Anthropic provided) | `<AUTH0_DOMAIN>` |
| Management M2M App | Manual | Created once, used by all bootstrap scripts |
| Google OAuth Connection | Manual | Requires Google Cloud Console setup |
| Username-Password Connection | Manual (enable) | For test users |
| **APIs (Resource Servers)** | **Automated** | Bootstrap scripts |
| **Roles** | **Automated** | Bootstrap scripts |
| **Permissions** | **Automated** | Bootstrap scripts |
| **SPA Application** | **Automated** | Bootstrap scripts |
| **M2M Applications** | **Automated** | Bootstrap scripts |
| **Actions** | **Automated** | Bootstrap scripts |
| **Test Users** | **Automated** | Bootstrap scripts |

---

## 2. Manual Setup (One-Time)

These steps only need to be done ONCE when setting up the platform.

### 2.1 Management M2M Application (ALREADY DONE)

This was created manually and provides access to the Auth0 Management API.

**Current Configuration:**
```
Name: Platform Management M2M (or similar)
Client ID: <AUTH0_MGMT_CLIENT_ID>
Client Secret: [stored in Doppler as AUTH0_MGMT_CLIENT_SECRET]
```

**Permissions Required:**
- `read:users`, `create:users`, `update:users`, `delete:users`
- `read:roles`, `create:roles`, `update:roles`, `delete:roles`
- `read:role_members`, `create:role_members`
- `read:resource_servers`, `create:resource_servers`, `update:resource_servers`, `delete:resource_servers`
- `read:clients`, `create:clients`, `update:clients`, `delete:clients`
- `read:client_grants`, `create:client_grants`, `update:client_grants`, `delete:client_grants`
- `read:actions`, `create:actions`, `update:actions`, `delete:actions`
- `read:triggers`, `update:triggers`

**DO NOT DELETE THIS APPLICATION** - All bootstrap scripts depend on it.

### 2.2 Google OAuth Connection (ALREADY DONE)

This allows users to sign in with their Google accounts.

**Setup Steps (if recreating):**

1. **Google Cloud Console:**
   ```
   - Go to: https://console.cloud.google.com/
   - Create or select project
   - Enable "Google+ API" and "Google Identity Services"
   - Create OAuth Client ID (Web application)
   - Add authorized redirect URI: https://<AUTH0_DOMAIN>/login/callback
   - Copy Client ID and Client Secret
   ```

2. **Auth0 Dashboard:**
   ```
   - Go to: Authentication → Social
   - Click "google-oauth2" (or create new)
   - Enter Google Client ID and Client Secret
   - Enable for applications
   ```

### 2.3 Username-Password-Authentication Connection

Required for test users (Playwright automation).

**Setup Steps:**

1. **Auth0 Dashboard:**
   ```
   - Go to: Authentication → Database
   - Find "Username-Password-Authentication"
   - Ensure it's enabled
   - Enable for applications that need it
   ```

### 2.4 Enable RBAC on APIs

After running bootstrap, enable RBAC for each API:

1. **Auth0 Dashboard:**
   ```
   - Go to: Applications → APIs
   - Click on each API (e.g., "Fraud Rule Management API")
   - Go to "Settings" tab
   - Under "RBAC Settings":
     - Enable "Enable RBAC"
     - Enable "Add Permissions in the Access Token"
   - Save
   ```

---

## 3. Automated Setup (Bootstrap Scripts)

### 3.1 Prerequisites

Before running bootstrap scripts:

1. **Doppler configured** with AUTH0_MGMT_* credentials
2. **Management M2M app** exists (see 2.1)
3. **Google OAuth** enabled (see 2.2)

### 3.2 Run Bootstrap

```powershell
# Rule Management (creates all shared resources)
cd ../card-fraud-rule-management
doppler setup --project card-fraud-rule-management --config local
uv sync
uv run auth0-bootstrap --yes --verbose

# Rule Engine
cd ../card-fraud-rule-engine
doppler setup --project card-fraud-rule-engine --config local
uv sync
uv run auth0-bootstrap --yes --verbose

# Transaction Management
cd ../card-fraud-transaction-management
doppler setup --project card-fraud-transaction-management --config local
uv sync
uv run auth0-bootstrap --yes --verbose
```

### 3.3 What Bootstrap Creates

**Rule Management Bootstrap (Central Hub):**
- API: `https://fraud-rule-management-api`
- Permissions: `rule:create`, `rule:update`, `rule:submit`, `rule:approve`, `rule:reject`, `rule:read`
- Roles: `PLATFORM_ADMIN`, `RULE_MAKER`, `RULE_CHECKER`, `RULE_VIEWER`, `FRAUD_ANALYST`, `FRAUD_SUPERVISOR`
- SPA App: `Fraud Intelligence Portal`
- M2M App: `Fraud Rule Management M2M`
- Actions: Role injection for tokens
- Test Users: 6 users for Playwright
- **Auto-synced to Doppler:**
  - `AUTH0_CLIENT_ID` - M2M client ID
  - `AUTH0_CLIENT_SECRET` - M2M client secret
  - `TEST_USER_*_PASSWORD` - 5 test user passwords (auto-generated)

**Rule Engine Bootstrap:**
- API: `https://fraud-rule-engine-api`
- Scopes: `execute:rules`, `read:results`, `replay:transactions`, `read:metrics`
- M2M App: `Fraud Rule Engine M2M`
- No roles (scope-based only, M2M service)
- **Auto-synced to Doppler:**
  - `AUTH0_CLIENT_ID` - M2M client ID
  - `AUTH0_CLIENT_SECRET` - M2M client secret

**Transaction Management Bootstrap:**
- API: `https://fraud-transaction-management-api`
- Permissions: `txn:view`, `txn:comment`, `txn:flag`, `txn:recommend`, `txn:approve`, `txn:block`, `txn:override`
- M2M App: `Fraud Transaction Management M2M`
- Uses shared roles (created by rule-management)
- **Auto-synced to Doppler:**
  - `AUTH0_CLIENT_ID` - M2M client ID
  - `AUTH0_CLIENT_SECRET` - M2M client secret

### 3.4 Verify Setup

```powershell
# Verify Auth0 resources exist
uv run auth0-verify

# Verify secrets in Doppler
doppler secrets --project=card-fraud-rule-engine --config=local

# Check specific Auth0 secrets
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_DOMAIN
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_CLIENT_ID
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_AUDIENCE
```

### 3.5 Secrets Auto-Synced to Doppler

After running `auth0-bootstrap`, the following secrets are automatically synced to Doppler:

| Secret | Description |
|--------|-------------|
| `AUTH0_CLIENT_ID` | M2M client ID (created by bootstrap) |
| `AUTH0_CLIENT_SECRET` | M2M client secret (only on first creation) |
| `AUTH0_AUDIENCE` | API identifier: `https://fraud-rule-engine-api` |
| `AUTH0_DOMAIN` | Your Auth0 tenant domain |

**Note:** The `AUTH0_MGMT_*` secrets must be set manually - these are NOT created by bootstrap and must be obtained from a project admin.

---

## 4. SPA (React UI) Configuration

### 4.1 Get SPA Client ID

After running rule-management bootstrap:

1. **Auth0 Dashboard:**
   ```
   - Go to: Applications → Applications
   - Find: "Fraud Intelligence Portal"
   - Copy: Client ID
   ```

### 4.2 Environment Variables

Create `.env.local` in intelligence-portal:

```bash
# Auth0 Configuration
VITE_AUTH0_DOMAIN=<AUTH0_DOMAIN>
VITE_AUTH0_CLIENT_ID=<spa-client-id-from-dashboard>

# API Audiences
VITE_AUTH0_AUDIENCE_RULE_MANAGEMENT=https://fraud-rule-management-api
VITE_AUTH0_AUDIENCE_RULE_ENGINE=https://fraud-rule-engine-api
VITE_AUTH0_AUDIENCE_TRANSACTION_MGMT=https://fraud-transaction-management-api

# API Base URLs
VITE_API_URL_RULE_MANAGEMENT=http://localhost:8000/api/v1
VITE_API_URL_RULE_ENGINE=http://localhost:8001/api/v1
VITE_API_URL_TRANSACTION_MGMT=http://localhost:8002/api/v1
```

Or use Doppler:

```powershell
doppler secrets set --project card-fraud-intelligence-portal --config local \
  VITE_AUTH0_DOMAIN="<AUTH0_DOMAIN>" \
  VITE_AUTH0_CLIENT_ID="<spa-client-id>"
```

### 4.3 React Setup

```typescript
// src/main.tsx
import { Auth0Provider } from '@auth0/auth0-react';

<Auth0Provider
  domain={import.meta.env.VITE_AUTH0_DOMAIN}
  clientId={import.meta.env.VITE_AUTH0_CLIENT_ID}
  authorizationParams={{
    redirect_uri: window.location.origin,
    audience: import.meta.env.VITE_AUTH0_AUDIENCE_RULE_MANAGEMENT,
    scope: 'openid profile email',
  }}
  cacheLocation="localstorage"
>
  <App />
</Auth0Provider>
```

### 4.4 Using Roles and Permissions in UI

```typescript
// src/hooks/useAuth.ts
import { useAuth0 } from '@auth0/auth0-react';

export const useAuth = () => {
  const { user, getAccessTokenSilently } = useAuth0();

  // Get roles from token
  const roles = user?.['https://fraud-rule-management-api/roles'] || [];

  // Role checks
  const isPlatformAdmin = roles.includes('PLATFORM_ADMIN');
  const isRuleMaker = roles.includes('RULE_MAKER');
  const isRuleChecker = roles.includes('RULE_CHECKER');
  const isFraudAnalyst = roles.includes('FRAUD_ANALYST');
  const isFraudSupervisor = roles.includes('FRAUD_SUPERVISOR');

  // Get token with permissions
  const getTokenForApi = async (audience: string) => {
    return await getAccessTokenSilently({
      authorizationParams: { audience },
    });
  };

  return {
    roles,
    isPlatformAdmin,
    isRuleMaker,
    isRuleChecker,
    isFraudAnalyst,
    isFraudSupervisor,
    getTokenForApi,
  };
};
```

### 4.5 Permission-Based UI

```tsx
// Example: Show approve button only if user has permission
const { isRuleChecker, isPlatformAdmin } = useAuth();

{(isRuleChecker || isPlatformAdmin) && (
  <Button onClick={handleApprove} disabled={isOwnRule}>
    Approve Rule
  </Button>
)}
```

---

## 5. Cleanup and Reset

### 5.1 Full Cleanup

To start fresh (delete everything except Management M2M):

```powershell
cd ../card-fraud-rule-management
uv run auth0-cleanup --yes --verbose
```

This deletes:
- All platform APIs
- All roles (old and new)
- All M2M apps (except Management M2M)
- SPA app
- Actions
- Test users

This preserves:
- Management M2M Application
- Google OAuth Connection
- Username-Password-Authentication Connection

### 5.2 After Cleanup

Re-run bootstrap scripts to recreate everything:

```powershell
uv run auth0-bootstrap --yes --verbose
```

---

## 6. Troubleshooting

### "Missing required env var: AUTH0_MGMT_*"

**Cause:** Management API credentials not configured in Doppler

**Fix:**
```powershell
# Check Doppler is configured
doppler setup --project=card-fraud-rule-engine --config=local

# Check which secrets exist
doppler secrets --only-names

# Contact project admin to get MANAGEMENT_API credentials:
# - AUTH0_MGMT_DOMAIN
# - AUTH0_MGMT_CLIENT_ID
# - AUTH0_MGMT_CLIENT_SECRET
```

### "Unauthorized" or "Forbidden" errors

**Cause:** Management M2M app missing permissions or invalid credentials

**Fix:**
- Verify Management M2M has all required permissions (see Section 2.1)
- Check AUTH0_MGMT_CLIENT_ID and AUTH0_MGMT_CLIENT_SECRET are correct
- Ensure Management M2M is authorized for the Management API

### "API already exists" error during bootstrap

**Cause:** Resources from previous run

**Fix:** Bootstrap is idempotent and will update existing resources. If you need to start fresh:
```powershell
# Run cleanup (requires rule-management project)
cd ../card-fraud-rule-management
uv run auth0-cleanup --yes

# Then re-run bootstrap
uv run auth0-bootstrap --yes
```

### Test users not created

Test users are created automatically with auto-generated passwords:
- Passwords are generated securely and synced to Doppler automatically
- If a test user already exists, a warning is shown (password cannot be updated)
- Use `--skip-test-users` flag to skip test user creation entirely

**Fix if test users fail:**
1. Check Auth0 Dashboard → Authentication → Database → Username-Password-Authentication
2. Ensure the connection is enabled
3. Verify AUTH0_MGMT_* credentials have `create:users` permission

### SPA login not working

1. Check callback URLs in Auth0 Dashboard match your app URL
2. Verify SPA Client ID is correct (check Doppler: `VITE_AUTH0_CLIENT_ID`)
3. Check browser console for errors
4. Ensure Allowed Origins include your development URL

### Permissions not in token

1. Enable RBAC on the API (Dashboard → APIs → Settings)
2. Enable "Add Permissions in the Access Token"
3. Re-login to get new token

### "Client ID already in use" error

**Cause:** Trying to create an M2M app that already exists

**Fix:** Bootstrap handles this automatically - it will update the existing app instead of creating a new one.

### "Bootstrap fails after timeout"

**Cause:** Auth0 API rate limiting or network issues

**Fix:**
```powershell
# Run with verbose output to see detailed errors
uv run auth0-bootstrap --yes --verbose

# If rate limited, wait a few minutes and retry
# Auth0 Management API has rate limits per token
```

### JWT validation fails in application

**Cause:** JWT not enabled or wrong audience

**Fix:**
```powershell
# Verify AUTH0_AUDIENCE matches API identifier
doppler secrets get AUTH0_AUDIENCE

# Ensure JWT is enabled in the application profile
# For load testing: uv run doppler-load-test
# For dev mode: JWT is disabled by default
```

---

## Appendix: Doppler Secrets Reference

### Secrets Synced Automatically

These secrets are auto-synced by bootstrap scripts (no manual setup required):

| Secret | Synced By | Notes |
|--------|-----------|-------|
| `AUTH0_CLIENT_ID` | Each project's bootstrap | M2M client ID |
| `AUTH0_CLIENT_SECRET` | Each project's bootstrap | M2M client secret (only on first creation) |
| `TEST_USER_*_PASSWORD` | rule-management bootstrap | Auto-generated, synced to all projects |

### card-fraud-rule-management

```yaml
# Management (shared - for bootstrap) - MANUAL SETUP REQUIRED
AUTH0_MGMT_DOMAIN: <AUTH0_DOMAIN>
AUTH0_MGMT_CLIENT_ID: <AUTH0_MGMT_CLIENT_ID>
AUTH0_MGMT_CLIENT_SECRET: <secret>

# API Configuration - AUTO-SYNCED BY BOOTSTRAP
AUTH0_AUDIENCE: https://fraud-rule-management-api
AUTH0_DOMAIN: <AUTH0_DOMAIN>
AUTH0_CLIENT_ID: <auto-synced-by-bootstrap>
AUTH0_CLIENT_SECRET: <auto-synced-by-bootstrap>

# SPA Configuration - MANUAL SETUP REQUIRED
AUTH0_SPA_APP_NAME: Fraud Intelligence Portal
AUTH0_SPA_CALLBACK_URLS: http://localhost:3000,http://localhost:5173
AUTH0_SPA_ALLOWED_ORIGINS: http://localhost:3000,http://localhost:5173
AUTH0_SPA_ALLOWED_LOGOUT_URLS: http://localhost:3000,http://localhost:5173

# Test User Passwords - AUTO-SYNCED BY BOOTSTRAP
TEST_USER_PLATFORM_ADMIN_PASSWORD: <auto-generated>
TEST_USER_RULE_MAKER_PASSWORD: <auto-generated>
TEST_USER_RULE_CHECKER_PASSWORD: <auto-generated>
TEST_USER_FRAUD_ANALYST_PASSWORD: <auto-generated>
TEST_USER_FRAUD_SUPERVISOR_PASSWORD: <auto-generated>
```

### Test User Passwords - All Projects

Test user passwords are generated once by rule-management bootstrap and must be synced to all projects' Doppler configs (local + test):

```powershell
# Already done automatically during rule-management bootstrap
# Manual sync if needed:
doppler secrets get TEST_USER_* --project card-fraud-rule-management --config local --plain

# Then set in other projects:
doppler secrets set --project card-fraud-intelligence-portal --config local \
  TEST_USER_PLATFORM_ADMIN_PASSWORD=<value> \
  TEST_USER_RULE_MAKER_PASSWORD=<value> \
  # ... etc
```

---

**End of Document**
