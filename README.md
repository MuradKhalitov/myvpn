# MyVPN

Infrastructure scaffold for a Telegram bot that will manage VPN subscriptions.
The project currently contains no Telegram, VPN, payment, or domain logic.

## Requirements

- Java 17
- Docker with Docker Compose

## Start PostgreSQL

```powershell
docker compose up -d postgres
```

## Run the application

Activate the `local` Spring profile. It uses the same local-only database
credentials as Docker Compose:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

On Linux or macOS, use `./mvnw` instead of `.\mvnw.cmd`.

## Run tests

Docker must be running because integration tests start PostgreSQL with
Testcontainers:

```powershell
.\mvnw.cmd clean verify
```

## Stop PostgreSQL

```powershell
docker compose down
```

Add `--volumes` only when you intentionally want to remove the local database
data.

The committed `myvpn/myvpn` credentials are intended only for the local
development profile. Non-local environments must provide `DB_URL`,
`DB_USERNAME`, and `DB_PASSWORD` externally.
