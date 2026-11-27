#!/usr/bin/env bash
# debloat.sh — turn off the verified-safe bloatware list, one package at a time.
#
# Reads config/debloat.list (or --list FILE). Each package is turned off with `pm disable-user --user 0` (still
# installed, data kept; rollback.sh turns it back on). Before anything changes, every package is checked against:
#   - Android's core pieces and factory menus (patterns in lib.sh) and config/do-not-touch.list: refused outright;
#   - the app that answers Home, and any app that answers Home: never (that's install-launcher.sh's job);
#   - the app that saved a Wi-Fi network: skipped (Android would delete the network);
#   - the software under the HDMI inputs and the remote's Input key: skipped (HDMI "No signal" otherwise).
# Each result is printed. The first unexpected answer stops the run; what was turned off so far is recorded on the TV
# (settings global tcl_tv_minimal_disabled) and on this Mac (state/<tv>/debloat-disabled.txt), so rollback.sh can
# undo exactly that.
#
#   scripts/debloat.sh --ip 192.168.1.50 --dry-run     # see exactly what it would do
#   scripts/debloat.sh --ip 192.168.1.50
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"

LIST="$TTM_ROOT/config/debloat.list"
ALLOW_ANDROID_TV=0
parse_common "$@"
set -- ${REST[@]+"${REST[@]}"}
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common
      echo "  --list FILE         Another list (same format as config/debloat.list)." >&3; exit 0 ;;
    --list) [ $# -ge 2 ] || die "--list needs a file" 2; LIST="$2"; shift ;;
    --allow-android-tv) ALLOW_ANDROID_TV=1 ;;
    *) die "Unknown option: $1 (see --help)" 2 ;;
  esac
  shift
done

step "Reading $(basename "$LIST")"
WANT="$(read_list "$LIST")"
[ -n "$WANT" ] || die "$LIST has no active package lines (all commented out?). Nothing to do." 2
REFUSED=""
for p in $WANT; do
  if why="$(forbidden "$p")"; then REFUSED="$REFUSED
  $p: $why"; fi
done
[ -z "$REFUSED" ] || die "Refusing the whole list — it names packages that must never be turned off:$REFUSED
Take those lines out of $LIST. Nothing was sent to the TV." 2
ok "$(printf '%s\n' "$WANT" | wc -l | tr -d ' ') packages, none on the never-touch list"

resolve_tv
tv_connect
require_google_tv

step "Safety checks on the TV"
# Placeholders for --dry-run: a TV that has most of the list installed, none of it disabled.
DRY_ALL="$(printf '%s\n' "$WANT" | tr '\n' ' ') $GOOGLE_HOME_PKG"
INSTALLED="$(pkgs '' "$DRY_ALL")"
[ -n "$INSTALLED" ] || die "The TV listed no packages at all. Try again in a minute." 4
DISABLED_BEFORE="$(pkgs -d '')"

# 1. Home: whatever answers Home stays on (CleanYourTV disable.sh "Home guard", #239).
HOME_PKG="$(home_package "$GOOGLE_HOME")"
HOME_CLAIMS="$(sh_read "$GOOGLE_HOME" cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | grep -oE '^ *[A-Za-z0-9_.]+/' | tr -d ' /' | sort -u || true)"
[ -n "$HOME_PKG" ] || is_dry || die "Couldn't tell which app answers Home. Not changing anything." 4
ok "Home is $HOME_PKG; apps that answer Home are kept: $(printf '%s\n' "$HOME_CLAIMS" | paste -sd' ' -)"

# 2. Wi-Fi owners (Free My TV HomeMode.wifiOwners; CleanYourTV disable.sh): fail closed if Wi-Fi can't be read.
WIFI_ON="$(sh_read 1 settings get global wifi_on | head -n 1)"
OWNERS=""
if [ "$WIFI_ON" != 0 ]; then
  WIFI="$(sh_read 'cname=android.uid.system' dumpsys wifi)"
  OWNERS="$(printf '%s\n' "$WIFI" | grep -oE 'cname=[A-Za-z0-9_.]+' | sed 's/^cname=//' | sort -u || true)"
  [ -n "$OWNERS" ] || die "Couldn't read which apps saved the Wi-Fi networks (dumpsys wifi). Not changing anything:
      a package that saved your network would take the TV off Wi-Fi. Try again in a minute." 4
fi
ok "Wi-Fi networks saved by: $(printf '%s\n' "$OWNERS" | paste -sd' ' -)"

