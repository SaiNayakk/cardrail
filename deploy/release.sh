#!/bin/sh
# Deploys a tagged release to both live cells (ADR-015), from the laptop:
#
#   deploy/release.sh v0.6.0
#
# 1. Checks: the tag is on main, Sprout's witness shows both regions up, and both devices have the memory.
# 2. Builds the release from the tag alone (a clean worktree): the host jar, and the images the laptop runs.
# 3. Copies the phone's files to it (the jar, the web app, the starter and its settings; secrets only by file).
# 4. Migrates every schema in both cells before either starts the release (rows replicate, DDL doesn't).
# 5. Restarts the laptop's cell, then the phone's, then makes sure each copies the other.
#
# Nothing here prints a secret. Stop at the first failure: the cells keep running the release they had.
set -eu
TAG=${1:?usage: deploy/release.sh vX.Y.Z}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WITNESS=https://sprout-witness.sai31nayak.workers.dev/status
PHONE=${CARDRAIL_PHONE_SSH:-phone}
say() { printf '\n== %s\n' "$*"; }
cd "$ROOT"

say "checks"
git fetch -q --tags origin
git merge-base --is-ancestor "refs/tags/$TAG" origin/main || { echo "$TAG is not on main: never deploy unmerged code"; exit 1; }
curl -fsS -m 15 "$WITNESS" | python3 -c '
import json, sys
s = json.load(sys.stdin)
up = all(r["status"] == "up" for r in s["regions"])
print("witness:", s["overall"], [(r["id"], r["status"]) for r in s["regions"]])
sys.exit(0 if s["overall"] == "ok" and up else "not deploying while a Sprout region is down or held by the other")'
phone_free=$(ssh -o ConnectTimeout=15 "$PHONE" "free -m | awk 'NR==2 {print \$7}'")
# the phone keeps 1.2 GB available; a first deploy must also leave room for cardrail's 400 MB
need=1250; ssh "$PHONE" 'test -f ~/cardrail/cardrail-host.jar' || need=1650
echo "phone: $phone_free MB available, $need needed"
[ "$phone_free" -ge "$need" ] || { echo "not enough memory on the phone"; exit 1; }
docker info >/dev/null

say "build $TAG"
WORK=$(mktemp -d)
trap 'git worktree remove --force "$WORK" 2>/dev/null || true' EXIT
git worktree add -q --detach "$WORK" "refs/tags/$TAG"
(cd "$WORK" && ./mvnw -B -q -DskipTests install)
docker build -q -f "$WORK/preprod/Dockerfile.host" -t "cardrail-host:$TAG" "$WORK"
docker build -q -t "cardrail-web:$TAG" "$WORK/web"
# the web app's files for the phone's edge, from the image just built
rm -rf "$WORK/phone-web" && mkdir -p "$WORK/phone-web"
docker run --rm "cardrail-web:$TAG" tar -C /usr/share/nginx/html -cf - . | tar -C "$WORK/phone-web" -xf -

say "copy to the phone"
ssh "$PHONE" 'mkdir -p ~/cardrail/web ~/cardrail/logs ~/cardrail/tunnel && chmod 700 ~/cardrail'
scp -q "$WORK/host/target/cardrail-host.jar" "$PHONE:cardrail/cardrail-host.jar.new"
for f in deploy/phone/start.sh deploy/phone/backseat.sh deploy/phone/cell.env deploy/phone/nginx.conf deploy/edge.sh; do
    git show "refs/tags/$TAG:$f" | ssh "$PHONE" "cat > cardrail/$(basename "$f")"
done
git show "refs/tags/$TAG:web/nginx/default.conf.template" | ssh "$PHONE" 'cat > cardrail/edge.conf.template'
tar -C "$WORK/phone-web" -cf - . | ssh "$PHONE" 'rm -rf ~/cardrail/web.new && mkdir ~/cardrail/web.new && tar -C ~/cardrail/web.new -xf -'
scp -q deploy/phone/secrets.env "$PHONE:cardrail/secrets.env"
scp -q -r deploy/phone/tunnel/. "$PHONE:cardrail/tunnel/"
ssh "$PHONE" 'chmod 600 ~/cardrail/secrets.env ~/cardrail/tunnel/*'

say "migrate both cells"
# shellcheck disable=SC2016
ssh "$PHONE" 'cd ~/cardrail && set -a && . ./cell.env && . ./secrets.env && set +a && java -XX:+UseSerialGC -Xmx64m -jar cardrail-host.jar.new migrate >> logs/admin.log 2>&1' \
    || { echo "the phone's migration failed: see ~/cardrail/logs/admin.log there"; exit 1; }
CARDRAIL_VERSION=$TAG docker compose -f deploy/laptop/compose.yml run --rm --no-deps -e XMX=64m rails migrate

say "restart the laptop's cell"
CARDRAIL_VERSION=$TAG docker compose -f deploy/laptop/compose.yml up -d --wait

say "restart the phone's cell"
ssh "$PHONE" 'cd ~/cardrail && mv cardrail-host.jar.new cardrail-host.jar && rm -rf web && mv web.new web'
ssh "$PHONE" 'sh ~/cardrail/backseat.sh restart'

say "replication both ways"
CARDRAIL_VERSION=$TAG docker compose -f deploy/laptop/compose.yml run --rm --no-deps -e XMX=64m rails replicate || echo "the phone isn't publishing yet; its starter subscribes when it is"

say "$TAG deployed; check memory with deploy/measure.sh"
