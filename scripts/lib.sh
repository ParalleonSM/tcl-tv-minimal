# shellcheck shell=bash
# shellcheck disable=SC2034   # constants and flags used by the scripts that source this file
# Shared helpers for the tcl-tv-minimal scripts. Sourced, never run.
#
# Written for the bash that ships with macOS (3.2): no associative arrays, no mapfile, no ${x,,}, no `timeout`.
# Every command that would reach the TV goes through tv_do (changes something) or tv_read (only reads). In --dry-run
# both print the exact command and touch nothing; tv_read then answers with the placeholder its caller gives, so the
# script walks its whole happy path without a TV.

TTM_VERSION="1.0.0"
TTM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"            # override for tests: ADB=tests/fake-adb
DRY_RUN=0
ASSUME_YES=0
ALLOW_ANDROID_TV="${ALLOW_ANDROID_TV:-0}"   # --allow-android-tv in detect/debloat/install
TV=""                        # host:port, from --ip, $TV_IP or a prompt; never hard-coded
LAUNCHER_PKG="io.freemytv"
GOOGLE_HOME_PKG="com.google.android.apps.tv.launcherx"
GOOGLE_HOME="com.google.android.apps.tv.launcherx/.home.HomeActivity"   # Free My TV HomeMode.GOOGLE_HOME
GOOGLE_SETUP="com.google.android.tungsten.setupwraith"
RECORD_KEY="tcl_tv_minimal_disabled"      # what debloat.sh turned off, kept on the TV (settings global)
FMTV_RECORD_KEY="free_my_tv_disabled"     # what Free My TV's own switch turned off (HomeMode.RECORD)
PKG_RE='^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'   # a bare package name (CleanYourTV tools/disable.sh PKG_RE)
# Android's own pieces: with one of these off a TV may not boot again. Union of CleanYourTV tools/disable.sh CORE_RE
# (#884, #1578, #2557, #2803) and Free My TV HomeMode.CORE. No flag overrides it.
CORE_RE='^(android|android\.ext\.(services|shared)|com\.(google\.)?android\.sdksandbox|com\.android\.location\.fused|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings|com\.(google\.)?android\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|com\.android\.providers\..*|com\.cltv\.mal|com\.mediatek\.(tis|tv\.service|tv\.agent))$'
# Factory and service menus on any brand (Free My TV HomeMode.FACTORY).
FACTORY_RE='^(.*\.)?(factory|factorymenu|facmenu|fac|hkcfac|testpq)(\..*)?$'

exec 3>&1   # fd 3: the terminal, so logging still shows inside $(...)

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_B=$'\033[1m'; C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_D=$'\033[2m'; C_0=$'\033[0m'
else
  C_B=""; C_G=""; C_Y=""; C_R=""; C_D=""; C_0=""
fi
say()  { printf '%s\n' "$*" >&3; }
step() { printf '\n%s==> %s%s\n' "$C_B" "$*" "$C_0" >&3; }
ok()   { printf '%s  ok%s  %s\n' "$C_G" "$C_0" "$*" >&3; }
warn() { printf '%swarn%s  %s\n' "$C_Y" "$C_0" "$*" >&3; }
die()  { printf '%sSTOP%s  %s\n' "$C_R" "$C_0" "$1" >&2; exit "${2:-1}"; }
is_dry() { [ "$DRY_RUN" -eq 1 ]; }

# The command as a human would type it (for logs and --dry-run).
show_cmd() {
  local out="adb" a
  for a in "$@"; do
    case "$a" in
      *[[:space:]\'\"\;\&\|\<\>\(\)\$]*) out="$out '$(printf '%s' "$a" | sed "s/'/'\\\\''/g")'" ;;
      *) out="$out $a" ;;
    esac
  done
  printf '%s' "$out"
}

# tv_do <adb args...>: a command that changes the TV. Dry-run: printed, not run.
tv_do() {
  if is_dry; then
    printf '%s[dry-run]%s %s\n' "$C_D" "$C_0" "$(show_cmd -s "$TV" "$@")" >&3
    return 0
  fi
  printf '%s  +  %s%s\n' "$C_D" "$(show_cmd -s "$TV" "$@")" "$C_0" >&3
  "$ADB" -s "$TV" "$@" 2>&1 | tr -d '\r'
}

# tv_read <dry-run answer> <adb args...>: a read-only command. Dry-run: printed, and <dry-run answer> comes back.
tv_read() {
  local placeholder="$1"; shift
  if is_dry; then
    printf '%s[dry-run]%s %s   %s# read-only%s\n' "$C_D" "$C_0" "$(show_cmd -s "$TV" "$@")" "$C_D" "$C_0" >&3
    printf '%s\n' "$placeholder"
    return 0
  fi
  "$ADB" -s "$TV" "$@" 2>&1 | tr -d '\r'
}

