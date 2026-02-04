# Code Map

## Repository Purpose

Quarkus runtime for fraud decision evaluation and compiled ruleset execution.

## Documentation Layout

- `01-setup/`: Setup
- `02-development/`: Development
- `03-api/`: API
- `04-testing/`: Testing
- `05-deployment/`: Deployment
- `06-operations/`: Operations
- `07-reference/`: Reference

## Local Commands

- `uv sync`
- `uv run doppler-local`
- `uv run test-unit`

## Platform Modes

- Standalone mode: run this repository with its own local commands and Doppler config.
- Consolidated mode: run via `card-fraud-platform` for cross-service local validation.
