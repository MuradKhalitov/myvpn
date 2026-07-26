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

## 3x-ui provider

The production adapter targets 3x-ui `v2.9.1` and uses its session-cookie API.
Keep the panel credentials and hidden web path outside Git:

```powershell
$env:VPN_PROVIDER_TYPE = "3x-ui"
$env:THREEXUI_BASE_URL = "https://panel.example.com"
$env:THREEXUI_WEB_BASE_PATH = "/hidden-path"
$env:THREEXUI_USERNAME = "<username>"
$env:THREEXUI_PASSWORD = "<password>"
$env:THREEXUI_INBOUND_ID = "123"
$env:THREEXUI_PUBLIC_HOST = "vpn.example.com"
$env:THREEXUI_PUBLIC_PORT_OVERRIDE = ""
$env:THREEXUI_MAX_MUTATION_ATTEMPTS = "3"
$env:THREEXUI_MAX_REQUESTS_PER_OPERATION = "8"
```

`THREEXUI_BASE_URL` must contain only the scheme and authority. Put the hidden
panel path only in `THREEXUI_WEB_BASE_PATH`.

`THREEXUI_PUBLIC_HOST` is mandatory for the real provider and must identify
the public VPN endpoint, not the panel. The optional port override replaces
the inbound port only when explicitly configured. It must contain only a
hostname, IPv4 address, or IPv6 address. Do not include a scheme, port, path,
query, or fragment. Set the public port separately:

```env
THREEXUI_PUBLIC_HOST=vpn.example.com
THREEXUI_PUBLIC_HOST=203.0.113.10
THREEXUI_PUBLIC_HOST=2001:db8::1
THREEXUI_PUBLIC_PORT_OVERRIDE=443
```

For example, `THREEXUI_PUBLIC_HOST=vpn.example.com:443` is invalid. IPv6 may
be supplied with or without brackets. Schemed values such as
`https://vpn.example.com`, IPv6 zone identifiers such as `fe80::1%eth0`,
unspecified addresses `0.0.0.0` and `::`, and IPv4 octets with leading zeroes
are rejected.

Private and loopback hosts are allowed for local and development deployments.
Hostnames must use ASCII labels; configure an IDN in its ASCII-compatible
punycode form.

TLS verification is always enabled. For a panel certificate signed by a private
CA, import that CA into a dedicated Java truststore and pass it to the JVM:

```powershell
keytool -importcert -alias three-x-ui -file panel-ca.crt `
  -keystore three-x-ui-truststore.p12 -storetype PKCS12

$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=three-x-ui-truststore.p12 -Djavax.net.ssl.trustStorePassword=<password>"
```

The current adapter manages client creation, expiry updates, and deletion. For
confirmed VLESS + Reality + TCP clients it builds a VLESS URI from the public
inbound settings and returns it only as the provisioning result. The real URI
is not persisted in PostgreSQL. Other transports and security modes are
rejected until they have dedicated, tested configuration factories.

For Reality links the adapter follows the 3x-ui `v2.9.1` subscription
generator: `spx` is a fresh slash-prefixed, 15-character cryptographically
random alphanumeric path. A server-side `spiderX` value is not copied into the
client configuration.

3x-ui `v2.9.1` refuses to delete the last client of an inbound. The configured
inbound must therefore always contain at least one unmanaged service client.
This application neither creates nor deletes that client automatically. If a
managed client is the last remaining client, revocation fails safely and the
local subscription is not marked as revoked.

Each 3x-ui business operation has one shared HTTP request budget. The default
allows at most three mutation attempts and at most eight total HTTP requests,
including login, authentication replay, mutations, and reconciliation reads.
One request is reserved after a mutation until its reconciliation read.

Provisioning recovery uses a database lease and a unique claim token. A result
from a worker whose lease has been replaced is ignored. After five uncertain
recovery attempts, the subscription enters `MANUAL_REVIEW_REQUIRED`; automatic
recovery stops and the state continues to block creation of another current
subscription until an administrator resolves it.

Rollback of changeset 006 is intentionally rejected while
`MANUAL_REVIEW_REQUIRED` rows exist. Changeset 005 keeps the status column at
`varchar(32)` during rollback so `RECONCILIATION_REQUIRED` is never truncated.
Resolve provisioning states explicitly before operating an older application
version.

## VPN delivery recovery

Automatic Telegram configuration delivery is an at-least-once durable outbox.
If Telegram accepts a message and the application stops before the completion
transaction commits, lease recovery can send the same configuration again.

Deliveries that reach `MANUAL_REVIEW_REQUIRED`, including a configuration that
does not fit Telegram's 4096-character text limit, require support handling.
Locate the row by its safe status/failure code only. Do not copy a VPN URI into
a ticket, application log, or chat. There is deliberately no administrator
resend command. The owner can use `/vpn` in a private chat only when the full
configuration fits in one Telegram text message.
