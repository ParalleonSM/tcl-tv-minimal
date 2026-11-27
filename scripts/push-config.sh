#!/usr/bin/env bash
# push-config.sh — send config/config.json (theme, layout, pinned/hidden apps, rows, JARVIS on/off, commands) to the
# launcher on the TV. It applies at once: no rebuild, no reinstall. Read the TV's current one back with --pull.
#
#   scripts/push-config.sh --ip 192.168.1.50                    # send config/config.json
#   scripts/push-config.sh --ip 192.168.1.50 my-config.json     # send another file
#   scripts/push-config.sh --ip 192.168.1.50 --pull             # print the config the TV is using now
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"
FILE="$TTM_ROOT/config/config.json"
PULL=0
parse_common "$@"
for a in ${REST[@]+"${REST[@]}"}; do
  case "$a" in
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common; echo "  --pull              Print the TV's current config." >&3; exit 0 ;;
    --pull) PULL=1 ;;
    -*) die "Unknown option: $a (see --help)" 2 ;;
    *) FILE="$a" ;;
  esac
done
resolve_tv
tv_connect
if [ "$PULL" -eq 1 ]; then
  sh_read '{ "jarvis": { "enabled": true } }' content read --uri "$CONTROL_URI/config.json"
  exit 0
fi
[ -f "$FILE" ] || die "No such file: $FILE" 2
push_config "$FILE"