# sh_read <dry-run answer> <shell command>: tv_read for `adb shell`.
sh_read() { local p="$1"; shift; tv_read "$p" shell "$@"; }
prop() { sh_read "${2:-}" getprop "$1" | head -n 1; }

confirm() {   # confirm "question" -> 0 yes, 1 no. --yes and --dry-run answer yes.
  if [ "$ASSUME_YES" -eq 1 ] || is_dry; then say "$1 [y/N] y (auto)"; return 0; fi
  local a
  printf '%s [y/N] ' "$1" >&3
  read -r a || a=""
  case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

usage_common() {
  cat >&3 <<EOF
Common options:
  --ip <host[:port]>  The TV's IP address (port 5555 if left out). Or set TV_IP. Asked for if neither is given.
  --dry-run, -n       Print every command that would run. Nothing is sent to the TV (no TV needed).
  --yes, -y           Don't ask before changes.
  --help, -h          This help.
EOF
}

# parse_common "$@": fills TV, DRY_RUN, ASSUME_YES; the rest goes to REST (an array).
REST=()
parse_common() {
  REST=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --ip) [ $# -ge 2 ] || die "--ip needs an address" 2; TV="$2"; shift 2 ;;
      --ip=*) TV="${1#--ip=}"; shift ;;
      -n|--dry-run) DRY_RUN=1; shift ;;
      -y|--yes) ASSUME_YES=1; shift ;;
      *) REST+=("$1"); shift ;;
    esac
  done
}

resolve_tv() {
  [ -n "$TV" ] || TV="${TV_IP:-}"
  if [ -z "$TV" ]; then
    if is_dry; then TV="TV_IP:5555"; say "(dry-run: no --ip given; using the placeholder TV_IP:5555)"; return 0; fi
    printf "The TV's IP address (TV: Settings > Network & Internet > your network): " >&3
    read -r TV || TV=""
  fi
  TV="$(printf '%s' "$TV" | tr -d '[:space:]')"
  [ -n "$TV" ] || die "No TV address given." 2
  case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
  printf '%s' "$TV" | grep -Eq '^[A-Za-z0-9._-]+:[0-9]{1,5}$' || die "\"$TV\" doesn't look like host:port (e.g. 192.168.1.50:5555)." 2
}
tv_host() { printf '%s' "${TV%:*}"; }

# Where this Mac keeps what it learned about this TV (git-ignored).
state_dir() {
  local d
  d="$TTM_ROOT/state/$(printf '%s' "$TV" | tr ':/' '__')"
  is_dry || mkdir -p "$d"
  printf '%s' "$d"
}

need_adb() {
  is_dry && return 0
  command -v "$ADB" >/dev/null 2>&1 || die "adb not found. On the Mac: brew install --cask android-platform-tools" 127
}

# A Roku TV answers its ECP API on port 8060 and has no ADB at all.
probe_roku() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -s -m 3 "http://$(tv_host):8060/query/device-info" 2>/dev/null | grep -q '<device-info'
}

# tv_connect: adb connect, then wait for the "Allow USB debugging?" answer on the TV.
tv_connect() {
  step "Connecting to the TV at $TV"
  need_adb
  if is_dry; then
    printf '%s[dry-run]%s adb connect %s\n' "$C_D" "$C_0" "$TV" >&3
    printf '%s[dry-run]%s adb -s %s get-state   %s# repeated until "device" (up to 90 s)%s\n' "$C_D" "$C_0" "$TV" "$C_D" "$C_0" >&3
    return 0
  fi
  local out st i asked=0
  out="$("$ADB" connect "$TV" 2>&1 | tr -d '\r')" || true
  case "$out" in
    *"connected to"*|*"already connected"*) ;;
    *)
      if probe_roku; then
        die "This is a Roku TV (it answers Roku's API on port 8060). Roku TVs don't run Android: no ADB, no launcher,
      nothing in this package applies. Nothing was changed." 3
      fi
      die "Couldn't connect: $out
      - Is the TV on, on the same Wi-Fi as this Mac, and is USB debugging on (DEPLOY.md step 2)?
      - Right IP? (TV: Settings > Network & Internet)
      - macOS 'No route to host' while the TV is up: adb kill-server; then allow your terminal in
        System Settings > Privacy & Security > Local Network (DEPLOY.md, Troubleshooting)." 4 ;;
  esac
  for i in $(seq 1 45); do
    st="$("$ADB" -s "$TV" get-state 2>&1 | tr -d '\r')" || true
    case "$st" in
      device) ok "connected ($TV)"; return 0 ;;
      *unauthorized*)
        if [ $asked -eq 0 ]; then
          say "  The TV is asking 'Allow USB debugging?'. On the TV: tick 'Always allow from this computer', choose Allow."
          asked=1
        fi ;;
      *offline*) [ $((i % 10)) -eq 0 ] && "$ADB" connect "$TV" >/dev/null 2>&1 ;;
    esac
    sleep 2
  done
  die "The TV never authorized this Mac (state: $st). Accept the prompt on the TV, then run this again." 4
}

