# Privacy Audit - Open Testing (Asombrate)

Date: 2026-04-25
Scope: Android app + backend ORS proxy used by the app.

## 1) Data the app uses

- Origin and destination entered by the user.
- Optional current location when the user taps "usar mi ubicacion actual".
- Departure time selected by the user.
- Vehicle type selected by the user.
- Route/geocode responses required to compute the recommendation.

## 2) Data the app does NOT collect

- No accounts, email, phone or password.
- No payment data.
- No contacts, photos, files, camera or microphone data.
- No advertising identifiers for profiling.
- No analytics SDKs in current dependencies.

## 3) Retention posture

- No persistent location history database.
- App caches are in-memory TTL only.
- Android backup and data extraction are disabled.

## 4) Third-party services

- Project backend proxy.
- OpenRouteService via backend.
- Map tiles via osmdroid/OpenStreetMap infrastructure.

## 5) Hardening now present

- Release blocks cleartext traffic.
- Release rejects placeholder or localhost backend URLs.
- Health endpoint does not expose secrets.
- Backend adds request IDs and security headers.

## 6) Conclusion

Current implementation is aligned with privacy-by-design expectations for Open Testing and a first production rollout.
