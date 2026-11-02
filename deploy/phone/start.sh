#!/data/data/com.termux/files/usr/bin/sh
# Starts the phone's live cell (cell a, ADR-015) and keeps it running: the banks in one JVM, the public edge, and a
# connector of cardrail's own tunnel. Backseat runs this as ONE app, `cardrail` (`sh start.sh` in ~/cardrail);
# stopping that app stops all of cardrail here, because everything started here is in its process group.
#
# The phone is Sprout's first, so this starter is careful in the ways Sprout's is:
#   - it waits until the phone has been up 10 minutes, after Sprout's own staged start
#   - it starts nothing while Sprout's cellwatch is TAKING_OVER (its worst memory moment)
#   - it starts the JVM only with CARDRAIL_MIN_FREE_MB available (default 1450: the 1.2 GB Sprout keeps, plus ours)
#   - everything runs at a lower priority than the phone's own work
# It restarts a piece that dies, after the same checks. The JVM exits on purpose (status 3) when its cell changes
# state, and is started again at once to run what the new state needs.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGS="$HERE/logs"
SETTLE=${CARDRAIL_SETTLE_SECONDS:-600}
MIN_FREE_MB=${CARDRAIL_MIN_FREE_MB:-1450}
SPROUT_STATUS="$HOME/sprout/cells/status.json"
mkdir -p "$LOGS"

# the cell's settings, then its secrets (cardrail's own keys, and the database password Sprout's session set)
set -a
. "$HERE/cell.env"
. "$HERE/secrets.env"
[ -f "$HOME/.cardrail.env" ] && . "$HOME/.cardrail.env"
set +a

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
booted_for() { python3 -c 'import time; print(int(time.clock_gettime(time.CLOCK_BOOTTIME)))'; }
free_mb() { free -m | awk 'NR==2 {print $7}'; }
sprout_taking_over() { grep -q TAKING_OVER "$SPROUT_STATUS" 2>/dev/null; }
heap() { if [ "$(cat "$CARDRAIL_STATE_FILE" 2>/dev/null)" = HOLDING ]; then echo "$HOLDING_XMX"; else echo "$XMX"; fi; }
java_run() {
    nice -n 10 java -XX:+UseSerialGC -Xms16m -Xmx"$(heap)" -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 -Xss512k \
        -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 \
        -XX:ActiveProcessorCount=2 -jar "$HERE/cardrail-host.jar" "$@"
}

# wait until starting is safe: the phone settled, Sprout not taking over, its Postgres up, and memory to spare
ready() {
    [ "$(booted_for)" -ge "$SETTLE" ] || return 1
    sprout_taking_over && return 1
    pg_isready -q -h 127.0.0.1 -p "${CARDRAIL_PG_PORT:-5432}" || return 1
    [ "$(free_mb)" -ge "$1" ]
}
wait_ready() {
    until ready "$1"; do
        log "waiting: up $(booted_for)s, $(free_mb) MB available, Sprout $(sprout_taking_over && echo taking over || echo settled)"
        sleep 30
    done
}

pid_of() { cat "$LOGS/$1.pid" 2>/dev/null; }
running() { p=$(pid_of "$1") && [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }
kill_tree() {
    for c in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$c"; done
    kill -TERM "$1" 2>/dev/null
}
stop_all() {
    for piece in front edge host; do
        p=$(pid_of "$piece") && [ -n "$p" ] && kill_tree "$p"
    done
    exit 0
}
trap stop_all INT TERM

start() {
    case "$1" in
        host)  ( java_run banks >> "$LOGS/host.log" 2>&1 ) & ;;
        edge)  ( CELL=a LOCAL=127.0.0.1 PEER="$LAPTOP_LAN" STATE_FILE="$CARDRAIL_STATE_FILE" \
                 TEMPLATE="$HERE/edge.conf.template" CONF="$HERE/edge/server.conf" LISTEN=127.0.0.1:8280 ROOT="$HERE/web" \
                 NGINX="nginx -p $HERE/edge -c $HERE/nginx.conf" sh "$HERE/edge.sh" >> "$LOGS/edge.log" 2>&1 ) & ;;
        front) ( nice -n 10 cloudflared tunnel --no-autoupdate --config "$HERE/tunnel/config.yml" run >> "$LOGS/front.log" 2>&1 ) & ;;
    esac
    echo $! > "$LOGS/$1.pid"
    log "started $1"
}

# Before this release serves: both cells migrate every schema (rows replicate, DDL doesn't), then publish and subscribe.
wait_ready "$MIN_FREE_MB"
mkdir -p "$HERE/edge/logs"
java_run migrate >> "$LOGS/admin.log" 2>&1 || { log "migrate failed; see admin.log"; exit 1; }
( until java_run replicate >> "$LOGS/admin.log" 2>&1; do sleep 60; done; log "replicating both ways" ) &

start host
sleep 60
start edge
start front

# keep each piece running; the JVM restarts at once after a state change, after the usual checks otherwise
while sleep 15; do
    for piece in host edge front; do
        running "$piece" && continue
        if [ "$piece" = host ]; then
            wait_ready "$MIN_FREE_MB"
        fi
        start "$piece"
    done
done
