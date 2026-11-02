#!/bin/sh
# Hands a cell's half back after a takeover (ADR-015), from the laptop. The returning cell is TAKEN_OVER (serving
# nothing), the other is HOLDING (serving everything):
#
#   deploy/failback.sh a      the phone came back; the laptop holds the banks
#   deploy/failback.sh b      the laptop came back; the phone holds the rails
#
# Payments are refused only between the holder stopping and the two cells starting again, about a minute.
# Stops at the first failure; every step can be run again.
set -eu
BACK=${1:?usage: deploy/failback.sh a|b}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHONE=${CARDRAIL_PHONE_SSH:-phone}
TAG=$(docker inspect --format '{{.Config.Image}}' cardrail-live-rails-1 | sed 's/.*://')
say() { printf '\n== %s\n' "$*"; }
cd "$ROOT"

laptop() { CARDRAIL_VERSION=$TAG docker compose -f deploy/laptop/compose.yml "$@"; }
laptop_admin() { laptop run --rm --no-deps -e XMX=64m rails "$1"; }
laptop_state() { laptop run --rm --no-deps --entrypoint sh rails -c "echo $1 > /state/cell-state"; }
# shellcheck disable=SC2029
phone_admin() { ssh "$PHONE" "cd ~/cardrail && set -a && . ./cell.env && . ./secrets.env && set +a && java -XX:+UseSerialGC -Xmx64m -jar cardrail-host.jar $1 >> logs/admin.log 2>&1"; }
phone_state() { ssh "$PHONE" "echo $1 > ~/cardrail/cell-state"; }

case "$BACK" in
    a)
        say "the phone copies everything from the laptop, which keeps serving"
        phone_admin rejoin
        say "the laptop stops serving"
        laptop stop rails
        laptop_admin release
        say "the phone takes the last rows and its half"
        phone_admin resume
        phone_state NORMAL
        laptop_state NORMAL
        laptop start rails
        ssh "$PHONE" 'sh ~/cardrail/backseat.sh restart'
        ;;
    b)
        say "the laptop copies everything from the phone, which keeps serving"
        laptop_admin rejoin
        say "the phone stops serving"
        ssh "$PHONE" 'sh ~/cardrail/backseat.sh stop'
        phone_admin release
        say "the laptop takes the last rows and its half"
        laptop_admin resume
        laptop_state NORMAL
        phone_state NORMAL
        laptop restart rails
        ssh "$PHONE" 'sh ~/cardrail/backseat.sh start'
        ;;
    *) echo "a or b"; exit 2 ;;
esac

say "replication both ways"
laptop_admin replicate || echo "the phone isn't publishing yet; its starter subscribes when it is"
say "done: both cells NORMAL; check with deploy/measure.sh and the status pages"
