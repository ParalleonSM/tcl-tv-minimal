#!/usr/bin/env bash
# rollback.sh — put the TV back the way it was: every package debloat.sh turned off comes back on, the stock Google TV
# home screen (and its setup app and screensaver) comes back as Home, and the launcher is uninstalled.
#
#   1. Turns back on what debloat.sh recorded (on the TV and on this Mac) — only what is still off, nothing else.
#   2. Launcher: the safe way is Free My TV's own "Original" (it knows exactly what its switch turned off and restores
#      it in the right order). This script opens that screen and waits. If the TV can't do that (black screen, app
#      gone, app crashing), --force does it from this Mac instead, in the same order Free My TV uses: Google TV home
#      on and set as Home first, the setup app last, then the screensaver and the remote-button service.
#   3. Uninstalls the launcher — only once the stock home is Home again (--keep-launcher to keep it installed).
#   4. Compares the package states with the snapshot detect.sh took before anything changed.
#
#   scripts/rollback.sh --ip 192.168.1.50 --dry-run
#   scripts/rollback.sh --ip 192.168.1.50
#   scripts/rollback.sh --ip 192.168.1.50 --force          # the TV can't open Free My TV's settings
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"

FORCE=0
KEEP=0
WAIT_S=600
parse_common "$@"
set -- ${REST[@]+"${REST[@]}"}
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common; cat >&3 <<EOF
  --force             Restore the stock home from this Mac instead of the launcher's 'Original' button.
  --keep-launcher     Leave the launcher app installed (in 'Original' mode it changes nothing).
  --wait SECONDS      How long to wait for 'Original' on the TV (default 600).
EOF
      exit 0 ;;
    --force) FORCE=1 ;;
    --keep-launcher) KEEP=1 ;;
    --wait) [ $# -ge 2 ] || die "--wait needs seconds" 2; WAIT_S="$2"; shift ;;
    *) die "Unknown option: $1 (see --help)" 2 ;;
  esac
  shift
done
resolve_tv
tv_connect
classify_tv
say "  $TV_BRAND $TV_MODEL, Android $TV_ANDROID ($TV_KIND)"
SD="$(state_dir)"
FAILED=""

# pm enable <pkg> if it is disabled now; prints the result. Returns 1 on an unexpected answer.
enable_if_off() {
  local p="$1" disabled="$2" out
  if ! in_list "$p" "$disabled"; then say "  on    $p — already on"; return 0; fi
  out="$(tv_do shell pm enable "$p")"
  is_dry && return 0
  case "$out" in
    *"new state: enabled"*) ok "on    $p"; return 0 ;;
    *) warn "could not turn $p back on: $out"; FAILED="$FAILED $p"; return 1 ;;
  esac
}

step "1/4  Turning back on what debloat.sh turned off"
REC="$(tv_record 'com.tcl.esticker,com.tcl.exhibit')"
if ! is_dry && [ -f "$SD/debloat-disabled.txt" ]; then REC="$(printf '%s\n%s\n' "$REC" "$(cat "$SD/debloat-disabled.txt")")"; fi
REC="$(printf '%s\n' "$REC" | grep -E "$PKG_RE" | sort -u || true)"
if [ -z "$REC" ]; then
  say "  nothing recorded (debloat.sh never ran on this TV, or it was already rolled back)"
else
  DIS="$(pkgs -d "$(printf '%s\n' "$REC" | tr '\n' ' ')")"
  LEFT=""
  for p in $REC; do enable_if_off "$p" "$DIS" || LEFT="$LEFT
$p"; done
  save_tv_record "$LEFT"
  if ! is_dry; then printf '%s\n' "$LEFT" | sed '/^$/d' > "$SD/debloat-disabled.txt"; fi
fi

step "2/4  Home screen"
HP="$(home_package "$LAUNCHER_PKG")"
INSTALLED="$(pkgs '' "$LAUNCHER_PKG $GOOGLE_HOME_PKG")"
if [ "$HP" != "$LAUNCHER_PKG" ] && ! is_dry; then
  ok "Home already opens ${HP:-?} (not the launcher)"