# What answers the Home button now, as a package ("" when it can't tell).
home_package() {
  local h
  h="$(sh_read "${1:-$GOOGLE_HOME}" cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
  h="${h%%/*}"
  printf '%s' "$h" | grep -Eq "$PKG_RE" && printf '%s' "$h"
  return 0
}
# HOME role holder (Android 10+), "" if none or unsupported.
home_role() {
  sh_read "${1:-}" cmd role get-role-holders android.app.role.HOME 2>/dev/null | tr -d ' ' | grep -E "$PKG_RE" | head -n 1 || true
}

# Packages in the given state: pm list packages <flag> ("" all, -d disabled, -e enabled, -s system).
pkgs() {
  if is_dry; then
    sh_read "" pm list packages "$1" >/dev/null
    printf '%s\n' "${2:-}" | tr ' ' '\n' | sed '/^$/d'   # the caller's dry-run answer: space-separated names
    return 0
  fi
  sh_read "" pm list packages "$1" | sed -n 's/^package://p'
}

in_list() { printf '%s\n' "$2" | grep -qxF "$1"; }   # in_list <word> <newline-separated list>

# Reads a list file: one package per line, '#' comments, blank lines ignored; fails on anything not package-shaped.
read_list() {
  local f="$1" names bad
  [ -r "$f" ] || die "Can't read $f" 2
  names="$(sed -e 's/\r$//' -e 's/#.*$//' "$f" | awk 'NF {print $1}')"
  bad="$(printf '%s\n' "$names" | sed '/^$/d' | grep -vE "$PKG_RE" || true)"
  [ -z "$bad" ] || die "$f has lines that aren't package names: $bad" 2
  printf '%s\n' "$names" | sed '/^$/d'
}

# Is the given package one we must never disable? Prints the reason.
forbidden() {
  local p="$1"
  if printf '%s' "$p" | grep -Eq "$CORE_RE"; then echo "core Android / TV middleware (may not boot again)"; return 0; fi
  if printf '%s' "$p" | grep -Eq "$FACTORY_RE"; then echo "factory / service menu"; return 0; fi
  if in_list "$p" "$(read_list "$TTM_ROOT/config/do-not-touch.list")"; then echo "on config/do-not-touch.list"; return 0; fi
  return 1
}

# The TV's own record of what debloat.sh turned off (comma list in settings global), one per line.
tv_record() {
  local v
  v="$(sh_read "${1:-}" settings get global "$RECORD_KEY" | head -n 1)"
  [ "$v" = "null" ] && v=""
  printf '%s\n' "$v" | tr ',' '\n' | grep -E "$PKG_RE" || true
}
save_tv_record() {   # save_tv_record "<newline list>"
  local list
  list="$(printf '%s\n' "$1" | grep -E "$PKG_RE" | sort -u | paste -sd, - || true)"
  if [ -z "$list" ]; then tv_do shell settings delete global "$RECORD_KEY" >/dev/null
  else tv_do shell settings put global "$RECORD_KEY" "$list" >/dev/null; fi
}

# Google TV, Android TV, Fire TV or something else? Sets TV_KIND (google-tv|android-tv|fire-tv|other) and fills
# TV_BRAND, TV_MAKER, TV_MODEL, TV_DEVICE, TV_ANDROID, TV_SDK.
classify_tv() {
  TV_BRAND="$(prop ro.product.brand TCL)"
  TV_MAKER="$(prop ro.product.manufacturer TCL)"
  TV_MODEL="$(prop ro.product.model 'Smart TV Pro')"
  TV_DEVICE="$(prop ro.product.device G10)"
  TV_ANDROID="$(prop ro.build.version.release 12)"
  TV_SDK="$(prop ro.build.version.sdk 31)"
  local features all
  features="$(sh_read 'feature:android.software.leanback' pm list features)"
  all="$(pkgs "" "$GOOGLE_HOME_PKG")"
  if printf '%s %s' "$TV_BRAND" "$TV_MAKER" | grep -qi amazon \
      || printf '%s\n' "$features" | grep -q 'feature:amazon.hardware.fire_tv' \
      || in_list com.amazon.tv.launcher "$all"; then
    TV_KIND=fire-tv
  elif ! printf '%s\n' "$features" | grep -q 'feature:android.software.leanback'; then
    TV_KIND=other
  elif in_list "$GOOGLE_HOME_PKG" "$all"; then
    TV_KIND=google-tv
  else
    TV_KIND=android-tv
  fi
}

