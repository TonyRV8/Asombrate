# Technical Decisions - Asombrate

## 1) Problem

Users need a practical recommendation about where to sit (car/bus) to reduce direct sun exposure during a real trip.

## 2) Solution

Asombrate combines route geometry + solar position + seat layout to recommend the best seat side and alternatives.

## 3) Architecture choice

- Android app (Kotlin + Compose) for user interaction and result visualization.
- Backend ORS proxy for API-key protection, quota/rate policy, cache and abuse controls.
- ViewModel-centered state orchestration on client side.

Why this architecture:

- Keeps secret management out of mobile binary.
- Adds operational control layer (quota/rate/cache) before upstream routing service.
- Preserves simple client UX while reducing operational risk.

## 4) Main trade-offs

- Added backend complexity vs. better security and quota control.
- In-memory cache simplicity vs. no long-term cache persistence.
- Strong privacy posture vs. reduced observability telemetry.

## 5) ORS quota protection strategy

- Dynamic rate limiting based on daily and per-minute safe budgets.
- Operational thresholds mapped to usage states: NORMAL, HIGH_USAGE, DEGRADED, BLOCK.
- Cache-first behavior in degraded mode when possible.
- Block mode returns controlled responses instead of exhausting upstream quota.

## 6) Privacy by design decisions

- No login/account model.
- No ad-tech trackers.
- No persistent location history store.
- Backend-side secret management.
- Minimal technical logging and removal of raw coordinate details from app debug text.

## 7) Open Testing readiness

Project includes:

- Contract-aligned backend/app service states.
- Unit tests + minimal CI gate.
- Privacy audit + policy draft + Play Data Safety draft.
- Actionable release checklist.
