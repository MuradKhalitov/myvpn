# 3x-ui backup and restore

The backup contains `/etc/x-ui/x-ui.db`: panel users, settings, inbounds and
client/subscription configuration. It excludes reinstallable binaries, stock geo
files, logs and certificates. Nginx/Certbot configuration needs separate backup.
This is not a coordinated snapshot with PostgreSQL or payment systems.

Python's SQLite Backup API reads the live source in `mode=ro`; x-ui and Xray stay
running. This handles concurrent SQLite writes, unlike copying a live database.
The script closes the snapshot, reopens it and requires `PRAGMA integrity_check`
to return exactly `ok` before publishing it with an atomic rename.

## Operation

Run as `myvpn-deploy`, which must be able to read the source DB:

```bash
bash /opt/myvpn/myvpn/scripts/server/backup-x-ui.sh
```

Backups are `/opt/myvpn/backups/x-ui/x-ui-YYYY-MM-DD_HH-mm-ssZ.db` (UTC).
Directory mode is 0700, files 0600, owned by `myvpn-deploy`. A lock prevents
overlapping runs. After success, only direct regular `x-ui-*.db` files older than
14 days are removed. Other backup directories are untouched. Failed snapshots
are not published; a retention failure after publication leaves the new backup.
The directory is deliberately fixed; empty/alternative paths are rejected.
SQLite backup retries are bounded to approximately 120 seconds.

Check a selected backup without printing any table data:

```bash
stat -c '%s bytes %a %U:%G %n' /opt/myvpn/backups/x-ui/x-ui-TIMESTAMP.db
python3 - /opt/myvpn/backups/x-ui/x-ui-TIMESTAMP.db <<'PY'
import sqlite3
import sys
from contextlib import closing
from pathlib import Path
with closing(sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)) as db:
    valid = db.execute('PRAGMA integrity_check;').fetchall() == [('ok',)]
print('integrity: ok' if valid else 'integrity: FAILED')
sys.exit(0 if valid else 1)
PY
```

Integrity checking verifies SQLite structure, not a complete application restore.
Keep the compatible 3x-ui version (currently 2.9.1) recorded for recovery.

## Restore procedure — maintenance only

Do not run a restore as part of normal backup verification.

1. Select and integrity-check the backup above. Arrange a maintenance window.
2. Stop `x-ui.service`; confirm both panel and its Xray process have stopped.
3. Save the current `/etc/x-ui/x-ui.db` and any existing SQLite journal/WAL/SHM
   sidecars together in a separate protected directory. Record original owner
   and mode. Never discard the only current copy.
4. Replace the stopped database with the checked backup. Do not mix a restored
   DB with stale sidecars; retain those only with the saved original database.
5. Restore the expected source owner/mode (audited: `root:root`, 0644), or the
   documented current deployment permissions if these have changed.
6. Start `x-ui.service`; check `systemctl status x-ui --no-pager`, panel login,
   subscriptions, Xray listeners and an actual VPN connection.

## Systemd (prepared, install manually)

Units are `deploy/systemd/myvpn-x-ui-backup.service` and
`deploy/systemd/myvpn-x-ui-backup.timer`. The executable script must first exist
at its permanent path with mode 0755. Install units as root with mode 0644 under
`/etc/systemd/system/`, then run daemon-reload and systemd-analyze verify on both.
Run the service manually and check `Result=success`, `ExecMainStatus=0`, its
journal and the new backup before enabling the timer.

Schedule: daily 03:15 UTC plus up to five minutes randomized delay.
`Persistent=true` catches up a missed run after downtime.

```bash
sudo systemctl start myvpn-x-ui-backup.service
systemctl show myvpn-x-ui-backup.service -p Result -p ExecMainStatus
journalctl -u myvpn-x-ui-backup.service --no-pager
# Only after the manual run and backup checks succeed:
sudo systemctl enable --now myvpn-x-ui-backup.timer
systemctl list-timers --all myvpn-x-ui-backup.timer
# Disable / re-enable:
sudo systemctl disable --now myvpn-x-ui-backup.timer
sudo systemctl enable --now myvpn-x-ui-backup.timer
```
