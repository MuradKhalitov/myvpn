# MyVPN server deployment

## Requirements

Use a supported Linux VPS with Docker Engine and the Docker Compose plugin, outbound HTTPS access to Telegram and 3x-ui, and persistent disk for PostgreSQL backups. The bot uses long polling; neither Spring Boot port 8080 nor PostgreSQL port 5432 is published to the host. Restrict SSH to trusted administration networks and do not open 8080 or 5432 in the firewall.

## Публикация образа

Build and publish on a local computer or CI, not on the VPS. `scripts/publish-image.ps1` runs from any Windows PowerShell directory, locates the Git root, builds for `linux/amd64`, and derives the immutable image tag from the short Git commit. It never changes `.env.staging`.

### Проверочная сборка без push

```powershell
.\scripts\publish-image.ps1 `
  -DockerHubRepository "dockerhub-user/myvpn" `
  -NoPush
```

This uses `--load`, so the image is available in the local Docker image store and no registry push occurs.

### Публикация immutable image

```powershell
docker login

.\scripts\publish-image.ps1 `
  -DockerHubRepository "dockerhub-user/myvpn"
```

### Публикация immutable image и staging alias

```powershell
.\scripts\publish-image.ps1 `
  -DockerHubRepository "dockerhub-user/myvpn" `
  -PublishStagingAlias
```

Use a Docker Hub access token for `docker login`, preferably with minimum permissions. The commit tag is the primary deployment and rollback tag; mutable `staging` is only an additional alias. Never pass registry credentials or application secrets through build args. `.env.staging`, `.env.server`, and `.env-bot` are excluded from the build context and must not be copied into an image. After publication, manually copy the script's displayed `MYVPN_IMAGE` value into `.env.staging`.

## VPS setup and first staging smoke-test

Create a dedicated non-root deployment user, for example `myvpn-deploy`, with permission to use Docker. Run `docker login` as that user, not as root. A private Docker Hub repository requires `docker login` on the VPS, preferably using a read-only access token when available.

```bash
sudo mkdir -p /opt/myvpn
sudo chown myvpn-deploy:myvpn-deploy /opt/myvpn
cd /opt/myvpn
cp .env.staging.example .env.staging
chmod 600 .env.staging
editor .env.staging
```

Set `MYVPN_IMAGE` to an immutable published commit tag. Stop every local application instance using the same Telegram bot token before starting staging, otherwise both instances compete for `getUpdates`.

```bash
docker login

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  config --quiet

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  pull app

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  up -d --no-build
```

`docker compose pull` can also refresh the pinned PostgreSQL image. Check state and redacted logs:

```bash
docker compose -f compose.server.yaml ps
docker compose -f compose.server.yaml logs -f app
curl -I https://api.telegram.org
```

Check 3x-ui reachability over HTTPS/TLS without printing a username, password, or session cookie. Staging uses fake payments but real Telegram long polling and 3x-ui. Production rejects both fake providers.

## Production deployment

Create the protected production environment file and replace every placeholder before deployment:

```bash
cp .env.server.example .env.server
chmod 600 .env.server
editor .env.server

grep -nE \
'replace-me|replace-with|dockerhub-user|change-me|<[^>]+>' \
.env.server
```

The final `grep` command must produce no output. Set `MYVPN_IMAGE` to an immutable image tag, never to `latest` or a mutable production alias. Validate Compose interpolation without printing its rendered configuration, because it can contain secrets:

```bash
docker compose \
  --env-file .env.server \
  -f compose.server.yaml \
  config >/dev/null
