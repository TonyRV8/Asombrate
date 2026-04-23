# Play Console Data Safety Draft - Asombrate

Date: 2026-04-21

Important: This is a technical draft. Final legal answers in Play Console must be reviewed before submission.

## A) Recommended high-level answers

- Data collected: Yes, limited data required for app functionality.
- Data shared/sold for advertising: No.
- Data used for app functionality: Yes.
- Data encrypted in transit: Yes (ensure release BACKEND_BASE_URL uses HTTPS).
- Account creation required: No.

## B) Data categories checklist

| Data category                       | Collect?                                  | Why                              | Linked to user identity?  | Required?                 |
| ----------------------------------- | ----------------------------------------- | -------------------------------- | ------------------------- | ------------------------- |
| Precise location                    | Yes (when user uses map/current location) | Route + recommendation           | No account linkage in app | Optional (feature-driven) |
| Approximate location                | Yes (coarse permission path)              | Route + recommendation           | No account linkage in app | Optional                  |
| Personal info (name/email/phone)    | No                                        | Not used                         | N/A                       | N/A                       |
| Financial info                      | No                                        | Not used                         | N/A                       | N/A                       |
| Contacts/photos/files/messages      | No                                        | Not used                         | N/A                       | N/A                       |
| App activity analytics              | No                                        | No analytics SDK in dependencies | N/A                       | N/A                       |
| Crash diagnostics telemetry SDK     | No dedicated SDK                          | Not configured in current repo   | N/A                       | N/A                       |
| Device or other IDs for ads/profile | No                                        | Not used                         | N/A                       | N/A                       |

## C) Technical justifications (quick copy)

1. Location is used only to compute route and sun/shade recommendation.
2. No login/account system exists, so data is not tied to persistent user profiles.
3. No ad SDKs or analytics trackers are present in app dependencies.
4. The app uses backend proxy + routing/map providers only for core functionality.
5. The app does not store persistent location history in local database.

## D) Final verification before Play submission

- Confirm release BACKEND_BASE_URL is HTTPS.
- Confirm no secrets in app binary/resources.
- Confirm policy text and Data Safety answers are aligned with docs/play-store/privacy-audit-open-testing.md and docs/play-store/privacy-policy-draft.md.
