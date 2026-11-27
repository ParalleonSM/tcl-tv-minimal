#!/usr/bin/env bash
# tvctl.sh — drive the launcher's command layer from this Mac (by hand, or from a companion agent).
#
# Every action in the launcher's registry (config.json "commands", plus the built-ins) can be run by name, and JARVIS's
# phrase matcher can be fed text as if it was spoken. Replies are JSON on stdout (one line), so an agent can parse them.
#
#   scripts/tvctl.sh --ip 192.168.1.50 status                     # {"ok":true,"say":"…","volume":…,…}
#   scripts/tvctl.sh --ip 192.168.1.50 list                       # every registered action and its phrases
#   scripts/tvctl.sh --ip 192.168.1.50 run launch_app app=netflix
#   scripts/tvctl.sh --ip 192.168.1.50 run hdmi input=2
#   scripts/tvctl.sh --ip 192.168.1.50 run volume step=-3
#   scripts/tvctl.sh --ip 192.168.1.50 say "switch to HDMI 2"     # matched like a spoken command
#   scripts/tvctl.sh --ip 192.168.1.50 speak "Good morning, sir." # JARVIS says it (TTS on the TV)
#   scripts/tvctl.sh --ip 192.168.1.50 jarvis on|off              # the JARVIS layout toggle (saved in config.json)
#   scripts/tvctl.sh --ip 192.168.1.50 listen                     # open JARVIS's voice mode on the TV
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"
parse_common "$@"
set -- ${REST[@]+"${REST[@]}"}
case "${1:-}" in -h|--help|"") sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common; exit 0 ;; esac

# JSON string escape (backslash, quote, control chars) — enough for command text.
jstr() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\t' '  ')"; }

VERB="$1"; shift
case "$VERB" in
  status) CMD='{"action":"status"}' ;;
  list) CMD='{"action":"list"}' ;;
  run)
    [ $# -ge 1 ] || die "run <action> [key=value ...]" 2
    CMD="{\"action\":$(jstr "$1")"; shift
    for kv in "$@"; do
      case "$kv" in *=*) ;; *) die "arguments are key=value (got $kv)" 2 ;; esac
      CMD="$CMD,$(jstr "${kv%%=*}"):$(jstr "${kv#*=}")"
    done
    CMD="$CMD}" ;;
  say) [ $# -ge 1 ] || die "say \"<text>\"" 2; CMD="{\"action\":\"utterance\",\"text\":$(jstr "$*")}" ;;
  speak) [ $# -ge 1 ] || die "speak \"<text>\"" 2; CMD="{\"action\":\"speak\",\"text\":$(jstr "$*")}" ;;
  jarvis)
    case "${1:-}" in on) CMD='{"action":"jarvis","enabled":"true"}' ;; off) CMD='{"action":"jarvis","enabled":"false"}' ;;
      *) die "jarvis on|off" 2 ;; esac ;;
  listen) CMD='{"action":"listen"}' ;;
  *) die "Unknown command: $VERB (see --help)" 2 ;;
esac
resolve_tv
if ! is_dry && [ "$("$ADB" -s "$TV" get-state 2>/dev/null | tr -d '\r')" != device ]; then tv_connect >/dev/null; fi
REPLY_JSON="$(control_call "$CMD" '{"ok":true,"dry_run":true}')"
printf '%s\n' "$REPLY_JSON"
case "$REPLY_JSON" in *'"ok":true'*) exit 0 ;; *) exit 1 ;; esac
