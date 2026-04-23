# Privacy Audit - Open Testing (Asombrate)

Date: 2026-04-21
Scope: Android app + backend ORS proxy used by the app.

## 1) Data the app uses

- Origin and destination entered by the user (text or map pin).
- Optional current device location only when the user taps "usar mi ubicacion actual".
- Departure time selected by the user.
- Vehicle type selected by the user (car or bus).
- Route/geocode responses required to compute seat-side recommendation.

Use purpose: app functionality only (route calculation + sun/shade recommendation).

## 2) Data the app does NOT collect

- No account data (no name, email, phone, password).
- No payment data.
- No contacts, photos, files, messages, or microphone/camera data.
- No advertising identifiers usage for profiling.
- No analytics SDKs or behavior tracking SDKs in current dependencies.

## 3) Account and tracking posture

- No login, no user account, no registration flow.
- No invasive behavior tracking.
- No ad-tech telemetry.

## 4) Location retention and persistence

- No persistent location history database in the app.
- Route/geocode caches are in-memory TTL only and are lost when the app process ends.
- Small UI state persistence is limited to selected vehicle type via SavedStateHandle.
- Android backup is disabled (allowBackup=false) to reduce unintended persistence risk.

## 5) Technical logging and PII

- Android app does not use Log/Timber analytics logging pipelines for user tracking.
- Debug info used for UI state has been minimized to avoid raw coordinates.
- Backend operational logs are structured and focused on quota/counter status.
- Backend keeps in-memory IP rate-limit counters for abuse protection only; no long-term user profile storage is implemented in this repo.

## 6) Third-party services usage

- Route/geocode are processed through the project backend proxy, which calls OpenRouteService.
- Map tiles/search functionality rely on map/routing providers only for core app function.
- No third-party marketing/ads SDK integration found.

## 7) Consistency checks and minimal fixes applied

- Privacy hardening applied:
  - AndroidManifest switched to allowBackup=false.
  - ShadowViewModel debug text no longer includes exact origin/destination coordinates.

## 8) Open Testing readiness conclusion

Current implementation is consistent with a privacy-by-design posture for Open Testing:

- Minimal data usage for core functionality.
- No account system.
- No invasive tracking.
- No unnecessary persistent location history.
