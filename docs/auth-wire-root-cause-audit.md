# Persistent auth: HTTP contract root-cause audit

## What is confirmed, and what is not

Confirmed in the repository and reproduced over HTTP with the actual Retrofit converter:
the backend refresh response has four fields (accessToken, refreshToken, tokenType, expiresIn),
but Android used AuthResponse, which required an additional accountId. A successful 200 response
therefore raised MissingFieldException before the repository could persist the returned tokens.
No real token values are included in this report or the new diagnostics.

HONOR was initially unavailable, then became available later in the audit. The installed release
was 1.0.8 (versionCode 9), not debuggable. Before updating, its auth logs showed readable expired
access and REFRESH_ATTEMPT without REFRESH_SUCCESS; the old APK did not log HTTP status/parsing
failure. Thus the pre-fix physical HTTP response was not captured directly.

With explicit user approval, the signed release APK was updated using adb install -r. Signer
certificates were verified equal beforehand, and the installed APK was confirmed different from
the rebuilt APK. No uninstall, Clear Data, token extraction or backend deployment occurred.
The preserved installation then completed refresh HTTP 200, atomic persistence and VPN HTTP 200
to Ready. A subsequent force-stop/start also restored Ready from the newly persisted credentials.
Raw DataStore values and backend logs were not read; logical credential state was observed through
the new safe in-app diagnostics. CURRENT versus PREVIOUS matching cannot be distinguished from
the successful response alone and was not claimed for this production request.

## Exact reproduced failure path before the fix

1. MyVpnApplication supplies the shared PhoneAuthRepository and DeviceIdentityStore.
2. MainActivity constructs MainViewModel; init invokes restoreAuth.
3. DataStore returns an expired access credential and a persisted refresh A.
4. restoreSession acquires its Mutex and invokes POST /api/v1/auth/refresh with A.
5. Backend finds current A, rotates to B and commits; AuthController returns AuthTokens, HTTP 200.
6. The response JSON has no accountId. Retrofit's kotlinx.serialization converter throws
   MissingFieldException while decoding the Android AuthResponse.
7. The repository never receives the response object and never executes store.save(B).
   It catches HttpException and IOException, but this serialization error is neither.
8. MainViewModel.restoreAuth catches Throwable and calls errorFor with SESSION_EXPIRED fallback.
   The generic branch renders the service-unavailable text. loadVpn is not reached.
9. ErrorScreen's button directly invokes vm.retry. SESSION_EXPIRED routes to restoreAuth.
   A new coroutine and new HTTP request are created; the Mutex is released on the failed attempt.
10. The backend now finds previous A and returns the same B, again with HTTP 200 and no accountId.
    Parsing fails again. The unchanged logical credential set remains A plus expired access.
11. Previously no Initializing state was set on Retry. The new Error equalled the old Error,
    so StateFlow suppressed an equal emission. The UI appeared motionless despite a new request.

There is no cached Deferred, refreshInProgress, isLoading or equivalent guard in this restore
path. The phone-exchange flags belong to a different pre-auth path. MainActivity's manual
remember-based ViewModel lifetime is a separate concern, not evidence for the reproduced error.

## Why previous tests missed it

PhoneAuthRepositoryTest.FakeApi returned an already constructed AuthResponse including accountId.
Those tests bypassed HTTP response parsing. Backend HTTP tests independently accepted the correct
four-field AuthTokens response. Rotation/recovery changes could not repair this DTO mismatch.

The new RefreshWireContractTest was first run against the old production DTO and failed with
MissingFieldException naming accountId. It passes after the DTO correction. Its response fixture
is also compared with actual backend AuthTokens JSON serialization in AuthTokensWireContractTest;
AuthControllerIntegrationTest asserts the real endpoint's four-field response shape.

## Minimal correction and diagnostics

- MyVpnApi.refresh now returns RefreshResponse with the backend's four fields. Registration and
  phone-exchange response DTOs are unchanged. Account metadata comes from the persisted session.
- Credentials are still saved atomically before publishing memory. No credentials are cleared
  for INVALID, network, 5xx or parsing errors. SESSION_REVOKED remains terminal.
- Retry displays Initializing and starts a new attempt. Parsing errors have a dedicated
  RESPONSE_FORMAT category and user-facing retryable message instead of masquerading as 5xx.
- An OkHttp interceptor logs the HTTP status before Retrofit parses JSON. It reads neither
  response bodies nor Authorization headers and logs only the two fixed endpoint categories.
- Android logs AUTH_RETRY_CLICKED, AUTH_RESTORE_STARTED/FINISHED (attempt counter), lock wait/acquire,
  credential/access presence and expiry flags, REFRESH_REQUEST_STARTED, REFRESH_HTTP_STATUS,
  REFRESH_RESULT, VPN_LOAD_STARTED/FINISHED and UI_STATE_CHANGED. Error codes are allowlisted.
- Backend logs CURRENT/PREVIOUS/NONE match, technical session UUID, revocation state, result and
  rotation counter. Controller logs distinguish transaction completion from service-level rotation
  and classify DB/transaction/JWT/domain/internal failures without exception messages or payloads.
- Internal DB/HMAC/JWT/transaction failures are not mapped to INVALID by RefreshTokenService;
  they propagate to framework error handling. Production occurrence/status needs runtime evidence.

No auth protocol, JWT TTL, pepper, CallCheck, trial, VPN, payment or release-script behavior changes.
No Liquibase migration is required. The pre-existing versionCode/versionName worktree changes
were preserved; this task only adds a test dependency to that Gradle file.

## Architecture comparison

