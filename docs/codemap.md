# Code Map

## Core Layout

- `src/main/java/com/fraud/engine/`: Quarkus runtime code.
  - `resource/`: API resources/controllers.
  - `engine/`: rule evaluation pipeline.
  - `ruleset/`: manifest/artifact loading.
  - `security/`: JWT/auth guards.
  - `velocity/`: Redis-backed velocity checks.
- `src/main/resources/`: runtime configuration.
- `src/test/java/`: unit/integration tests.
- `load-testing/`: rule-engine-focused load test scripts.
- `schemas/`: API/domain schema artifacts.

## Key Commands

- `uv run dev`
- `uv run test-unit`
- `uv run test-integration`
- `uv run test-all`

## Integration Role

Consumes compiled artifacts and evaluates transactions in real time.
