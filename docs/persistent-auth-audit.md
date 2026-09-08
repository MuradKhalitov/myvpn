# Persistent device authentication audit

## Lifecycle

CallCheck creates a PostgreSQL AuthSession for the account and returns access/refresh credentials.
Android encrypts both tokens with Android Keystore and writes the credential set in one
DataStore edit. The repository publishes the in-memory session only after that edit succeeds.
The application-scoped PhoneAuthRepository coordinates restore, exchange and refresh with a Mutex.
There is no persisted authorized boolean.

The configured default access TTL remains 15 minutes (`AUTH_JWT_ACCESS_TTL`, unchanged).
The device session has no time-based expiry. Legacy `expires_at` is nullable and ignored;
rotation clears it. Explicit revocation is stored in `revoked_at`. Account deactivation also
produces SESSION_REVOKED under the existing backend security policy.

## Causes found and changes

- REFRESH_TOKEN_INVALID used to clear encrypted credentials. It now reconciles with durable
  storage, retries at most once with a different saved credential, or raises SessionRecoveryException.
  User Retry attempts restore/refresh again; it never automatically discards INVALID credentials.
- REFRESH_TOKEN_REVOKED was previously treated as final. This backend emits SESSION_REVOKED,
  not REFRESH_TOKEN_REVOKED; the ambiguous legacy code is now treated as recoverable INVALID.
- Empty access with a future expiration previously passed accessTokenIsValid and subsequently
  raised SessionExpiredException. Presence of nonblank access is now required for access validity.
- Exchange/refresh published memory before successful persistence. Publication now follows save.
- Startup and refresh now share the same Mutex, preventing restore from publishing a stale read
  while another operation clears or replaces credentials.
- Generic Retry previously selected PhoneEntry from a null in-memory session. It now restores
  persisted credentials; null memory is not evidence of missing device credentials.
- Protected 403 now follows the same coordinated refresh and single retry as 401.
  Neither the original nor the retried protected request clears credentials.
- General Keystore errors previously meant corruption. Actual malformed ciphertext, invalidated
  keys and authentication-tag failures are final local corruption; provider availability failures
  preserve credentials. Ciphertext lengths are validated before allocating the IV.
- A revoked previous token used to fail the grace check as INVALID. Both current and previous
  now require a non-revoked session before any rotation or recovery.
- Previous-token recovery used to expire after two minutes, stranding a device after a lost
  response and delayed retry. Recovery is now bounded by generation only (see below).
- Safe event logs identify startup, validity, refresh, recovery, invalid, revoke and clear reason;
  they do not include credential values or exception payloads.

## All production local-clear and PhoneEntry paths

| Location / trigger | HTTP/domain input | DataStore clear | Allowed now |
| --- | --- | --- | --- |
| Api.kt refresh | refresh 401/403 + SESSION_REVOKED | Yes, REVOKED_SESSION | Yes: explicit server confirmation |
| Api.kt loadPersistedSession | unreadable or blank local refresh | Yes, CORRUPTED_LOCAL_CREDENTIAL | Yes: local credential unusable |
| DeviceIdentityStore.clear | called only by the two paths above | Removes access, refresh and session metadata atomically | Yes; legacy VPN/device identity keys retained |
| MainViewModel.restoreAuth | repository returns null: missing/corrupt/revoked | Only repository paths above | Yes |
| MainViewModel.loadVpn | SessionExpiredException from repository: missing/corrupt/revoked | Only repository paths above | Yes; access expiration and blank access no longer produce this exception with usable refresh |
| MainViewModel.startPhoneVerification | invalid phone input before verification | No | Yes: unauthenticated phone input validation |
| MainViewModel.changePhoneNumber | change number from verification screen | No | Yes: pre-auth navigation, not logout |
| MainViewModel.retry, PhoneEntry branch | already at PhoneEntry | No | Yes: no auth transition |
| MainViewModel.retry, VERIFICATION_EXPIRED/FAILED | incomplete first phone verification | No | Yes: no established device session |
| MainActivity PhoneEntry renderer | renders the state above | No | No independent transition |

No other production clearAuth, clearSession, clearIdentity or refresh-removal paths were found.
The old INVALID clear branch was removed. No logout UI/flow/endpoint was added. The pre-existing
backend logout endpoint and admin-compatible service were not changed by this task.

## Failure behavior

| Scenario | Result |
| --- | --- |
| Valid access | Load protected VPN state |
| Expired or missing access, refresh present | Silent refresh, atomic save, load VPN state |
| Network/DNS/connect failure or timeout | Preserve credentials, retryable error |
| HTTP 408/429/500/502/503/504 | Preserve credentials, retryable error |
| Retry after service recovery | Restore DataStore, refresh if needed, reload VPN |
| Protected 401/403 | Coordinated refresh; retry original request once |
| Repeated protected 401/403 | Retryable error; preserve credentials |
| REFRESH_TOKEN_INVALID / legacy REFRESH_TOKEN_REVOKED | Reconcile; session recovery error with Retry if unsuccessful |
| SESSION_REVOKED from refresh | Clear local credentials, PhoneEntry |
| Lost refresh response A to B | Retry A at any time recovers exactly B until B is used for refresh; counter is unchanged |
| Concurrent refresh of A | PostgreSQL pessimistic locks; one rotation, same resulting refresh |
| Backend restart/recreate | Restore PostgreSQL session; requires the same database and refresh pepper |
| Process death after save | Recreated repository restores the atomic durable credential set |
| Clear Data/reinstall | Missing credential, CallCheck; existing account retains original trial |

