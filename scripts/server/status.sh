#!/usr/bin/env bash
set -u

overall=0
export LC_ALL=C

report() {
    local label=$1 status=$2 critical=$3 detail=${4-}
    printf '%-15s %-7s %s\n' "$label" "$status" "$detail"
    if [[ $status == ERROR && $critical == yes ]]; then
        overall=2
    elif [[ $status != OK ]] && (( overall < 1 )); then
        overall=1
    fi
}

available() { command -v "$1" >/dev/null 2>&1; }

container() {
    local label=$1 name=$2 state
    if ! available docker || ! available timeout; then
        report "$label" UNKNOWN yes 'docker/timeout unavailable'; return
    fi
    if ! state=$(timeout 8 docker inspect --type container --format \
        '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null); then
        if timeout 8 docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
            report "$label" ERROR yes 'container missing or inspect failed'
        else
            report "$label" UNKNOWN yes 'Docker inaccessible'
        fi
        return
    fi
    case "$state" in
        'true healthy') report "$label" OK yes ;;
        'true none') report "$label" OK yes 'running; no healthcheck' ;;
        'true starting') report "$label" WARNING yes 'health starting' ;;
        'true unhealthy') report "$label" ERROR yes 'unhealthy' ;;
        true*) report "$label" WARNING yes 'health unknown' ;;
        *) report "$label" ERROR yes 'not running' ;;
    esac
}

api_health() {
    local response
    if ! available curl; then report 'API health' UNKNOWN yes 'curl unavailable'; return; fi
    if response=$(curl --silent --fail --connect-timeout 3 --max-time 5 \
        https://api.myvpn05.ru/actuator/health 2>/dev/null) &&
        [[ $response =~ \"status\"[[:space:]]*:[[:space:]]*\"UP\" ]]; then
        report 'API health' OK yes
    else
        report 'API health' ERROR yes
    fi
}

unit_status() {
    local label=$1 unit=$2 critical=$3 timer=${4-no} active enabled
    if ! available systemctl || ! available timeout; then
        report "$label" UNKNOWN "$critical" 'systemctl/timeout unavailable'; return
    fi
    active=$(timeout 5 systemctl is-active "$unit" 2>/dev/null)
    case "$active" in
        active) ;;
        inactive|failed|activating|deactivating|reloading|maintenance|unknown)
            report "$label" ERROR "$critical" 'not active or unit missing'; return ;;
        *) report "$label" UNKNOWN "$critical" 'systemd inaccessible'; return ;;
    esac
    if [[ $timer == yes ]]; then
        enabled=$(timeout 5 systemctl is-enabled "$unit" 2>/dev/null)
        case "$enabled" in
            enabled) ;;
            '') report "$label" UNKNOWN "$critical" 'enablement inaccessible'; return ;;
            *) report "$label" ERROR "$critical" 'not persistently enabled'; return ;;
        esac
    fi
    report "$label" OK "$critical"
}

ports() {
    local listeners port critical
    if ! available ss || ! listeners=$(ss -H -ltn 2>/dev/null); then
        for port in 26810 2096 58131; do report "$port" UNKNOWN "$([[ $port == 58131 ]] && echo yes || echo no)" 'ss unavailable'; done
        return
    fi
    for port in 26810 2096 58131; do
        critical=no
        [[ $port == 58131 ]] && critical=yes
        if awk -v port="$port" '
            $4 ~ (":" port "$") {
                address=$4; sub(/:[0-9]+$/, "", address)
                loopback=(address ~ /^127\./ || address == "[::1]" || address == "::1")
                if ((port != 58131 && loopback) || (port == 58131 && !loopback)) found=1
            }
            END { exit !found }
        ' <<< "$listeners"; then
            report "$port" OK "$critical"
        else
            report "$port" ERROR "$critical" 'expected listener absent'
        fi
    done
}

