# Open Testing Release Checklist - Asombrate

Use this as a go/no-go checklist before publishing to Google Play Open Testing.

## 1) Backend and endpoint configuration

- [ ] BACKEND_BASE_URL in local/release config points to production-like HTTPS endpoint.
- [ ] Backend env vars configured (at least ORS_API_KEY, quota/rate/caching values).
- [ ] Backend health check and routing paths respond correctly: /directions, /geocode, /reverse-geocode.

## 2) Security and secrets

- [ ] ORS_API_KEY is only in backend environment, never in client app config/resources.
- [ ] No credentials committed in repository history for release branch.
- [ ] Release endpoint does not expose debug-only internals.

## 3) Build artifacts

- [ ] Unit tests pass: gradlew.bat :app:testDebugUnitTest
- [ ] Debug build passes: gradlew.bat :app:assembleDebug
- [ ] Release bundle generated (if publishing): gradlew.bat :app:bundleRelease
- [ ] Signing and Play App Signing workflow verified for upload.

## 4) Smoke test minimum

- [ ] Run manual smoke checklist in docs/release/smoke-checklist-backend-android.md
- [ ] Confirm service state handling in app UI: NORMAL, HIGH_USAGE, DEGRADED, BLOCK, TEMP_UNAVAILABLE.
- [ ] Confirm fallback behavior with last successful route remains functional.

## 5) Privacy and Play documentation

- [ ] docs/play-store/privacy-audit-open-testing.md reviewed and still accurate.
- [ ] docs/play-store/privacy-policy-draft.md updated with final contact and effective date.
- [ ] docs/play-store/play-console-data-safety-draft.md answers copied/validated in Play Console.

## 6) Store listing assets (manual)

- [ ] App icon, feature graphic, screenshots updated.
- [ ] Short and full descriptions aligned with real functionality.
- [ ] Privacy policy URL/document location ready for Play submission.

## 7) CI gate

- [ ] CI workflow passes on latest commit (assembleDebug + testDebugUnitTest).
- [ ] Any CI red status is blocking for release.

## 8) Go / No-Go decision

- [ ] GO: all mandatory checks above passed.
- [ ] NO-GO: at least one mandatory check failed; release postponed.