# Stops unless this is a Google TV (or an Android TV with --allow-android-tv).
require_google_tv() {
  classify_tv
  case "$TV_KIND" in
    google-tv) ok "Google TV: $TV_BRAND $TV_MODEL ($TV_DEVICE), Android $TV_ANDROID (SDK $TV_SDK)" ;;
    fire-tv) die "This is an Amazon Fire TV ($TV_BRAND $TV_MODEL). Amazon's launcher keeps the Home button and Free My TV
      refuses to switch there; this package does not apply. Nothing was changed." 3 ;;
    android-tv)
      if [ "${ALLOW_ANDROID_TV:-0}" = 1 ]; then warn "Android TV (not Google TV): $TV_BRAND $TV_MODEL — continuing (--allow-android-tv)"
      else die "This is Android TV, not Google TV ($TV_BRAND $TV_MODEL, no $GOOGLE_HOME_PKG). Free My TV supports it, but
      this package was put together for a TCL Google TV. Re-run with --allow-android-tv to go on anyway." 3; fi ;;
    *) die "This device doesn't report Android TV's leanback feature ($TV_BRAND $TV_MODEL): it's not a TV running
      Android TV / Google TV. Nothing was changed." 3 ;;
  esac
  if ! printf '%s %s' "$TV_BRAND" "$TV_MAKER" | grep -qi tcl; then
    warn "Not a TCL ($TV_BRAND / $TV_MAKER). The debloat list is TCL's; packages it doesn't have are skipped."
  fi
}

# Foreground activity (package/activity) or "".
top_activity() {
  sh_read "${1:-}" dumpsys activity activities \
    | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | head -n 1 \
    | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -n 1 || true
}

# ---------------------------------------------------------------------------------------------------------------------
# The launcher's control layer: content://io.freemytv.control (Control.java), usable only by adb's shell.

CONTROL_URI="content://io.freemytv.control"

# Single-quote a string for the TV's shell (adb shell joins its arguments and the TV's sh parses them again).
shq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# control_call '<json command>' [dry-run reply] -> prints the JSON reply ("" if none).
control_call() {
  local out dflt='{"ok":true}'
  [ $# -ge 2 ] && dflt="$2"
  out="$(sh_read "$dflt" "content call --uri $CONTROL_URI --method command --arg $(shq "$1")")"
  # The TV prints: Result: Bundle[{reply={"ok":true,...}}]
  case "$out" in
    *"reply="*) printf '%s\n' "$out" | tr -d '\n' | sed -e 's/^.*reply=//' -e 's/}][[:space:]]*$//'; echo ;;
    "{"*) printf '%s\n' "$out" ;;          # dry-run placeholder
    *) printf '%s\n' "$out" | head -n 3 ;;
  esac
}

# Validate a JSON file with whatever this Mac has (python3 or jq); the launcher validates it again and keeps the old
# config if the new one doesn't parse.
check_json() {
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import json' >/dev/null 2>&1; then
    python3 -I -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null || die "$1 is not valid JSON." 2
  elif command -v jq >/dev/null 2>&1; then
    jq empty "$1" >/dev/null 2>&1 || die "$1 is not valid JSON." 2
  else
    warn "No python3 or jq here to check $1; the launcher will check it and keep its old config if it's broken."
  fi
}

# push_config <file>: send a config.json to the launcher and make it reload.
push_config() {
  local f="$1" reply
  check_json "$f"
  if is_dry; then
    printf '%s[dry-run]%s adb -s %s exec-in content write --uri %s/config.json < %s\n' "$C_D" "$C_0" "$TV" "$CONTROL_URI" "$f" >&3
  else
    printf '%s  +  adb -s %s exec-in content write --uri %s/config.json < %s%s\n' "$C_D" "$TV" "$CONTROL_URI" "$f" "$C_0" >&3
    "$ADB" -s "$TV" exec-in content write --uri "$CONTROL_URI/config.json" < "$f" 2>&1 | tr -d '\r' >&3 || true
  fi
  reply="$(control_call '{"action":"reload_config"}' '{"ok":true,"config":"loaded"}')"
  case "$reply" in
    *'"ok":true'*) ok "config sent and loaded: $(printf '%s' "$reply" | cut -c1-160)" ;;
    *) die "The launcher didn't take the config: ${reply:-no answer}
      (It keeps the previous one. Is the launcher installed? Is $f valid?)" 5 ;;
  esac
}
