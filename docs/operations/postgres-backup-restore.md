# PostgreSQL backup and restore

## Scope and current deployment

This runbook describes manual backups and systemd automation. No cron job is used.
Production restore requires a separate approved maintenance operation.

- Project: `/opt/myvpn/myvpn`; Compose: `compose.server.yaml`; env: `.env.staging`.
- PostgreSQL service: `postgres`; audited version: PostgreSQL 16.6 (`postgres:16.6-alpine`).
- Database/owner: `myvpn`; application tables and Liquibase metadata: `public`.
- Data volume: `myvpn_myvpn-postgres-data`; PGDATA: `/var/lib/postgresql/data`.
- The audited database was approximately 8.53 MiB; free host disk was approximately 9.5 GiB.
  These sizes are historical observations, not guaranteed capacity.

The script dumps the entire database as plain SQL compressed with gzip: schema,
table data, sequences, ownership, grants and Liquibase tables. It does not dump
cluster-wide roles/tablespaces, server configuration, Docker volumes, or other databases.
Recreate required roles before restore. The audited `myvpn` role is a superuser;
changing application privileges is a separate task.

`pg_dump` uses a consistent database snapshot while ordinary reads and writes
continue. Transactions not committed at that snapshot are excluded. Neither the
backend nor PostgreSQL is stopped for backup. Dump holds table locks that can
conflict with DDL; do not overlap it with deployment/Liquibase migrations. There
is additional CPU/I/O load, and a long dump can delay cleanup of old row versions.
See [PostgreSQL 16 pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html).

This is **not a coordinated snapshot with 3x-ui or YooKassa**. Their state must be
reconciled after restore. Daily backups allow roughly 24 hours of data loss when
running successfully; there is no point-in-time recovery from these SQL files.

## Storage and manual execution

After the script has been separately delivered to the server, run from your workstation:

```bash
ssh myvpn-vps 'bash /opt/myvpn/myvpn/scripts/server/backup-postgres.sh'
```

The script runs as the SSH user with Docker access (`myvpn-deploy` in the audit).
It creates `/opt/myvpn/backups/postgres` owned by that user, mode `0700`, and files
named `myvpn-YYYY-MM-DD_HH-mm-ssZ.sql.gz` in UTC, mode `0600`. It refuses an existing
backup directory owned by a different user. Choose one operating account and keep
using it; do not switch between root and the deployment account without planning ownership.

Defaults can be overridden through `PROJECT_DIR`, `COMPOSE_FILE`, `ENV_FILE`,
`BACKUP_DIR`, and `RETENTION_DAYS`. Compose/env defaults follow `PROJECT_DIR`.
Paths must be absolute; the dedicated backup directory must end in `/postgres`,
be outside the project and have no symlinks or `..` components. Empty values fail
validation. Do not change these defaults for the first production run.

The env file is passed only to Docker Compose; the script never sources or prints
it. Database/user come from the container's existing `POSTGRES_DB`/`POSTGRES_USER`.
The audited local Unix socket authentication permits `--no-password`; if that
policy changes, the job must fail rather than request or print credentials.

Preflight checks Docker/Compose, files, running service and disk capacity. The
capacity threshold is twice `pg_database_size` plus 100 MiB. This is headroom,
not a promise that a dump will fit if other workloads consume space meanwhile.

`flock` serializes jobs sharing the directory. A second launch exits nonzero with
an error. The persistent `.backup-postgres.lock` file must not be deleted to
"unlock" a job. A timestamp collision is rejected instead of overwriting a backup.

The job uses `set -Eeuo pipefail`, `umask 077`, its own `mktemp` file in the backup
directory, `gzip -t`, and an atomic rename. Errors during dump/compression/validation
leave no new final backup. EXIT/INT/TERM handling removes only this run's temporary
file. SIGKILL or power loss can leave a hidden temporary file; it is never treated
as a backup or automatically removed by a later job.

Logs contain timestamps, the backup basename, success/error, final size and retention
result. SQL, environment and raw Docker/PostgreSQL diagnostics are not logged.
An error identifies the failed stage. Do not troubleshoot by printing environment
variables or enabling shell tracing.

## Retention

Retention is **14 days**, executed only after publishing a successful new backup.
Only regular `myvpn-*.sql.gz` files directly in the dedicated directory, older
than the retention interval (minute precision), are deleted. The newly published
file is explicitly excluded. No subdirectories, other temporary files or symlinks
are removed. Approximately 14 copies remain with one successful run per day;
manual extra runs can produce more files. A retention error returns nonzero but
preserves the already published new backup.

The existing `/opt/myvpn/backups/myvpn-2026-07-27T09-34-53Z.dump` is outside this
directory and is neither changed nor considered verified by this procedure.
The systemd timer below schedules daily backups; keep deployments outside that window.

