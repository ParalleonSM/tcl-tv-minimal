#!/usr/bin/env bash
# detect.sh — connect to the TV over ADB and say what it is. Read-only: changes nothing on the TV.
#
# Prints model, Android version, the current launcher, and whether this is a Google TV. Stops with a clear message on
# a Roku TV or a Fire TV (the whole approach doesn't apply there). Saves a snapshot of the TV's package states to
# state/<tv>/baseline-*.txt on this Mac, the "before" picture rollback compares against.
#
#   scripts/detect.sh --ip 192.168.1.50
#   scripts/detect.sh --ip 192.168.1.50 --dry-run
set -euo pipefail
# shellcheck source=scripts/lib.sh
. "$(dirname "$0")/lib.sh"

ALLOW_ANDROID_TV=0
parse_common "$@"
for a in ${REST[@]+"${REST[@]}"}; do
  case "$a" in
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&3; usage_common; echo "  --allow-android-tv  Go on with Android TV that isn't Google TV." >&3; exit 0 ;;
    --allow-android-tv) ALLOW_ANDROID_TV=1 ;;
    *) die "Unknown option: $a (see --help)" 2 ;;
  esac
done
resolve_tv
tv_connect

step "What is this TV?"
require_google_tv
FINGERPRINT="$(prop ro.build.fingerprint 'TCL/G10/...:12/...')"
say "  brand / maker   $TV_BRAND / $TV_MAKER"
say "  model / device  $TV_MODEL / $TV_DEVICE"
say "  Android         $TV_ANDROID (SDK $TV_SDK)"
say "  build           $FINGERPRINT"
PRODUCT="$(prop ro.product.name G10_4K_US_NF)"
SWVER="$(prop ro.software.version_id V8-R75PT01-LF1V534)"
say "  product         ${PRODUCT:-<not reported>}"
say "  software        ${SWVER:-<not reported>}"
[ "${TV_SDK:-0}" -ge 26 ] 2>/dev/null || is_dry || die "Android $TV_ANDROID is older than 8.0: the launcher needs Android 8 (SDK 26)." 3

step "Model check"
# The owner's set is a TCL 50Q651G (Q6 series). What the sources say (read 2026-11-27, NIGHT_REPORT.md "50Q651G
# verification"):
# - 50Q651G-CA: device G10, product G10_4K_US_NF, Android 12 (STT2.230203.001), software V8-R75PT01-LF1V499
#   (CleanYourTV PR #78, closed unmerged, @ b74bb63c2b). 55Q651G: product G10_4K_US_NF, software V8-R75PT01-LF1V534
#   (CleanYourTV examples/tcl-g10-google-tv/debloat.txt L45-46 @ be6087056a).
# - TCL Google TVs report a generic ro.product.model ("Smart TV Pro"; CleanYourTV docs/devices/tcl-75qm751g-qm7.md,
#   tcl-55p71b-pro-g09.md: "not exposed by ro.product.model"). What a Q651G itself returns is NOT confirmed, so the
#   "50Q651G" string is matched if it shows up, and otherwise the platform.
# - G10_4K_US_NF is also the 55QM5K and S551G (same file L89, L130): the platform alone can't prove the model.
case "$TV_MODEL $PRODUCT" in
  *[Qq]651[Gg]*)
    ok "Known model: TCL $TV_MODEL (Q6 series) — package checked against this model's reports (see NIGHT_REPORT.md)" ;;
  *)
    if [ "$TV_DEVICE" = G10 ] && [ "$PRODUCT" = G10_4K_US_NF ]; then
      ok "Platform G10 / G10_4K_US_NF: the one TCL's 50Q651G and 55Q651G (Q6 series) report — package checked for it"
      say "  TCL reports a generic model name (\"$TV_MODEL\"), so the TV can't say it's a 50Q651G: the sticker on the back"
      say "  or Settings > System > About shows it. (55QM5K and S551G sets share this platform.)"
    else
      say "  Not the platform of the TCL 50Q651G (G10 / G10_4K_US_NF) this package was checked against. The lists still"
      say "  suit TCL Google TVs; anything your set doesn't have is skipped."
    fi ;;
