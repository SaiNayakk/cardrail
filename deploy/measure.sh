#!/bin/sh
# What cardrail holds in memory on both live cells, for the caps agreed with Sprout: the phone at most 400 MB (the
# JVM plus cardrail's Postgres backends), the laptop at most 700 MB of Docker.
#   deploy/measure.sh
set -eu
PHONE=${CARDRAIL_PHONE_SSH:-phone}

echo "== laptop (Docker)"
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | grep '^cardrail-live' || echo "not running"

echo "== phone"
# shellcheck disable=SC2016
ssh -o ConnectTimeout=15 "$PHONE" '
jvm=$(ps -eo rss,args | awk "/cardrail-host.jar/ && !/awk/ {s+=\$1} END {print int(s/1024)}")
backends=$(ps -eo rss,args | awk "/postgres: cardrail cardrail/ && !/awk/ {s+=\$1; n++} END {print int(s/1024) \" MB in \" n+0}")
edge=$(ps -eo rss,args | awk "/cardrail\/nginx.conf|cardrail\/edge/ && !/awk/ {s+=\$1} END {print int(s/1024)}")
front=$(ps -eo rss,args | awk "/cardrail\/tunnel/ && !/awk/ {s+=\$1} END {print int(s/1024)}")
echo "JVM ${jvm} MB, Postgres backends ${backends}, edge ${edge} MB, connector ${front} MB"
echo "state $(cat ~/cardrail/cell-state 2>/dev/null || echo NORMAL); available $(free -m | awk "NR==2 {print \$7}") MB"'