Local storage does not protect against loss of the VPS. Plan encrypted off-server
copies and monitoring of failures, backup age, available space and restore drills.
Gzip is not encryption; dumps contain sensitive application/VPN/authentication data.

## Systemd automation

Repository units:

- `deploy/systemd/myvpn-postgres-backup.service`
- `deploy/systemd/myvpn-postgres-backup.timer`

Installed locations:

- `/etc/systemd/system/myvpn-postgres-backup.service`
- `/etc/systemd/system/myvpn-postgres-backup.timer`

The oneshot service runs the executable script directly at
`/opt/myvpn/myvpn/scripts/server/backup-postgres.sh`, as
`myvpn-deploy:myvpn-deploy`, with `/opt/myvpn/myvpn` as its working directory.
The script is tracked with executable permission and must retain LF line endings.
The account needs Docker socket access through its existing group membership and
read access to the Compose/env files. Secrets are not placed in the units.

Hardening is limited to `UMask=0077`, `NoNewPrivileges=true` and `PrivateTmp=true`.
The job still needs Docker access; these settings do not make a Docker-capable
account unprivileged. The backup directory remains accessible outside private tmp.
`TimeoutStartSec=30min` bounds a stuck job; a timeout is a failed run, not a backup.

Schedule: every day at **03:00 UTC**, plus a random delay up to five minutes
(systemd's normal timer accuracy can add scheduling tolerance).
`Persistent=true` catches up a missed scheduled run after the timer/server starts.
It can therefore trigger a run outside the normal window when first enabled or
re-enabled after downtime. Completed files appear in `/opt/myvpn/backups/postgres`.

Before installation, ensure the server working tree has no unexpected changes,
update the repository with a fast-forward pull, and check the script:

```bash
bash -n /opt/myvpn/myvpn/scripts/server/backup-postgres.sh
test -x /opt/myvpn/myvpn/scripts/server/backup-postgres.sh
sudo install -o root -g root -m 0644 \
  /opt/myvpn/myvpn/deploy/systemd/myvpn-postgres-backup.service \
  /etc/systemd/system/myvpn-postgres-backup.service
sudo install -o root -g root -m 0644 \
  /opt/myvpn/myvpn/deploy/systemd/myvpn-postgres-backup.timer \
  /etc/systemd/system/myvpn-postgres-backup.timer
sudo systemctl daemon-reload
sudo systemd-analyze verify \
  /etc/systemd/system/myvpn-postgres-backup.service \
  /etc/systemd/system/myvpn-postgres-backup.timer
```

If verification fails, stop and do not enable the timer. Run the service manually
before enabling automation, inspect its exit status and verify the new file:

```bash
sudo systemctl start myvpn-postgres-backup.service
sudo systemctl status myvpn-postgres-backup.service --no-pager
sudo journalctl -u myvpn-postgres-backup.service -n 100 --no-pager
systemctl show myvpn-postgres-backup.service -p Result -p ExecMainStatus
```

Successful oneshot completion is normally `inactive (dead)` with
`Result=success` and `ExecMainStatus=0`; `systemctl status` can return 3 for this
inactive state. It does not mean the backup failed. Require a new nonempty file,
successful `gzip -t`, mode `0600`, directory `0700` and owner `myvpn-deploy`.
Do not enable the timer after a failed service run or failed archive check.

After the successful manual check, enable and inspect:

```bash
sudo systemctl enable --now myvpn-postgres-backup.timer
systemctl status myvpn-postgres-backup.timer --no-pager
systemctl list-timers --all | grep myvpn-postgres-backup
journalctl -u myvpn-postgres-backup.service
```

Use `sudo journalctl` if the deployment account cannot read the system journal.
Check both enabled/active state and the next run time; inspect service failures
and the age of the latest backup regularly. A timer being active alone does not
prove that backups succeed.

Disable scheduling without deleting backups:

```bash
sudo systemctl disable --now myvpn-postgres-backup.timer
```

This does not stop an already running backup service. To re-enable scheduling:

```bash
sudo systemctl enable --now myvpn-postgres-backup.timer
```

## Non-restoring checks

On the VPS, select an exact completed file; do not select the hidden temp file:

```bash
BACKUP=/opt/myvpn/backups/postgres/myvpn-YYYY-MM-DD_HH-mm-ssZ.sql.gz
gzip -t -- "$BACKUP"
stat -c '%n %s bytes mode=%a owner=%U:%G' -- "$BACKUP"
```

To check for a pg_dump header, table structure and completion marker without
printing any SQL or user data, read the entire stream and emit only PASS/FAIL:

```bash
set -o pipefail
gzip -dc -- "$BACKUP" | awk '
  /^-- PostgreSQL database dump$/ { header = 1 }
  /^CREATE TABLE / { structure = 1 }
  /^-- PostgreSQL database dump complete$/ { complete = 1 }
  END {
    ok = header && structure && complete
    print ok ? "SQL structure check: PASS" : "SQL structure check: FAIL"
    exit !ok
  }
'
```

These checks do not prove restorability. **A backup remains unconfirmed until a
test restore succeeds.** Periodically restore into an isolated PostgreSQL 16
instance with matching roles/locale and validate application tables, constraints,
ownership, sequences and Liquibase state. Keep any test backend isolated from
production 3x-ui, payments, SMS and other external integrations. Record the backup
identifier, checksum, restore outcome, validation and duration without dumping data.

## Restore to an existing empty database (maintenance only)

Do not execute this section during backup implementation or against a populated DB.

1. Select a trusted, test-restored backup and compatible backend image. Preserve
   the current production state separately before approving any destructive action.
2. Stop all backend instances and other database writers; keep PostgreSQL running.
   Block incoming user traffic. Scheduled subscription/payment/VPN work must also stop.
3. Verify server/client versions (initial recovery on PostgreSQL 16), role `myvpn`,
   extensions, encoding UTF8 and locale `en_US.utf8`. The audit found only standard
   `plpgsql`, `pg_default` and `pg_global`. Recheck rather than assume no later changes.
4. Verify the target database is empty. **Never overlay existing tables.** If not
   empty, stop and approve a separate replacement procedure: preserve the old DB,
   disconnect writers and create a clean DB from `template0` with the correct owner
   and locale. Do not delete the Docker data volume. Do not start backend/Liquibase
   to prepopulate the new database before importing the dump.
5. Check the immutable selected file with `gzip -t` and its recorded checksum.
   Restore plain SQL with `psql`, not `pg_restore`. On the server, the following is
   the restore step **only after the previous gates**, with `BACKUP` set explicitly:

   ```bash
   set -Eeuo pipefail
   gzip -t -- "$BACKUP"
   gzip -dc -- "$BACKUP" | docker compose \
     -f /opt/myvpn/myvpn/compose.server.yaml \
     --env-file /opt/myvpn/myvpn/.env.staging \
     exec -T postgres psql -X --no-password \
       --username=myvpn --dbname=myvpn \
       --set=ON_ERROR_STOP=on --single-transaction
   ```

   Protect restore diagnostics: SQL errors can contain data. If any stage fails,
   keep backend stopped and investigate. Do not assume a decompression/transport
   failure guarantees rollback; verify/reset the disposable target before retrying.
6. Check `databasechangelog` and `databasechangeloglock`; preserve history and
   checksums. The audit had 10 changesets and no lock, but use the chosen backup's
   expected migration state. Never blindly clear locks/checksums or rerun old DDL.
7. Verify key tables: `accounts`, `account_identities`, `device_credentials`,
   `auth_sessions`, `subscriptions`, `payment_orders`, `vpn_accesses`, `vpn_tariffs`.
   Check expected counts, foreign keys, uniqueness, sequence values, owners/grants,
   and run `ANALYZE` on the restored DB before resuming service.
8. Reconcile 3x-ui and YooKassa operations since the snapshot. Restoring old auth
   state can also undo session revocations. Do not let background workers blindly
   mutate external state before reviewing recovery implications.
9. Start one compatible backend, observe Liquibase and Hibernate validation,
   check health/authentication/subscriptions/VPN and only then reopen traffic.

See [PostgreSQL SQL dump restore](https://www.postgresql.org/docs/16/backup-dump.html).

## Disaster recovery on a new VPS

1. Provision Ubuntu, Docker/Compose, capacity, firewall and restricted access.
2. Restore Compose/overrides and the known backend image version/digest. Retrieve
   secrets from protected storage; never embed them in the runbook or dump logs.
3. Create a new data volume and start **only PostgreSQL**, with a compatible version,
   required roles and empty DB. Do not start the whole Compose stack yet.
4. Follow the empty-database restore and validation procedure above.
5. Restore the separately backed-up 3x-ui database/config and matching Xray setup,
   Nginx configuration, systemd units/drop-ins, Certbot renewal configuration and
   certificates, and published Android APKs/metadata. Reconcile external state.
6. Validate one backend before switching DNS/traffic, then resume backup and verify
   the first new copy. Test restore again after infrastructure/version changes.

PostgreSQL alone cannot recover MyVPN. Store database/3x-ui passwords, payment/SMS
credentials, JWT private keys, peppers, SSH/TLS private keys, `.env.staging` and
Android signing keystore separately in encrypted, access-controlled storage.
Losing peppers may invalidate existing credentials/sessions. Keep recovery access
and encryption keys independent of the VPS being recovered.
