#!/data/data/com.termux/files/usr/bin/sh
# cardrail's one Backseat app on the phone, `cardrail` (start.sh in ~/cardrail), through the agent on this phone.
#   sh backseat.sh register|start|stop|restart
# Touches no other app: Sprout's are its own.
set -eu
AGENT=http://127.0.0.1:8080
token=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.backseat/agent/token.json")))["session_token"])')
agent() { curl -s -m 60 -X "$1" -H "x-backseat-token: $token" -H "content-type: application/json" "$AGENT$2" ${3:+-d "$3"}; }
registered() { curl -s -m 10 "$AGENT/public/status" | python3 -c "import json,sys; sys.exit(0 if any(a['name']=='cardrail' for a in json.load(sys.stdin)['apps']) else 1)"; }

case "${1:?register|start|stop|restart}" in
    register)
        if registered; then echo "cardrail is registered"; else
            agent POST /apps "{\"name\":\"cardrail\",\"command\":\"sh start.sh\",\"cwd\":\"$HOME/cardrail\"}" >/dev/null
            echo "registered cardrail"
        fi ;;
    restart)
        registered || "$0" register
        agent POST /apps/cardrail/restart >/dev/null && echo "cardrail restarting" ;;
    start|stop)
        agent POST "/apps/cardrail/$1" >/dev/null && echo "cardrail: $1" ;;
esac
