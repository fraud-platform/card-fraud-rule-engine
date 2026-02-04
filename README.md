# Card Fraud Rule Engine

Stateless Quarkus runtime for card fraud decisioning.

- AUTH flow: first-match evaluation, fail-open default APPROVE
- MONITORING flow: all-match analytics evaluation
- Redis velocity checks, MinIO ruleset loading, Kafka decision publishing

## Quick Start

Prerequisites:
- Java 21
- Maven 3.9+
- Python 3.11+
- `uv`
- Docker
- Doppler CLI

```bash
# 1) Install deps
uv sync

# 2) Bring up local infra (or use card-fraud-platform)
uv run infra-local-up
uv run redis-local-verify

# 3) Start service with Doppler secrets
uv run doppler-local
```

Service URLs:
- OpenAPI: `http://localhost:8081/openapi`
- Swagger UI: `http://localhost:8081/swagger-ui`
- Health: `http://localhost:8081/v1/evaluate/health`

## API Endpoints

Evaluation:
- `POST /v1/evaluate/auth`
- `POST /v1/evaluate/monitoring`
- `GET /v1/evaluate/health`
- `GET /v1/evaluate/rulesets/registry/status`
- `GET /v1/evaluate/rulesets/registry/{country}`
- `POST /v1/evaluate/rulesets/hotswap`
- `POST /v1/evaluate/rulesets/load`
- `POST /v1/evaluate/rulesets/bulk-load`

Management:
- `POST /v1/manage/replay`
- `POST /v1/manage/replay/batch`
- `POST /v1/manage/simulate`
- `GET /v1/manage/metrics`

## Test Commands

```bash
uv run test-unit
uv run test-smoke
uv run test-integration
uv run test-all
uv run test-coverage
```

Verified on 2026-02-03:
- `uv run test-unit`: `485` run, `0` failed, `0` errors, `3` skipped
- `uv run test-integration`: `36` run, `0` failed, `0` errors, `0` skipped

## Security and Secrets

Doppler is mandatory for runtime/test commands.

Use:
- `uv run doppler-local`
- `uv run test-unit`

Do not use:
- `mvn quarkus:dev`
- `mvn test`
- `.env` files in this repository

## Documentation

- Agent instructions: `AGENTS.md`
- Docs index: `docs/README.md`
- Setup guides: `docs/01-setup/`
- ADRs: `docs/07-reference/`
- Runbooks: `docs/06-operations/`
