#!/usr/bin/env bash
# install-launcher.sh — install the minimalist launcher, prove it runs, and only then make it the home screen.
#
# Order (a requirement, not a suggestion — the stock launcher is never touched before the new one is proven):
#   1. install the APK (adb install -r) and check the package is there;
#   2. allow it to run in the background (TCL blocks that by default);
#   3. send config/config.json to it (theme, apps, JARVIS on/off);
#   4. open it, check it is on screen and answers its command layer, then press Home: the stock home must come back;
#   5. only then the switch — done ON THE TV by Free My TV's own switch (Home Screen -> Free My TV), which turns off
#      the Google TV home, its setup app and screensaver in the safe order, with its own checks (inputs, Wi-Fi owner)
#      and a watchdog that switches back after 3 crashes in 5 minutes. This script opens that screen and waits;
#   6. checks Home now opens the new launcher.
# The stock launcher is never disabled from this Mac: Free My TV's switch does it, after step 4 proved the launcher.
#
#   scripts/install-launcher.sh --ip 192.168.1.50 --dry-run
#   scripts/install-launcher.sh --ip 192.168.1.50                       # the JARVIS build (JARVIS on, one-tap off)
#   scripts/install-launcher.sh --ip 192.168.1.50 --apk release/TCLMinimal-classic.apk
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"

APK="$TTM_ROOT/release/TCLMinimal.apk"
CONFIG="$TTM_ROOT/config/config.json"
SWITCH=1
WAIT_S=600
ALLOW_ANDROID_TV=0
parse_common "$@"
set -- ${REST[@]+"${REST[@]}"}
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common; cat >&3 <<EOF
  --apk FILE          APK to install (default release/TCLMinimal.apk).
  --config FILE       Launcher config to send (default config/config.json); --config none sends nothing.
  --no-switch         Install and check only; leave the stock home screen as Home.
  --wait SECONDS      How long to wait for the switch on the TV (default 600).
EOF
      exit 0 ;;
    --apk) [ $# -ge 2 ] || die "--apk needs a file" 2; APK="$2"; shift ;;
    --config) [ $# -ge 2 ] || die "--config needs a file" 2; CONFIG="$2"; shift ;;
    --no-switch) SWITCH=0 ;;
    --wait) [ $# -ge 2 ] || die "--wait needs seconds" 2; WAIT_S="$2"; shift ;;
    --allow-android-tv) ALLOW_ANDROID_TV=1 ;;
    *) die "Unknown option: $1 (see --help)" 2 ;;
  esac
  shift
done
if [ ! -f "$APK" ]; then
  if is_dry; then warn "APK $APK not found (dry-run goes on)"; else die "APK not found: $APK" 2; fi
fi
[ "$CONFIG" = none ] || [ -f "$CONFIG" ] || die "Config not found: $CONFIG (or --config none)" 2

resolve_tv
tv_connect
require_google_tv

step "1/6  Installing $(basename "$APK")"
BEFORE="$(sh_read '' dumpsys package "$LAUNCHER_PKG" | grep -m1 -oE 'versionName=[^ ]+' || true)"
[ -n "$BEFORE" ] && say "  already installed ($BEFORE): updating in place, settings kept"
say "  If this seems stuck, look at the TV: Google Play Protect may ask. Choose 'More details' > 'Install anyway'."
OUT="$(tv_do install -r "$APK")"
if ! is_dry; then
  case "$OUT" in
    *Success*) ok "installed" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
      die "A Free My TV signed with another key is already on the TV (the official release, or another build).
      On the TV: Free My TV > Home Screen > Original (if it's the home screen now), then
      Settings > Apps > Free My TV > Uninstall. Then run this again." 5 ;;
    *) die "Install failed: $OUT" 5 ;;
  esac
fi
VER="$(sh_read 'versionName=1.5-tcl.1' dumpsys package "$LAUNCHER_PKG" | grep -m1 -oE 'versionName=[^ ]+' || true)"
[ -n "$VER" ] || die "The package isn't there after the install." 5
ok "$LAUNCHER_PKG $VER"

step "2/6  Letting it run in the background (TCL's TclAppBoot blocks that by default)"
# Free My TV README "On TCL TVs": AUTO_START on some firmware, APP_AUTO_START on others; neither on some (fine).
# `appops write-settings` saves it now: TCL app-ops reach the disk lazily, and a restart soon after the `set` drops it
# and TclAppBoot blocks the watchdog/boot check again (CleanYourTV docs/TROUBLESHOOTING.md, "App-ops reach the disk
# lazily", @ be6087056a; confirmed on a G10 C69B over three reboots, examples/tcl-g10-google-tv L204-207 — the
# platform of the owner's 50Q651G).
AO="$(tv_do shell "appops set $LAUNCHER_PKG AUTO_START allow || appops set $LAUNCHER_PKG APP_AUTO_START allow; appops write-settings")"
case "$AO" in *"Unknown operation"*) say "  this firmware has no auto-start block (nothing to allow)";; *) ok "allowed" ;; esac

