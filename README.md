# MyVPN backend

Android-first backend for MyVPN. APK distribution is external to the backend.

Current backend foundation:

- automatic DEVICE account registration with signed JWT and refresh-token rotation;
- optional EMAIL OTP authentication foundation (recovery linking remains future work);
- FREE VPN access, traffic quota and policy reconciliation;
- PREMIUM subscription and direct YooKassa payment foundation;
- 3x-ui/Xray provisioning, retries, fencing and reconciliation.

The Android MVP client is isolated under `android/`; authenticated clients use `GET /api/v1/vpn/access`.

## Android MVP

Open `android/` in Android Studio with JDK 17 and SDK 35. Set `API_BASE_URL` in `android/app/build.gradle.kts`. Run `gradlew.bat test` or `gradlew.bat assembleDebug`; APK output is `android/app/build/outputs/apk/debug/app-debug.apk`. Device credentials and tokens use Android Keystore. Email recovery, payments, release signing, reinstall recovery, and the native XTLS/libXray engine remain follow-up work.

## Local development

Start PostgreSQL with Docker Compose, set the database and security environment variables, then run:

```powershell
.\mvnw.cmd clean verify
```

Production requires direct YooKassa credentials, SMTP settings, JWT keys, device-secret pepper and 3x-ui credentials. Do not store secrets in source control.
