# JSON Schemas

This folder contains planning-phase JSON Schema artifacts for:
- request body validation (`evaluate-request.json`)
- response envelopes (`AUTH-response.json`, `MONITORING-response.json`)
- compiled ruleset artifact (`compiled-ruleset-v1.json`)

Notes:
- Schemas are intended to be *contract documents*; implementation can enforce them strictly or minimally depending on endpoint semantics.
- Schemas assume `/v1/...` endpoints and the in-band degradation model (`engine_mode`, `engine_error_code`).

Contract alignment:
- The request schema intentionally validates only the minimal required envelope fields (`transaction_id`, `occurred_at`).
- `additionalProperties: true` is deliberate to preserve the “opaque payload” philosophy.
