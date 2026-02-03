# Doppler Secrets Setup Guide

This guide covers setting up Doppler secrets for the **card-fraud-rule-engine** project.

## Overview

**Doppler** is our **primary secrets manager** for all environments:
- `local` - Local development (Docker Redis + Redpanda)
- `test` - CI/CD and integration testing (TBD - Redis Cloud future)
- `prod` - Production deployment (TBD - Redis Cloud future)

**Never use `.env` files** - All secrets are managed through Doppler.

## Prerequisites

### 1. Install Doppler CLI

**Windows:**
```powershell
# Using winget (recommended)
winget install doppler.cli

# Or download directly from https://cli.doppler.com
# Download the Windows installer and run it
```

**macOS:**
```bash
# Using Homebrew
brew install doppler

# Or using curl
curl -sL --tlsv1.2 --proto "=https" 'https://cli.doppler.com/install.sh' | sh
```

**Linux:**
```bash
# Using curl
curl -sL --tlsv1.2 --proto "=https" 'https://cli.doppler.com/install.sh' | sh

# Or using apt
# Add Doppler's GPG key and repository
# See: https://cli.doppler.com/docs/install/linux
```

### 2. Login to Doppler

```powershell
# Login (will open browser for authentication)
doppler login

# Or use token-based authentication
doppler login --token <your-token>
```

### 3. Get Access to the Project

You need to be invited to the `card-fraud-rule-engine` Doppler project.

1. Ask a project admin to invite you via the Doppler dashboard
2. Or request access via your team's onboarding process
3. Once invited, you'll have access to the `local` config

### 4. Verify Access

```powershell
# List all secrets in the local config
doppler secrets --project=card-fraud-rule-engine --config=local

# Verify access to specific secrets
doppler secrets --project=card-fraud-rule-engine --config=local get REDIS_URL
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_CLIENT_ID
```

## This Project: Consumes Rules

**Important:** This project (`card-fraud-rule-engine`) is a runtime engine that:
- Connects to Redis for velocity state
- Uses Redpanda/Kafka for publishing decision events
- Calls Auth0 for token validation
- Reads compiled rulesets from MinIO (shared with rule-management)

## Required Secrets

### Local Config (Docker Redis + Redpanda)

| Secret | Value | Purpose |
|--------|-------|---------|
| `REDIS_URL` | `redis://localhost:6379` | Velocity state |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Decision event publishing |
| `AUTH0_DOMAIN` | `<your-tenant>.auth0.com` | Token validation |
| `AUTH0_AUDIENCE` | `https://fraud-rule-engine-api` | Auth0 audience |
| `AUTH0_CLIENT_ID` | (from Auth0 bootstrap) | M2M client ID |
| `AUTH0_CLIENT_SECRET` | (from Auth0 bootstrap) | M2M client secret |
| `AUTH0_MGMT_DOMAIN` | `<your-tenant>.auth0.com` | Auth0 Management API |
| `AUTH0_MGMT_CLIENT_ID` | (from project admin) | Management API client ID |
| `AUTH0_MGMT_CLIENT_SECRET` | (from project admin) | Management API client secret |
| `S3_ENDPOINT_URL` | `http://localhost:9000` | MinIO endpoint |
| `S3_ACCESS_KEY_ID` | `minioadmin` | MinIO access key |
| `S3_SECRET_ACCESS_KEY` | `minioadmin` | MinIO secret key |
| `S3_BUCKET_NAME` | `fraud-gov-artifacts` | Ruleset artifacts |
| `S3_REGION` | `us-east-1` | MinIO region |

### Test Config (TBD - Redis Cloud Future)

| Secret | Value | Purpose |
|--------|-------|---------|
| `REDIS_URL` | `redis://:@redis-cloud-xxx.cache.amazonaws.com:6379` | Velocity state |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka.test.com:9092` | Decision events |
| `AUTH0_DOMAIN` | `your-tenant.auth0.com` | Token validation |
| `AUTH0_AUDIENCE` | `https://fraud-rule-engine-api` | Auth0 audience |

### Prod Config (TBD - Redis Cloud Future)

| Secret | Value | Purpose |
|--------|-------|---------|
| `REDIS_URL` | `redis://:@redis-prod-xxx.cache.amazonaws.com:6379` | Velocity state |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka.prod.com:9092` | Decision events |
| `AUTH0_DOMAIN` | `your-tenant.auth0.com` | Token validation |
| `AUTH0_AUDIENCE` | `https://fraud-rule-engine-api` | Auth0 audience |

## First-Time Setup

### Step 1: Run Auth0 Bootstrap

The Auth0 bootstrap script creates the API, M2M client, and syncs credentials to Doppler:

```powershell
cd C:\Users\kanna\github\card-fraud-rule-engine
uv run auth0-bootstrap --yes --verbose
```

This creates:
- Resource Server: `https://fraud-rule-engine-api`
- M2M Application: `Fraud Rule Engine M2M`
- Scopes: `execute:rules`, `read:results`, `replay:transactions`, `read:metrics`

### Step 2: Verify Doppler Secrets

