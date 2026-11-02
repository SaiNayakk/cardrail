#!/bin/sh
# A live cell's public edge (ADR-015): nginx with the web app, forwarding each API to whichever cell runs it now.
# It re-reads the cell's state every 5 s and reloads nginx when the answer changes:
#   NORMAL      its own half here, the other half at the other cell
#   HOLDING     everything here (this cell took the other over)
#   TAKEN_OVER  everything at the other cell (it holds this cell's half)
#
# CELL (a: banks, b: rails), LOCAL (where this cell's JVM listens), PEER (the other cell's address on the home
# network), STATE_FILE, TEMPLATE, CONF, and NGINX (the command, with its prefix and config) come from the caller;
# LISTEN and ROOT, where nginx listens and the web app's files are, only where they differ from the image's.
set -eu

render() {
    state=$(cat "$STATE_FILE" 2>/dev/null || echo NORMAL)
    case "$state" in
        HOLDING) banks=$LOCAL rails=$LOCAL ;;
        TAKEN_OVER) banks=$PEER rails=$PEER ;;
        *) if [ "$CELL" = a ]; then banks=$LOCAL rails=$PEER; else banks=$PEER rails=$LOCAL; fi ;;
    esac
    sed -e "s#\${ACQUIRING}#$rails#g" -e "s#\${NETWORK}#$rails#g" \
        -e "s#\${ISSUING}#$banks#g" -e "s#\${MERIDIAN}#$banks#g" \
        -e "s#listen 80;#listen ${LISTEN:-80};#" -e "s#/usr/share/nginx/html#${ROOT:-/usr/share/nginx/html}#" "$TEMPLATE"
}

render > "$CONF"
$NGINX -t -q
$NGINX
last=$(render)
while sleep 5; do
    next=$(render)
    if [ "$next" != "$last" ]; then
        echo "$next" > "$CONF"
        if $NGINX -t -q && $NGINX -s reload; then
            echo "edge: now routing for $(cat "$STATE_FILE" 2>/dev/null || echo NORMAL)"
            last=$next
        fi
    fi
done
