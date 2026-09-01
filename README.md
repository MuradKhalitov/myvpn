# MyVPN backend

Android-first backend for MyVPN. APK distribution is external to the backend.

Current backend foundation:

- automatic DEVICE account registration with signed JWT and refresh-token rotation;
- optional EMAIL OTP authentication foundation (recovery linking remains future work);
- FREE VPN access, traffic quota and policy reconciliation;
- PREMIUM subscription and direct YooKassa payment foundation;
- 3x-ui/Xray provisioning, retries, fencing and reconciliation.

The Android UI and `GET /api/v1/vpn/access` endpoint are intentionally not implemented yet.

## Local development

Start PostgreSQL with Docker Compose, set the database and security environment variables, then run:

```powershell
.\mvnw.cmd clean verify
```

Production requires direct YooKassa credentials, SMTP settings, JWT keys, device-secret pepper and 3x-ui credentials. Do not store secrets in source control.
