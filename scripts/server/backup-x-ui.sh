#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077

SOURCE_DB=${SOURCE_DB-/etc/x-ui/x-ui.db}
BACKUP_DIR=${BACKUP_DIR-/opt/myvpn/backups/x-ui}
RETENTION_DAYS=${RETENTION_DAYS-14}
tmp_file=''
stage='preflight'

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }
cleanup() {
    local status=$?
    trap - EXIT
    if [[ -n $tmp_file ]]; then
        # Only this invocation's temporary database and SQLite sidecars.
        rm -f -- "$tmp_file" "$tmp_file-journal" "$tmp_file-wal" "$tmp_file-shm" 2>/dev/null || status=1
    fi
    if (( status != 0 )); then
        log "ERROR: backup failed during $stage (exit $status)" >&2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log 'START: 3x-ui backup'
for command in python3 flock mktemp realpath date chmod mkdir mv rm find stat; do
    command -v "$command" >/dev/null 2>&1 || fail "required command unavailable: $command"
done
[[ $RETENTION_DAYS =~ ^[1-9][0-9]{0,3}$ ]] || fail 'invalid RETENTION_DAYS (expected 1..9999)'
[[ $SOURCE_DB == /* && -f $SOURCE_DB && -r $SOURCE_DB ]] || fail 'source must be an existing readable absolute file'
# Retention is deliberately restricted to this one directory, even with overrides.
[[ $BACKUP_DIR == /opt/myvpn/backups/x-ui ]] || fail 'BACKUP_DIR must be /opt/myvpn/backups/x-ui'
[[ $(realpath -m -- "$BACKUP_DIR") == "$BACKUP_DIR" ]] || fail 'backup directory must not contain symlinks'
[[ $(realpath -e -- "$SOURCE_DB") != "$BACKUP_DIR/"* ]] || fail 'source must be outside backup directory'
mkdir -p -- "$BACKUP_DIR" 2>/dev/null
[[ -O $BACKUP_DIR && ! -L $BACKUP_DIR ]] || fail 'backup directory must be owned by current user'
chmod 700 -- "$BACKUP_DIR" 2>/dev/null
lock_file=$BACKUP_DIR/.backup-x-ui.lock
[[ ! -L $lock_file && ( ! -e $lock_file || ( -f $lock_file && -O $lock_file ) ) ]] || fail 'unsafe lock file'
exec 9>>"$lock_file"
flock -n 9 || fail 'another backup is running or lock unavailable'

backup_name="x-ui-$(date -u +%Y-%m-%d_%H-%M-%SZ).db"
final_file=$BACKUP_DIR/$backup_name
[[ ! -e $final_file && ! -L $final_file ]] || fail 'backup name already exists; retry later'
log "BACKUP: $backup_name"
tmp_file=$(mktemp "$BACKUP_DIR/.x-ui-backup.XXXXXXXX.tmp" 2>/dev/null)
stage='SQLite snapshot and integrity check'
python3 - "$SOURCE_DB" "$tmp_file" <<'PY'
import sqlite3
import sys
import time
from contextlib import closing
from pathlib import Path

try:
    deadline = time.monotonic() + 120

    def progress(status, remaining, total):
        if time.monotonic() > deadline:
            raise TimeoutError("backup deadline exceeded")

    source_uri = Path(sys.argv[1]).resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(source_uri, uri=True, timeout=10)) as source:
        with closing(sqlite3.connect(sys.argv[2])) as destination:
            source.backup(destination, pages=128, progress=progress, sleep=0.1)
    backup_uri = Path(sys.argv[2]).resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(backup_uri, uri=True)) as check:
        if check.execute("PRAGMA integrity_check;").fetchall() != [("ok",)]:
            raise RuntimeError("integrity check failed")
except Exception:
    # SQLite diagnostics may contain database content; the shell logs the stage.
    sys.exit(1)
PY
chmod 600 -- "$tmp_file" 2>/dev/null
backup_bytes=$(stat -c %s -- "$tmp_file")
(( backup_bytes > 0 )) || fail 'empty backup'
stage='atomic publication'
mv -T -- "$tmp_file" "$final_file" 2>/dev/null
tmp_file=''
log "SUCCESS: $backup_name size_bytes=$backup_bytes integrity=ok"
stage='retention (new backup already published)'
deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'x-ui-*.db' \
    ! -name "$backup_name" -mmin "+$((RETENTION_DAYS * 1440))" -delete -printf '.' 2>/dev/null)
log "RETENTION: deleted=${#deleted} retention_days=$RETENTION_DAYS"
