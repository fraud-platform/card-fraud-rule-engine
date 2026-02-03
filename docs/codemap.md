# Code Map

## Repository Purpose

Quarkus runtime for fraud decision evaluation and rule artifact consumption.

## Primary Areas

- `app/` or `src/`: service or application implementation.
- `tests/` or `e2e/`: automated validation.
- `scripts/` or `cli/`: local developer tooling.
- `docs/`: curated documentation index and section guides.

## Local Commands

- `uv sync`
- `uv run dev`
- `uv run test-unit`

## Test Commands

- `uv run test-unit`
- `uv run test-integration`

## API Note

Primary API surface is Quarkus endpoints for evaluation and health.

## Deployment Note

Local deployment can run standalone or via platform compose apps profile.