esac

step "Home screen"
HOME_PKG="$(home_package "$GOOGLE_HOME")"
ROLE="$(home_role "$GOOGLE_HOME_PKG")"
say "  Home button opens  ${HOME_PKG:-<unknown>}"
[ -n "$ROLE" ] && say "  HOME role holder   $ROLE"
HOMES="$(sh_read "$GOOGLE_HOME" cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | grep -oE '^ *[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | sed 's/^ *//' | sort -u || true)"
say "  apps that answer Home:"
printf '%s\n' "$HOMES" | sed '/^$/d; s/^/    /' >&3
SAVER="$(sh_read '<value read from the TV>' settings get secure screensaver_components | head -n 1)"
say "  screensaver        $SAVER"

step "Launcher package"
LVER="$(sh_read '' dumpsys package "$LAUNCHER_PKG" | grep -m1 -oE 'versionName=[^ ]+' || true)"
if [ -n "$LVER" ]; then
  say "  $LAUNCHER_PKG installed ($LVER)"
  FREC="$(sh_read '' settings get global "$FMTV_RECORD_KEY" | head -n 1)"
  [ -n "$FREC" ] && [ "$FREC" != null ] && say "  Free My TV's own switch has turned off: $FREC"
else
  say "  $LAUNCHER_PKG not installed yet (scripts/install-launcher.sh does it)"
fi
REC="$(tv_record '')"
[ -n "$REC" ] && say "  debloat.sh has turned off: $(printf '%s\n' "$REC" | paste -sd' ' -)"

step "Wi-Fi owner check"
# Android deletes a saved network when the app that saved it is disabled (Free My TV wifiOwners; CleanYourTV disable.sh).
OWNERS="$(sh_read 'cname=android.uid.system' dumpsys wifi | grep -oE 'cname=[A-Za-z0-9_.]+' | sort -u || true)"
if printf '%s\n' "$OWNERS" | grep -q "$GOOGLE_SETUP"; then
  warn "Your Wi-Fi network was saved by Google TV's setup app. Free My TV's switch will stop and ask you to forget the"
  warn "network and join it again (TV: Settings > Network & Internet). Do that before install-launcher.sh."
else
  ok "no saved network belongs to the setup app"
fi

step "Snapshot (the 'before' picture)"
SD="$(state_dir)"
STAMP="$(date +%Y%m%d-%H%M%S)"
DISABLED="$(pkgs -d '')"
ALL="$(pkgs '' "$GOOGLE_HOME_PKG com.tcl.esticker")"
if is_dry; then
  say "  (dry-run: would write $SD/baseline-$STAMP-disabled.txt and -packages.txt; nothing written)"
else
  printf '%s\n' "$DISABLED" > "$SD/baseline-$STAMP-disabled.txt"
  printf '%s\n' "$ALL" > "$SD/baseline-$STAMP-packages.txt"
  [ -f "$SD/baseline-disabled.txt" ] || cp "$SD/baseline-$STAMP-disabled.txt" "$SD/baseline-disabled.txt"
  {
    echo "when=$STAMP"; echo "tv=$TV"; echo "brand=$TV_BRAND"; echo "maker=$TV_MAKER"; echo "model=$TV_MODEL"
    echo "device=$TV_DEVICE"; echo "product=$PRODUCT"; echo "software=$SWVER"; echo "android=$TV_ANDROID"; echo "sdk=$TV_SDK"; echo "fingerprint=$FINGERPRINT"
    echo "kind=$TV_KIND"; echo "home=$HOME_PKG"; echo "screensaver=$SAVER"
  } > "$SD/detect-$STAMP.txt"
  ok "saved to $SD/ ($(printf '%s\n' "$ALL" | sed '/^$/d' | wc -l | tr -d ' ') packages, $(printf '%s\n' "$DISABLED" | sed '/^$/d' | wc -l | tr -d ' ') already disabled)"
fi

step "Result"
ok "This TV can take the package: $TV_KIND. Next: scripts/install-launcher.sh --ip ${TV%:5555}"
