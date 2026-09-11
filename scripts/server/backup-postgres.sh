#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077

PROJECT_DIR=${PROJECT_DIR-/opt/myvpn/myvpn}
COMPOSE_FILE=${COMPOSE_FILE-$PROJECT_DIR/compose.server.yaml}
ENV_FILE=${ENV_FILE-$PROJECT_DIR/.env.staging}
BACKUP_DIR=${BACKUP_DIR-/opt/myvpn/backups/postgres}
RETENTION_DAYS=${RETENTION_DAYS-14}

tmp_file=''
stage='preflight'

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "ERROR: $*" >&2
    exit 1
}

cleanup() {
    local status=$?
    trap - EXIT
    if [[ -n $tmp_file ]]; then
        if ! rm -f -- "$tmp_file" 2>/dev/null; then
            log 'ERROR: own temporary file could not be removed' >&2
            status=1
        fi
    fi
    if (( status != 0 )); then
        log "ERROR: backup job failed during $stage (exit $status)" >&2
    fi
    exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log 'START: PostgreSQL backup'
for command in docker flock gzip mktemp realpath df awk date chmod mkdir mv rm find stat; do
    command -v "$command" >/dev/null 2>&1 || fail "required command unavailable: $command"
done

[[ $RETENTION_DAYS =~ ^[1-9][0-9]{0,3}$ ]] || fail 'RETENTION_DAYS must be an integer from 1 to 9999'
[[ $PROJECT_DIR == /* && -d $PROJECT_DIR ]] || fail 'PROJECT_DIR must be an existing absolute directory'
[[ $COMPOSE_FILE == /* && -f $COMPOSE_FILE && -r $COMPOSE_FILE ]] || fail 'compose file unavailable or not absolute'
[[ $ENV_FILE == /* && -f $ENV_FILE && -r $ENV_FILE ]] || fail 'env file unavailable or not absolute'

# Require a dedicated directory named postgres, below an absolute parent.
# Reject symlinks, traversal, trailing slashes and ambiguous paths before mkdir/find.
[[ $BACKUP_DIR == /*/postgres ]] || fail 'BACKUP_DIR must be an absolute dedicated directory ending in /postgres'
canonical_dir=$(realpath -m -- "$BACKUP_DIR" 2>/dev/null)
[[ $BACKUP_DIR == "$canonical_dir" && $BACKUP_DIR != /postgres ]] || fail 'BACKUP_DIR must be canonical and contain no symlinks'
project_path=$(realpath -e -- "$PROJECT_DIR" 2>/dev/null)
[[ $BACKUP_DIR != "$project_path" && $BACKUP_DIR != "$project_path/"* ]] || fail 'BACKUP_DIR must be outside the project'

docker info >/dev/null 2>&1 || fail 'Docker daemon unavailable'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose unavailable'
compose=(docker compose --project-directory "$PROJECT_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE")
running_services=$("${compose[@]}" ps --status running --services 2>/dev/null)
[[ $'\n'"$running_services"$'\n' == *$'\npostgres\n'* ]] || fail 'PostgreSQL service is not running'

mkdir -p -- "$BACKUP_DIR" 2>/dev/null
[[ -d $BACKUP_DIR && -O $BACKUP_DIR && ! -L $BACKUP_DIR ]] || fail 'backup directory must be owned by the current user'
chmod 700 -- "$BACKUP_DIR" 2>/dev/null
lock_file=$BACKUP_DIR/.backup-postgres.lock
[[ ! -L $lock_file && ( ! -e $lock_file || ( -f $lock_file && -O $lock_file ) ) ]] || fail 'unsafe lock file'
exec 9>>"$lock_file"
flock -n 9 || fail 'another backup is running or the lock is unavailable'

# Read only the database size, never source the env file or print container env.
database_bytes=$("${compose[@]}" exec -T postgres sh -eu -c '
    exec psql -X --no-password --set=ON_ERROR_STOP=on \
        --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
        -Atc "SELECT pg_database_size(current_database());"
' 2>/dev/null)
[[ $database_bytes =~ ^[0-9]{1,15}$ ]] || fail 'could not determine database size'
available_kb=$(df -Pk -- "$BACKUP_DIR" 2>/dev/null | awk 'NR == 2 {print $4}')
[[ $available_kb =~ ^[0-9]+$ ]] || fail 'could not determine free disk space'
# Two database sizes plus 100 MiB of headroom; an estimate, not a reservation.
required_kb=$((2 * (database_bytes / 1024 + 1) + 102400))
(( available_kb >= required_kb )) || fail 'insufficient free disk space'

backup_name="myvpn-$(date -u +%Y-%m-%d_%H-%M-%SZ).sql.gz"
final_file=$BACKUP_DIR/$backup_name
[[ ! -e $final_file && ! -L $final_file ]] || fail 'backup name already exists; retry later'
log "BACKUP: $backup_name"

stage='dump and compression'
tmp_file=$(mktemp "$BACKUP_DIR/.myvpn-backup.XXXXXXXX.tmp" 2>/dev/null)
"${compose[@]}" exec -T postgres sh -eu -c '
    exec pg_dump --format=plain --no-password --lock-wait-timeout=10s \
        --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"
' 2>/dev/null | gzip -6 2>/dev/null > "$tmp_file"

stage='gzip validation'
gzip -t -- "$tmp_file" 2>/dev/null
chmod 600 -- "$tmp_file" 2>/dev/null
backup_bytes=$(stat -c %s -- "$tmp_file" 2>/dev/null)

stage='atomic publication'
# Same directory/filesystem: rename publishes a complete file with mode 0600.
mv -T -- "$tmp_file" "$final_file" 2>/dev/null
tmp_file=''
log "SUCCESS: $backup_name size_bytes=$backup_bytes"

stage='retention (new backup already published)'
# No recursion or symlink following. Never delete this run's new backup.
# GNU find minute precision avoids the extra day caused by -mtime rounding.
deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'myvpn-*.sql.gz' \
    ! -name "$backup_name" -mmin "+$((RETENTION_DAYS * 1440))" \
    -delete -printf '.' 2>/dev/null)
log "RETENTION: deleted=${#deleted} retention_days=$RETENTION_DAYS"