## Persistence and generation-based recovery

Migration 009 already drops expires_at NOT NULL and adds previous-token recovery columns;
rotation_counter and revoked_at already exist. No new migration is needed.
Compose stores PostgreSQL data in a named volume. No refresh rotation state is held exclusively
in backend memory. The refresh pepper must remain stable across instances and deployments.

After A rotates to B, the database stores hash(B), previous hash(A), and the incremented counter.
A retry of A derives the same B using HMAC(pepper, sessionId, counter), without another rotation.
Retries after 10 seconds, 10 minutes, a day, a month, or longer behave identically. Recovery
does not depend on access expiry, session age, or the legacy previous_refresh_valid_until value.

When B is used for refresh, B rotates to C, the previous hash becomes hash(B), and A becomes
INVALID. Exactly two generations can be usable: current and its immediate predecessor (one
before the first rotation). Using the access JWT does not consume the previous refresh generation.
Explicit revoke blocks both stored generations immediately. A credential older than the stored
previous generation is unknown/INVALID, not evidence of session revocation.

The nullable previous_refresh_valid_until column is retained for schema compatibility, ignored
for recovery, and set to null on rotation. The obsolete recovery-grace configuration and its
validation have been removed. No replacement timeout was introduced.

AUTH_REFRESH_PEPPER is supplied externally, required by production configuration and startup
validation, and never randomly generated at startup. compose.server.yaml now explicitly passes
the required variable into the app container. Preserve the same external secret across restart,
recreate, deploy and instances. Actual deployed secret equality was not inspected. The checked-in
server Compose also does not supply the other required production auth settings; those existing
settings must be provided by the deployment configuration, outside this protocol change.

Tests cover backend HTTP rotation/recovery/revoke, PostgreSQL concurrency across independent
service instances, old sessions with legacy expired timestamps, and service reconstruction with
fresh HMAC instances. Android tests cover repository and UI behavior with test doubles, including
process/repository recreation and a month-delayed lost-response Retry reaching Ready without any
PhoneEntry state. PostgreSQL tests exercise two concurrent generations, recovery after up to
100 years, legacy expired recovery deadlines, and revocation of both stored generations.
These simulations are not a physical-device Keystore/process-kill
test or an actual deployment; no deploy was performed.

## Verification results (2026-09-08)

- Java 17: `./mvnw.cmd clean verify`: PASS, 425 tests, zero failures/errors/skips.
- Android: `./gradlew.bat --no-daemon testDebugUnitTest`: PASS, 85 tests,
  zero failures/errors/skips (final test sources).
- Android: `./gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleRelease`:
  PASS. All three tasks ran together with the final Android test sources.
- Server Compose: `config --no-interpolate --format json` parsed successfully; the required
  AUTH_REFRESH_PEPPER forwarding was asserted without reading or printing a secret value.
- `git diff --check`: PASS.
- Generated build outputs were not staged. The pre-existing tracked Gradle HTML report was restored.
- No commit, push or deploy was performed.

Changed files:

- android/app/src/main/java/com/myvpn/android/data/Api.kt
- android/app/src/main/java/com/myvpn/android/data/DeviceIdentityStore.kt
- android/app/src/main/java/com/myvpn/android/data/Models.kt
- android/app/src/main/java/com/myvpn/android/ui/MainViewModel.kt
- android/app/src/test/java/com/myvpn/android/data/PhoneAuthRepositoryTest.kt
- android/app/src/test/java/com/myvpn/android/ui/MainViewModelTest.kt
- src/main/java/ru/murad/myvpn/application/auth/RefreshTokenService.java
- src/main/java/ru/murad/myvpn/model/AuthSession.java
- src/main/java/ru/murad/myvpn/config/AuthProperties.java
- src/main/java/ru/murad/myvpn/config/AuthConfiguration.java
- src/main/resources/application.yml
- compose.server.yaml
- src/test/java/ru/murad/myvpn/application/auth/RefreshTokenServiceTest.java
- src/test/java/ru/murad/myvpn/application/auth/AuthSecretRepresentationTest.java
- src/test/java/ru/murad/myvpn/application/auth/JwtTokenServiceTest.java
- src/test/java/ru/murad/myvpn/application/auth/DeviceRegistrationServiceTest.java
- src/test/java/ru/murad/myvpn/controller/AuthControllerIntegrationTest.java
- src/test/java/ru/murad/myvpn/application/auth/PersistentDeviceSessionIntegrationTest.java (new)
- docs/persistent-auth-audit.md (new)

The former two-minute protocol limitation is removed. Physical-device end-to-end testing against
a deployed backend remains outside the automated tests described here; no deploy was performed.
PR READY for the generation-based recovery change; deployed configuration and physical-device
testing are not claimed by this readiness assessment.
