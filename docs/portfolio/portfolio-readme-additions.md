# Portfolio README Additions - Asombrate

## Suggested highlights section

- Kotlin + Jetpack Compose Android app focused on route-aware sun exposure recommendations.
- Custom seat recommendation engine (car/bus layouts) driven by route geometry and solar position.
- Backend proxy for secure routing integration (no upstream API key in mobile app).
- Dynamic quota/rate limiting with operational states (NORMAL/HIGH_USAGE/DEGRADED/BLOCK).
- Graceful degraded UX and fallback behavior when upstream is constrained.
- Privacy-first approach: no account model, no ad tracking, minimal data persistence.

## Suggested architecture bullet points

- Client: Compose UI + ViewModel state orchestration + in-memory TTL caches.
- Backend: ORS gateway with abuse protection, cache, usage thresholds and structured operational responses.
- Contract alignment: client consumes X-Usage-State to adapt UX and messaging.

## Suggested quality and release bullet points

- Unit tests for network error classification and state-contract edge cases.
- Minimal CI gate running assembleDebug + testDebugUnitTest on push/PR.
- Open Testing documentation package (privacy audit, policy draft, Data Safety draft, release checklist).

## CV / interview framing

Use concise impact-oriented phrasing:

- Designed and implemented a privacy-first Android mobility assistant that computes sun-aware seat recommendations from live routes.
- Built a backend quota-protection layer (dynamic throttling + degradation strategy) to keep service reliable under constrained API budgets.
- Aligned mobile UX with backend operational states to provide transparent, user-friendly behavior during high usage and quota events.
- Prepared production-style release artifacts for Play Open Testing, including CI quality gates and Data Safety documentation.