backup() {
    local label=$1 directory=$2 pattern=$3 file latest='' modified newest=0 now age status
    if [[ ! -r $directory || ! -x $directory ]] || ! available stat || ! available date; then
        report "$label" UNKNOWN yes 'backup directory or metadata inaccessible'; return
    fi
    # Only metadata is read; globbing needs directory read/search permission.
    for file in "$directory"/$pattern; do
        [[ -f $file ]] || continue
        if ! modified=$(stat -c %Y -- "$file" 2>/dev/null) || [[ ! $modified =~ ^[0-9]+$ ]]; then
            report "$label" UNKNOWN yes 'backup metadata inaccessible'; return
        fi
        if [[ -z $latest ]] || (( modified > newest )); then latest=$file; newest=$modified; fi
    done
    if [[ -z $latest ]]; then report "$label" ERROR yes 'no backup'; return; fi
    if ! now=$(date +%s) || [[ ! $now =~ ^[0-9]+$ ]]; then report "$label" UNKNOWN yes 'clock unavailable'; return; fi
    age=$((now - newest))
    if (( age < 0 )); then report "$label" UNKNOWN yes 'backup timestamp is in the future'; return; fi
    status=OK
    if (( age > 48 * 3600 )); then status=ERROR
    elif (( age > 36 * 3600 )); then status=WARNING; fi
    report "$label" "$status" yes "age=$((age / 3600))h latest=${latest##*/}"
}

disk() {
    local usage used mount
    if ! usage=$(df -P /opt/myvpn 2>/dev/null | awk 'NR==2 {print $5, $6}') || [[ -z $usage ]]; then
        report '/opt/myvpn' UNKNOWN yes 'filesystem inaccessible'; return
    fi
    read -r used mount <<< "$usage"
    used=${used%\%}
    if [[ ! $used =~ ^[0-9]+$ ]]; then report '/opt/myvpn' UNKNOWN yes; return; fi
    local status=OK
    if (( used >= 90 )); then status=ERROR
    elif (( used >= 80 )); then status=WARNING; fi
    report "$mount" "$status" yes "used=$used%"
}

certificate() {
    local label=$1 domain=$2 expiry end now days status
    if ! available openssl || ! available timeout || ! available date; then
        report "$label" UNKNOWN no 'openssl/timeout/date unavailable'; return
    fi
    if ! expiry=$(set -o pipefail; timeout 8 openssl s_client -connect "$domain:443" \
        -servername "$domain" </dev/null 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null) ||
        [[ $expiry != notAfter=* ]]; then
        report "$label" UNKNOWN no 'certificate unavailable'; return
    fi
    if ! end=$(date -d "${expiry#notAfter=}" +%s 2>/dev/null) || ! now=$(date +%s); then
        report "$label" UNKNOWN no 'expiry unavailable'; return
    fi
    days=$(((end - now) / 86400))
    status=OK
    if (( days < 7 )); then status=ERROR
    elif (( days < 14 )); then status=WARNING; fi
    report "$label" "$status" no "$days days"
}

main() {
    printf '=== MyVPN Server Status ===\n\n'
    container Backend myvpn-app-1
    container PostgreSQL myvpn-postgres-1
    unit_status 3x-ui x-ui.service yes
    api_health
    printf '\nPorts\n'
    ports
    printf '\nBackups\n'
    backup PostgreSQL /opt/myvpn/backups/postgres 'myvpn-*.sql.gz'
    backup 3x-ui /opt/myvpn/backups/x-ui 'x-ui-*.db'
    printf '\nTimers\n'
    unit_status PostgreSQL myvpn-postgres-backup.timer no yes
    unit_status 3x-ui myvpn-x-ui-backup.timer no yes
    printf '\nDisk\n'
    disk
    printf '\nCertificates\n'
    certificate api api.myvpn05.ru
    certificate panel panel.myvpn05.ru
    certificate sub sub.myvpn05.ru
    local statuses=(OK WARNING ERROR)
    printf '\nOverall: %s\n' "${statuses[$overall]}"
    return "$overall"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main
    exit "$?"
fi