```

Production requires real payment and VPN providers. Keep `.env.server` private and never commit it.

For the first production version YooKassa webhook is not used. The application does not publish an HTTP port and no reverse proxy, public application domain, or inbound HTTPS route is required. The user receives `confirmation_url` in Telegram, completes payment, and then uses the bot's «Проверить оплату» action. The application verifies the payment with an authenticated YooKassa GET request; the existing pending-payment scheduling and activation/delivery workers continue this flow.

`YOOKASSA_RETURN_URL` remains required for YooKassa redirect confirmation. Set it to any public HTTPS static page that tells the user to return to Telegram and press «Проверить оплату»; it must not point to the application. `PAYMENT_RETURN_URL` is retained as the generic/fake-provider checkout URL, but YooKassa creates redirect payments with `YOOKASSA_RETURN_URL`. Set both variables to the same static page to avoid ambiguity.

## Update and rollback

Update staging by changing only the immutable image reference, then pull and recreate the application:

```bash
nano .env.staging
# MYVPN_IMAGE=dockerhub-user/myvpn:NEW_COMMIT

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  pull app

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  up -d --no-build

docker compose \
  --env-file .env.staging \
  -f compose.server.yaml \
  ps
```

To roll back, restore the previous immutable `MYVPN_IMAGE` commit tag in `.env.staging`, run `pull app`, run `up -d --no-build`, then check health and logs. Rolling back an application image does **not** roll back Liquibase migrations. If a release introduced an incompatible database migration, changing the image tag alone can be insufficient; use a separately tested database rollback plan.

## PostgreSQL backup and restore

Never restore into the current staging or production database. Backups are not repository artifacts and must remain readable only by the deployment account.

```bash
umask 077
mkdir -p /opt/myvpn/backups
stamp=$(date -u +%Y%m%dT%H%M%SZ)
tmp=/opt/myvpn/backups/.myvpn-${stamp}.dump.tmp
final=/opt/myvpn/backups/myvpn-${stamp}.dump

docker compose --env-file .env.staging -f compose.server.yaml exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$tmp"
pg_restore --list "$tmp" >/dev/null
mv "$tmp" "$final"

# Retain only backups older than 30 days; inspect this path before changing it.
find /opt/myvpn/backups -maxdepth 1 -type f -name 'myvpn-*.dump' -mtime +30 -delete
```

Verify recovery only in a separate test database/container, for example with `pg_restore --list` followed by `pg_restore -d myvpn_restore_test backup.dump`. Do not put passwords on command lines or in logs. Keep encrypted off-host copies and test restores regularly.

## Production hardening notes

- Keep `.env.staging` and `.env.server` owner-readable only: `chmod 600 .env.staging` and `chown myvpn-deploy:myvpn-deploy .env.staging`.
- The app runs as non-root with a read-only root filesystem; Compose does not publish PostgreSQL, Spring Boot, or Actuator ports.
- Images exclude `.env*`; never copy secrets through build arguments.
- The app uses UTC (`TZ=UTC`, `-Duser.timezone=UTC`). HikariCP is deliberately small for the 512 MB single-instance VPS: maximum 5, minimum idle 1, 5s connection timeout, 3s validation timeout, 5m idle timeout, 25m max lifetime.

### VLESS client UUID rotation

If a real VLESS client UUID is exposed, create and assign a new UUID in the configured 3x-ui inbound, generate the replacement configuration, and deliver it to the verified user through the normal secure channel. Confirm that the old UUID no longer works before closing the incident. Do not log either configuration, UUID, cookie, password, or URI.

## YooKassa manual verification

Select YooKassa with `PAYMENT_PROVIDER=YOOKASSA`; production rejects `FAKE`. Keep `YOOKASSA_SHOP_ID`, `YOOKASSA_SECRET_KEY`, and `YOOKASSA_RETURN_URL` only in the protected server environment file. Do not put them in Git, Docker build arguments, image labels, or logs.

The first production version has `YOOKASSA_WEBHOOK_ENABLED=false`. Do not configure a YooKassa webhook, Caddy/Nginx route, or a public Spring Boot endpoint for this release. Payment confirmation is performed exclusively by the bot action and pending-payment scheduler through authenticated YooKassa status GET requests. Image rollback does not roll back Liquibase migrations.

## Stop

```bash
docker compose -f compose.server.yaml down
```
