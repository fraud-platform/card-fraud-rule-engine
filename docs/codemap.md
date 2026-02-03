# Code Map

## Repository Purpose

Quarkus runtime for fraud decision evaluation and compiled ruleset execution.

## Key Paths

- `src/`: Quarkus Java source (runtime, domain, integration layers).
- `cli/`: Command wrappers for tests and local runtime flows.
- `e2e/`: Black-box end-to-end tests against running service instances.
- `load-testing/`: Service-specific Locust assets and load scripts.
- `sample-rulesets/`: Local sample rulesets used by tests and demos.
- `docs/`: Curated onboarding and operational documentation.

## Local Commands

- `uv sync`
- `uv run doppler-local`
- `uv run test-unit`

## Local Test Commands

- `uv run test-unit`
- `uv run test-integration`

## API Note

Primary API surface is evaluation and health endpoints (for example `/v1/evaluate/*`, `/api/v1/health`).

## Platform Integration

- Standalone mode: run this repository using its own local commands and Doppler project config.
- Consolidated mode: run this repository through `card-fraud-platform` compose stack for cross-service validation.
