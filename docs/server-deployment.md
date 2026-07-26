# MyVPN server deployment

## Requirements

Use one supported Linux VPS with Docker Engine and the Docker Compose plugin, outbound HTTPS access to Telegram and the configured 3x-ui server, and enough persistent disk for PostgreSQL backups. Do not install Docker with an unreviewed `curl | sh` script; use the official Docker documentation for the VPS distribution and verify package signatures according to that distribution's guidance.

The application and PostgreSQL are intentionally not published to the host. The bot uses Telegram long polling, so there is no inbound HTTP requirement. Allow outbound TCP/443 to Telegram and 3x-ui, allow SSH only from trusted administration networks, and do not open ports 5432 or 8080 in the firewall.

## First launch

Clone the reviewed revision, then create the real environment file. It is secret material and must never be committed or copied to chat/logs.

```bash
git clone <repository-url> myvpn
cd myvpn
cp .env.server.example .env.server
chmod 600 .env.server
editor .env.server
docker compose --env-file .env.server -f compose.server.yaml config
docker compose --env-file .env.server -f compose.server.yaml up -d --build
```

Check process state and application logs (the latter are stdout/stderr only):

```bash
docker compose -f compose.server.yaml ps
docker compose -f compose.server.yaml logs -f app
docker compose -f compose.server.yaml exec app curl --fail http://127.0.0.1:8080/actuator/health
```

The health endpoint is only reachable inside the Docker network/container and exposes no actuator endpoints other than health.

Before launch, verify outbound Telegram access without a token:

```bash
curl -I https://api.telegram.org
```

For 3x-ui, make an HTTPS/TLS reachability check to the configured host (for example `curl -I https://<3x-ui-host>/`) without embedding, echoing, or logging credentials. The application uses its configured HTTPS base URL and timeouts.

Never run a local instance and the server instance with the same Telegram bot token: both compete for `getUpdates`. Compose declares exactly one `app` replica, and the application holds a PostgreSQL advisory lock derived from the token; a second instance against the same database fails before it can own long polling.

## Первый staging smoke-test

Staging starts real Telegram long polling and real 3x-ui provisioning, but uses fake payments. Stop every local application instance using the same Telegram bot token before starting staging. This avoids competing `getUpdates` consumers even when the local instance uses another database.

```bash
cp .env.staging.example .env.staging
editor .env.staging

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  config

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  up -d --build
```

Observe only container state and redacted application logs:

```bash
docker compose -f compose.server.yaml ps
docker compose -f compose.server.yaml logs -f app
curl -I https://api.telegram.org
```

Check 3x-ui reachability over HTTPS/TLS without printing a username, password, or session cookie (for example, an unauthenticated `curl -I https://<3x-ui-host>/`). Do not use staging credentials in shell history or logs. Neither PostgreSQL nor the Spring Boot port is published by this Compose configuration.

The staging profile permits `PAYMENT_PROVIDER=FAKE` only with `PAYMENT_ALLOW_FAKE=true`; it always rejects `VPN_PROVIDER_TYPE=FAKE`. Production continues to reject both fake providers.

## Stop, update, and rollback

Stop without deleting PostgreSQL data:

```bash
docker compose -f compose.server.yaml down
```

For an update, back up first, fetch a reviewed Git revision, inspect the diff and Compose config, then run the same `up -d --build` command. To roll back, check out the prior known-good Git commit (or use the previously built/tagged image after changing the Compose image reference), verify `docker compose ... config`, and start it with `up -d`. Keep database migrations backward-compatible; a code rollback after an irreversible migration needs a separately tested database rollback plan.

## PostgreSQL backup and restore

Create a compressed logical backup on the VPS and store it outside the repository with restricted permissions:

```bash
docker compose -f compose.server.yaml exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" | gzip > myvpn-postgres-$(date +%F).sql.gz
```

For restore, stop the application first, create a fresh target database or explicitly approve overwriting the existing one, then restore using `psql` from the PostgreSQL container. Test restores on a non-production database before relying on them. Do not put passwords in command history; use the environment file or an interactive protected prompt.

The production profile rejects `PAYMENT_PROVIDER=FAKE` and `VPN_PROVIDER_TYPE=FAKE`, even if an allow flag is set. A real payment-provider implementation/configuration is required before production activation can succeed.