| Variant | Benefit | Cost / failure modes | Decision |
| --- | --- | --- | --- |
| A: 15-minute access + rotation + previous recovery | Existing clients/data stay compatible; old generations are invalidated | Rotation, persistence coordination, recovery and pepper compatibility must work | Keep for this fix: the demonstrated bug is a small wire-contract mismatch |
| B: 15-minute access + stable random opaque device credential | Fewer states; lost responses and concurrent refreshes cannot desynchronise generations | A stolen device credential remains usable until revoked; needs careful compatibility for existing rotating credentials | Reasonable future simplification for MyVPN, but does not fix JSON parsing, DB/pepper or network failures |
| C: 30-day access + stable fallback | Fewer refresh calls | Longer exposure of a stolen access JWT; delays discovery of a broken refresh path | Not recommended as a remedy |
| D: permanent access JWT | Removes access-expiry refresh | Stolen JWT remains usable indefinitely unless APIs check revocation; signing-key/session management becomes harder | Not recommended; not implemented |

Recommendation: repair the confirmed contract defect, validate on HONOR, then evaluate B separately
if simplifying the protocol is still desired. Switching protocols now would require compatibility
work without removing the demonstrated cause by itself.

## Physical validation without losing the installation

1. Attach HONOR, allow USB debugging, verify the installed package version. Do not uninstall or
   Clear Data. An update must use the same package and signing identity to retain the installation.
2. Capture only the auth tag: `adb logcat -v time MyVpnAuth:I '*:S'`. Do not collect token-bearing
   network-body/header dumps. Do not publish unfiltered app/backend logs.
3. Open with expired access: expect credentialPresent=true, REFRESH HTTP 200, parsed/persisted
   SUCCESS, VPN access HTTP result and Ready. Local tokens must never appear in the trace.
4. Repeat after process recreation. Verify account metadata survives refresh.
5. For a naturally occurring failure, click Retry and verify a new attempt number/request and
   the safe category. A 200 followed by RESPONSE_FORMAT is distinct from a real BACKEND_5XX.
6. Correlate backend match/result with timestamps/session UUID. Confirm the deployed pepper is
   unchanged before deployment; this audit did not resolve the earlier runtime-source uncertainty.

Readiness: PR READY for this contract defect. The preserved physical installation successfully
refreshed and reached Ready after the app-only fix. Physical offline-to-Retry testing was not
performed; real HTTP retry behavior is covered by the new automated tests. Backend pepper/deploy
compatibility remains the separate operational question from the earlier audit.

## Observed HONOR result

After the in-place app update (no backend changes):

```text
DEVICE_CREDENTIAL_PRESENT value=true
ACCESS_PRESENT value=true
ACCESS_EXPIRED value=true expirationPresent=true
REFRESH_REQUEST_STARTED
REFRESH_HTTP_STATUS status=200
REFRESH_RESULT result=SUCCESS persisted=true
AUTH_RESTORE_FINISHED attempt=1 result=SUCCESS
VPN_LOAD_STARTED
VPN_ACCESS_HTTP_STATUS status=200
VPN_LOAD_FINISHED result=SUCCESS
UI_STATE_CHANGED state=Ready
```

After force-stop/start: credential present, ACCESS_VALID, no refresh needed, VPN access HTTP 200,
Ready again. No PhoneEntry or session clear appeared in either observed process trace.

## Final verification

- Before the production fix: RefreshWireContractTest failed on a real HTTP 200 response with
  MissingFieldException for accountId, reproducing the contract defect.
- Java 17 `./mvnw.cmd clean verify`: PASS, 426 tests, no failures/errors/skips.
- `./gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleRelease`: PASS,
  91 Android tests, no failures/errors/skips; both APK variants built.
- `git diff --check`: PASS.
- Six new Android HTTP tests cover successful no-accountId refresh, missing access,
  500/invalid/malformed response followed by a real Retry request and Ready, and recreation
  consuming the persisted rotated credential. The real ApiFactory is used by these tests.
- No commit, push, backend deploy, app uninstall or Clear Data. The user authorized the
  diagnostic app update on HONOR, and physical startup/process recreation passed afterward.
- The version change in android/app/build.gradle.kts and tracked Gradle HTML report were already
  modified on entry. Version edits were preserved; builds regenerated the report. No artifacts
  were staged, and the generated report must remain outside the source changes for review.

Files changed by this task:

- android/app/build.gradle.kts (MockWebServer test dependency only)
- android/app/src/main/java/com/myvpn/android/data/Models.kt
- android/app/src/main/java/com/myvpn/android/data/Api.kt
- android/app/src/main/java/com/myvpn/android/data/AuthDiagnostics.kt (new)
- android/app/src/main/java/com/myvpn/android/ui/MainViewModel.kt
- android/app/src/test/java/com/myvpn/android/data/PhoneAuthRepositoryTest.kt
- android/app/src/test/java/com/myvpn/android/data/RefreshWireContractTest.kt (new)
- android/app/src/test/resources/refresh-success.json (new, synthetic shared contract fixture)
- src/main/java/ru/murad/myvpn/application/auth/RefreshTokenService.java (logging only)
- src/main/java/ru/murad/myvpn/controller/AuthController.java (logging only)
- src/main/java/ru/murad/myvpn/controller/AuthExceptionHandler.java (logging only)
- src/test/java/ru/murad/myvpn/controller/AuthControllerIntegrationTest.java
- src/test/java/ru/murad/myvpn/application/auth/AuthTokensWireContractTest.java (new)
- docs/auth-wire-root-cause-audit.md (new)