```powershell
# Verify all required secrets are set
uv run doppler-secrets-verify

# Or check manually
doppler secrets --project=card-fraud-rule-engine --config=local

# Required secrets checklist:
# - REDIS_URL
# - KAFKA_BOOTSTRAP_SERVERS
# - AUTH0_DOMAIN
# - AUTH0_AUDIENCE
# - AUTH0_CLIENT_ID (set by bootstrap)
# - AUTH0_CLIENT_SECRET (set by bootstrap)
# - AUTH0_MGMT_DOMAIN
# - AUTH0_MGMT_CLIENT_ID
# - AUTH0_MGMT_CLIENT_SECRET
# - S3_ENDPOINT_URL
# - S3_ACCESS_KEY_ID
# - S3_SECRET_ACCESS_KEY
# - S3_BUCKET_NAME
# - S3_REGION
```

```powershell
# Check local config
doppler secrets --project=card-fraud-rule-engine --config=local

# Verify specific secrets
doppler secrets --project=card-fraud-rule-engine --config=local get REDIS_URL
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_CLIENT_ID
```

## Quick Setup Commands

```powershell
# Start local infrastructure (Redis + Redpanda)
uv run infra-local-up

# Verify Redis
uv run redis-local-verify

# Run dev server with Doppler secrets
uv run doppler-local

# Run tests with Doppler secrets (local Redis)
uv run doppler-local-test

# Stop local infrastructure
uv run infra-local-down
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    card-fraud-rule-engine                    │
├─────────────────────────────────────────────────────────────┤
│  Redis (Velocity State)                                     │
│  - Atomic transaction counting per dimension                │
│  - Key format: vel:{ruleset_key}:{rule_id}:{dimension}:...  │
├─────────────────────────────────────────────────────────────┤
│  Redpanda (Kafka-Compatible Messaging)                      │
│  - Decision event publishing to transaction-management      │
│  - Topic: fraud.card.decisions.v1                           │
├─────────────────────────────────────────────────────────────┤
│  MinIO (S3-Compatible Storage)                              │
│  - Reads compiled rulesets from fraud-gov-artifacts bucket  │
│  - Ruleset key: rulesets/{env}/{ruleset_key}/v{version}/... │
├─────────────────────────────────────────────────────────────┤
│  Auth0 (JWT Validation)                                     │
│  - Validates M2M tokens with scope-based authorization      │
│  - Required scopes: execute:rules, read:results, etc.       │
└─────────────────────────────────────────────────────────────┘
```

## MinIO Usage (Shared)

**Important:** MinIO is managed by `card-fraud-rule-management` project.

This project:
- ✅ Reads rulesets from `fraud-gov-artifacts` bucket
- ❌ Does NOT write to MinIO (publishing done by rule-management)

If MinIO is not running:
```powershell
cd C:\Users\kanna\github\card-fraud-rule-management
uv run objstore-local-up
```

## Troubleshooting

### Issue: "doppler: command not found"

**Cause:** Doppler CLI not installed or not in PATH

**Fix:**
```powershell
# Verify installation
doppler --version

# If not found, reinstall
winget install doppler.cli

# Or add to PATH manually (Windows)
# Check: C:\Users\<username>\AppData\Local\Programs
```

### Issue: "Redis connection refused"

**Cause:** Redis not running or wrong connection string

**Fix:**
```powershell
# For local: start Redis
uv run infra-local-up

# Verify REDIS_URL
doppler secrets --project=card-fraud-rule-engine --config=local get REDIS_URL

# Test Redis connection
uv run redis-local-verify
```

### Issue: "Doppler: No such file or directory"

**Cause:** Not configured for this project

**Fix:**
```powershell
# Configure Doppler for this project
doppler setup --project=card-fraud-rule-engine --config=local

# Or use full command with project/config flags
doppler secrets --project=card-fraud-rule-engine --config=local
```

### Issue: Auth0 bootstrap fails

**Cause:** Missing Management API credentials

**Fix:** Ensure these are set in Doppler local config:
```powershell
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_MGMT_DOMAIN
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_MGMT_CLIENT_ID
doppler secrets --project=card-fraud-rule-engine --config=local get AUTH0_MGMT_CLIENT_SECRET
```

If these are missing, contact a project admin to get the Management API credentials.

### Issue: MinIO bucket not found

**Cause:** MinIO not running or wrong bucket name

**Fix:**
```powershell
# Start MinIO from rule-management
cd ../card-fraud-rule-management
uv run objstore-local-up

# Verify bucket exists
uv run objstore-local-verify
```

### Issue: "Invalid token" or "Unauthorized"

**Cause:** Doppler token expired or invalid

**Fix:**
```powershell
# Logout and login again
doppler logout
doppler login
```

### Issue: Secrets not syncing to application

**Cause:** Doppler not running with application

**Fix:**
```powershell
# Always use Doppler wrapper scripts
uv run doppler-local       # NOT mvn quarkus:dev
uv run doppler-local-test  # NOT mvn test
```

### Issue: "No such project" error

**Cause:** You don't have access to the Doppler project

**Fix:**
1. Contact a project admin to invite you to `card-fraud-rule-engine`
2. Wait for invite confirmation email
3. Run `doppler login` again after accepting invite

## Related Documentation

- [AGENTS.md](../../AGENTS.md) - Agent instructions
- [docs/01-setup/redis-setup.md](redis-setup.md) - Local Redis setup
- [docs/README.md](../README.md) - Documentation index
