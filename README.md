# MyVPN

Telegram bot for managing VPN subscriptions. The local profile receives
Telegram updates through long polling and currently issues test access through
`FakeVpnProvider`.

## Requirements

- Java 17
- Docker with Docker Compose

## Start PostgreSQL

```powershell
docker compose up -d postgres
```

## Run the application

Activate the `local` Spring profile. It uses the same local-only database
credentials as Docker Compose. Set the BotFather token and the comma-separated
Telegram IDs allowed to run administrative commands only in the current shell:

```powershell
$env:TELEGRAM_BOT_TOKEN = "<bot-token>"
$env:TELEGRAM_ADMIN_IDS = "123456789"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Do not commit a real bot token.

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