# 3. Input hardware and the remote's Input key (Free My TV HomeMode.neededByTv; CleanYourTV #321): one round trip.
CHECK="$(printf '%s\n' "$WANT" | while read -r p; do in_list "$p" "$INSTALLED" && printf '%s ' "$p"; done || true)"
NEEDED=""
if [ -n "$CHECK" ]; then
  DRY_HW="$(for p in $CHECK; do printf -- '- %s\n' "$p"; done; echo checked)"
  # shellcheck disable=SC2016   # $p, $d are expanded by the TV's shell, not here
  HW="$(sh_read "$DRY_HW" "for p in $CHECK; do d=\"\$(dumpsys package \$p)\"; if ! printf '%s\n' \"\$d\" | grep -q -F \"Package [\$p]\"; then echo \"unknown \$p\"; elif printf '%s\n' \"\$d\" | sed -n -r '/requested permissions:/,/(install|runtime) permissions:|User [0-9]+:/p' | grep -q TV_INPUT_HARDWARE || printf '%s\n' \"\$d\" | grep -q -E 'TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory'; then echo \"keep \$p\"; else echo \"- \$p\"; fi; done; echo checked")"
  printf '%s\n' "$HW" | grep -q '^checked' || die "The input-hardware check didn't finish. Not changing anything." 4
  NEEDED="$(printf '%s\n' "$HW" | sed -n 's/^keep //p')"
  UNKNOWN="$(printf '%s\n' "$HW" | sed -n 's/^unknown //p')"
  [ -z "$UNKNOWN" ] || die "Couldn't read the package info of: $UNKNOWN (dumpsys may be off after a hang — restart the TV).
      Not changing anything." 4
fi
ok "input-hardware check done${NEEDED:+ — kept on: $(printf '%s\n' "$NEEDED" | paste -sd' ' -)}"

step "Turning packages off ($(printf '%s\n' "$WANT" | wc -l | tr -d ' ') on the list)"
SD="$(state_dir)"
LOCAL_REC="$SD/debloat-disabled.txt"
RECORD="$(tv_record '')"
if ! is_dry && [ -f "$LOCAL_REC" ]; then RECORD="$(printf '%s\n%s\n' "$RECORD" "$(cat "$LOCAL_REC")" | grep -E "$PKG_RE" | sort -u)"; fi
n_off=0; n_skip=0
for p in $WANT; do
  if ! in_list "$p" "$INSTALLED"; then say "  skip  $p — not on this TV"; n_skip=$((n_skip + 1)); continue; fi
  if in_list "$p" "$DISABLED_BEFORE"; then say "  skip  $p — already off (left as it is; not recorded)"; n_skip=$((n_skip + 1)); continue; fi
  if [ "$p" = "$HOME_PKG" ] || in_list "$p" "$HOME_CLAIMS"; then warn "skip  $p — it answers Home"; n_skip=$((n_skip + 1)); continue; fi
  if in_list "$p" "$OWNERS"; then warn "skip  $p — it saved a Wi-Fi network (forget + rejoin the network in Settings first)"; n_skip=$((n_skip + 1)); continue; fi
  if in_list "$p" "$NEEDED"; then warn "skip  $p — the TV's inputs or remote need it"; n_skip=$((n_skip + 1)); continue; fi
  out="$(tv_do shell pm disable-user --user 0 "$p")"
  if is_dry; then n_off=$((n_off + 1)); continue; fi
  case "$out" in
    *"new state: disabled-user"*)
      ok "off   $p"
      n_off=$((n_off + 1))
      RECORD="$(printf '%s\n%s\n' "$RECORD" "$p" | grep -E "$PKG_RE" | sort -u)"
      printf '%s\n' "$RECORD" > "$LOCAL_REC"
      save_tv_record "$RECORD" ;;
    *)
      die "Unexpected answer for $p: $out
      Stopped here. Turned off so far in this run: $n_off (recorded; scripts/rollback.sh undoes them)." 5 ;;
  esac
done

step "Done"
if is_dry; then
  say "  (dry-run) would turn off up to $n_off packages; $n_skip skipped by the checks above. Nothing was changed."
else
  ok "$n_off turned off, $n_skip skipped. Undo any time: scripts/rollback.sh --ip ${TV%:5555}"
  say "  Record on the TV: settings get global $RECORD_KEY   |   on this Mac: $LOCAL_REC"
fi