elif [ "$FORCE" -eq 0 ] && in_list "$LAUNCHER_PKG" "$INSTALLED"; then
  tv_do shell am start -n "$LAUNCHER_PKG/.SettingsActivity" >/dev/null
  cat >&3 <<EOF
  The Free My TV settings screen is open on the TV. With the remote choose:  Home Screen  >  Original.
  It turns everything its switch changed back on and gives Home back to Google TV. This Mac waits up to $((WAIT_S / 60)) min.
  (Screen black or the app won't open? Ctrl-C, then: scripts/rollback.sh --ip ${TV%:5555} --force)
EOF
  waited=0
  while :; do
    HP="$(home_package "$GOOGLE_HOME_PKG")"
    [ "$HP" != "$LAUNCHER_PKG" ] && [ -n "$HP" ] && break
    is_dry && break
    [ "$waited" -ge "$WAIT_S" ] && die "Still the launcher after $((WAIT_S / 60)) min. Run again with --force." 6
    nap 5; waited=$((waited + 5))
  done
  ok "Original: Home opens $HP"
else
  [ "$FORCE" -eq 1 ] || warn "The launcher is Home but isn't installed: restoring from this Mac."
  say "  Restoring from this Mac, in Free My TV's own order (HomeMode.applyOriginal)."
  FREC="$(sh_read "com.google.android.apps.tv.launcherx,$GOOGLE_SETUP,com.tcl.esticker" settings get global "$FMTV_RECORD_KEY" | head -n 1)"
  [ "$FREC" = null ] && FREC=""
  FREC="$(printf '%s\n' "$FREC" | tr ',' '\n' | grep -E "$PKG_RE" || true)"
  DIS="$(pkgs -d "$(printf '%s\n' "$FREC" | tr '\n' ' ') $GOOGLE_HOME_PKG")"
  # a. Google TV's home on, and Home first.
  enable_if_off "$GOOGLE_HOME_PKG" "$DIS" || true
  out="$(tv_do shell cmd package set-home-activity "$GOOGLE_HOME")"
  is_dry || case "$out" in *Success*) ok "Home -> $GOOGLE_HOME" ;; *) warn "set-home-activity: $out"; FAILED="$FAILED home" ;; esac
  # b. Everything else Free My TV's switch turned off, but the setup app.
  for p in $FREC; do
    case "$p" in "$GOOGLE_HOME_PKG"|"$GOOGLE_SETUP") continue ;; esac
    enable_if_off "$p" "$DIS" || true
  done
  # c. The setup app last, once Home is back (alone its RecoveryActivity would take Home); then Home again.
  if in_list "$GOOGLE_SETUP" "$FREC" || in_list "$GOOGLE_SETUP" "$DIS"; then
    enable_if_off "$GOOGLE_SETUP" "$DIS" || true
    tv_do shell cmd package set-home-activity "$GOOGLE_HOME" >/dev/null
  fi
  # d. Screensaver: back to the TV's default when ours is set.
  SAVER="$(sh_read "$LAUNCHER_PKG/.Screensaver" settings get secure screensaver_components | head -n 1)"
  case "$SAVER" in
    *"$LAUNCHER_PKG/"*)
      DEF="$(sh_read 'com.google.android.apps.tv.dreamx/.DreamService' settings get secure screensaver_default_component | head -n 1)"
      if [ -n "$DEF" ] && [ "$DEF" != null ]; then tv_do shell settings put secure screensaver_components "$DEF" >/dev/null; ok "screensaver -> $DEF"
      else tv_do shell settings delete secure screensaver_components >/dev/null; ok "screensaver -> the TV's default"; fi ;;
  esac
  # e. The remote-button accessibility service.
  ACC="$(sh_read "$LAUNCHER_PKG/.RemoteKeyService" settings get secure enabled_accessibility_services | head -n 1)"
  case "$ACC" in
    *"$LAUNCHER_PKG/"*)
      KEEP_ACC="$(printf '%s\n' "$ACC" | tr ':' '\n' | grep -v "^$LAUNCHER_PKG/" | paste -sd: - || true)"
      if [ -n "$KEEP_ACC" ]; then tv_do shell settings put secure enabled_accessibility_services "$KEEP_ACC" >/dev/null
      else tv_do shell settings delete secure enabled_accessibility_services >/dev/null; fi
      ok "remote-button service off" ;;
  esac
  tv_do shell settings delete global "$FMTV_RECORD_KEY" >/dev/null
  # The launcher's own state still says "Free My TV mode", and its boot check would switch again: it goes in step 3.
  [ "$KEEP" -eq 1 ] && { warn "--force restores from outside the app, so it is uninstalled anyway (its boot check would switch back)."; KEEP=0; }
fi

step "3/4  The launcher app"
HP="$(home_package "$GOOGLE_HOME")"
GDIS="$(pkgs -d '')"
if [ "$KEEP" -eq 1 ]; then
  say "  kept installed (--keep-launcher); it's in 'Original' mode and changes nothing"
elif ! in_list "$LAUNCHER_PKG" "$INSTALLED"; then
  say "  not installed"
elif [ "$HP" = "$LAUNCHER_PKG" ] || in_list "$GOOGLE_HOME_PKG" "$GDIS"; then
  die "Not uninstalling: Home still opens the launcher or Google TV's home is off. Uninstalling now would leave no
      home screen. Run again with --force." 6
elif confirm "Uninstall the launcher app (back exactly as before)?"; then
  out="$(tv_do uninstall "$LAUNCHER_PKG")"
  is_dry || case "$out" in *Success*) ok "uninstalled" ;; *) warn "uninstall: $out" ;; esac
fi

step "4/4  Checking"
tv_do shell input keyevent KEYCODE_HOME >/dev/null
is_dry || nap 3
TOP="$(top_activity "$GOOGLE_HOME")"
case "$TOP" in
  "$GOOGLE_HOME_PKG"/*) ok "Home opens the stock Google TV home ($TOP)" ;;
  *) is_dry || warn "front screen: ${TOP:-?} — press Home on the remote once" ;;
esac
if ! is_dry; then
  NOW="$(pkgs -d '')"
  if [ -f "$SD/baseline-disabled.txt" ]; then
    EXTRA="$(printf '%s\n' "$NOW" | grep -vxF -f "$SD/baseline-disabled.txt" | sed '/^$/d' || true)"
    MISSING="$(grep -vxF -f <(printf '%s\n' "$NOW") "$SD/baseline-disabled.txt" | sed '/^$/d' || true)"
    if [ -z "$EXTRA" ] && [ -z "$MISSING" ]; then ok "disabled packages match the snapshot from before (state/…/baseline-disabled.txt)"
    else
      [ -n "$EXTRA" ] && warn "off now but on before: $(printf '%s\n' "$EXTRA" | paste -sd' ' -)"
      [ -n "$MISSING" ] && warn "on now but off before: $(printf '%s\n' "$MISSING" | paste -sd' ' -)"
    fi
  else
    say "  no 'before' snapshot on this Mac (detect.sh wasn't run from here) — skipped the comparison"
  fi
fi
if [ -n "$FAILED" ]; then
  die "Some things didn't come back:$FAILED. Try again, or see DEPLOY.md > Last resort." 7
fi
ok "Rolled back."