# JARVIS's voice mode needs the microphone. Granted now so the TV never has to ask (the classic build doesn't request it).
if sh_read "uses-permission: android.permission.RECORD_AUDIO" dumpsys package "$LAUNCHER_PKG" | grep -q 'android.permission.RECORD_AUDIO'; then
  MIC="$(tv_do shell pm grant "$LAUNCHER_PKG" android.permission.RECORD_AUDIO)"
  if [ -z "$MIC" ] || is_dry; then ok "microphone allowed for JARVIS's voice mode"
  else warn "couldn't grant the microphone ($MIC); JARVIS will ask on the TV the first time"; fi
fi

step "3/6  Sending the launcher config"
if [ "$CONFIG" = none ]; then
  say "  skipped (--config none): the launcher uses its built-in defaults (JARVIS on)"
else
  push_config "$CONFIG"
fi

step "4/6  Proving it runs (before it becomes Home)"
START="$(tv_do shell am start -W -n "$LAUNCHER_PKG/.HomeActivity")"
is_dry || printf '%s\n' "$START" | grep -qE 'Status: ok|Complete' || die "It didn't start: $START" 5
is_dry || nap 4
TOP="$(top_activity "$LAUNCHER_PKG/.HomeActivity")"
case "$TOP" in
  "$LAUNCHER_PKG"/*) ok "on screen: $TOP" ;;
  *) is_dry || die "It started but isn't on screen (front: ${TOP:-?}). Check the TV; nothing else was changed." 5 ;;
esac
STATUS="$(control_call '{"action":"status"}' '{"ok":true,"say":"All systems nominal."}')"
case "$STATUS" in *'"ok":true'*) ok "command layer answers: $(printf '%s' "$STATUS" | cut -c1-120)" ;;
  *) warn "command layer didn't answer as expected ($STATUS). The launcher runs; agent control may not." ;; esac
tv_do shell input keyevent KEYCODE_HOME >/dev/null
is_dry || nap 3
TOP="$(top_activity "$GOOGLE_HOME")"
case "$TOP" in
  "$LAUNCHER_PKG"/*) warn "Home already opens the new launcher (switched earlier)." ;;
  *) ok "Home still opens the stock home screen (${TOP:-$GOOGLE_HOME}): nothing switched yet" ;;
esac

if [ "$SWITCH" -eq 0 ]; then
  step "Stopping before the switch (--no-switch)"
  ok "Installed and proven. To switch later: on the TV, Free My TV > Home Screen > Free My TV."
  exit 0
fi

step "5/6  The switch — on the TV"
HP="$(home_package "$GOOGLE_HOME")"
if [ "$HP" = "$LAUNCHER_PKG" ]; then
  ok "already the home screen"
else
  tv_do shell am start -n "$LAUNCHER_PKG/.SettingsActivity" >/dev/null
  cat >&3 <<EOF
  The Free My TV settings screen is open on the TV. With the remote:
    1. Choose  Home Screen  >  Free My TV.
    2. The TV asks 'Allow USB debugging?' once more — this time for Free My TV itself.
       Tick 'Always allow' and choose Allow. (That lets it switch safely from inside the TV.)
    3. It shows 'Measuring…' and may flash the old home screen a couple of times. That's expected.
  This Mac waits up to $((WAIT_S / 60)) minutes and checks every few seconds.
EOF
  if [ "${TV_SDK:-31}" -le 29 ] 2>/dev/null && ! is_dry; then
    # Free My TV #221: on Android 8-10 the TV can hand the prompt's answer to a computer that's still connected.
    "$ADB" disconnect "$TV" >/dev/null 2>&1 || true
    say "  (Android $TV_ANDROID: this Mac disconnected so the TV's prompt goes to Free My TV. Press Enter here when"
    say "   the TV shows Free My TV's home screen.)"
    read -r _ || true
    "$ADB" connect "$TV" >/dev/null 2>&1 || true
    nap 2
  fi
  waited=0
  while :; do
    HP="$(home_package "$LAUNCHER_PKG")"
    [ "$HP" = "$LAUNCHER_PKG" ] && break
    is_dry && break
    [ "$waited" -ge "$WAIT_S" ] && die "No switch after $((WAIT_S / 60)) min (Home still opens ${HP:-?}). Nothing is broken: the
      stock home screen is still Home. See DEPLOY.md > Troubleshooting > 'The launcher doesn't stick'." 6
    case "$waited" in
      120) say "  … still waiting. On TCL, a background killer can stop the switch: wait a minute or two, then press Home." ;;
      300) say "  … still waiting. If the TV said 'forget the network and connect again', do that, then switch again." ;;
    esac
    nap 5; waited=$((waited + 5))
  done
  ok "the switch went through"
fi

step "6/6  Checking Home"
tv_do shell input keyevent KEYCODE_HOME >/dev/null
is_dry || nap 3
TOP="$(top_activity "$LAUNCHER_PKG/.HomeActivity")"
case "$TOP" in
  "$LAUNCHER_PKG"/*) ok "Home opens the new launcher ($TOP)" ;;
  *) is_dry || warn "Home is set to $LAUNCHER_PKG but the front screen is ${TOP:-?}. Press Home on the remote once." ;;
esac
GSTATE="$(pkgs -d "$GOOGLE_HOME_PKG")"
in_list "$GOOGLE_HOME_PKG" "$GSTATE" && ok "stock Google TV home is now off (Free My TV's switch did it; 'Original' brings it back)"
ok "Done. Next (optional): scripts/debloat.sh --ip ${TV%:5555}"
