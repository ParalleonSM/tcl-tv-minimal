#!/usr/bin/env bash
# shellcheck disable=SC2016  # expect "<test>": the single-quoted tests are eval'd later, on purpose
# Runs the deploy scripts for real (not --dry-run) against tests/fake-adb, a pretend TCL Google TV, and checks what
# they did to it. What this proves: the scripts' logic, order and safety checks. What it can't prove: that a real TCL
# answers the same way (see NIGHT_REPORT.md).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ADB="$ROOT/tests/fake-adb" NO_COLOR=1 TTM_FAST=1
TMP="$ROOT/tests/.tmp/fake"
PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL  [$SCEN] $*"; }
expect() { if eval "$1"; then ok; else bad "$2"; fi; }
state() { awk -v p="$1" '$1==p {print $2}' "$FAKE_TV/packages"; }

fake_tv_init() {   # a TCL Google TV, Android 12, stock home, nothing touched
  rm -rf "$TMP"; mkdir -p "$TMP"; export FAKE_TV="$TMP"
  cat > "$TMP/props" <<EOF
ro.product.brand=TCL
ro.product.manufacturer=TCL
ro.product.model=Smart TV Pro
ro.product.device=G10
ro.build.version.release=12
ro.build.version.sdk=31
ro.build.fingerprint=TCL/G10/G10:12/STT1.230301.001/1234:user/release-keys
EOF
  : > "$TMP/packages"
  for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.apps.tv.dreamx \
      com.google.android.katniss com.google.android.tts com.android.vending com.google.android.gms com.android.systemui \
      com.tcl.tv com.tcl.tvinput com.tcl.esticker com.tcl.exhibit com.tcl.hotelmenu com.tcl.messagebox com.tcl.usercenter \
      com.tcl.logkit com.google.android.feedback com.tcl.repairguide com.tcl.ocean.instructions \
      com.google.android.play.games com.google.android.youtube.tvmusic com.tcl.smartalexa com.netflix.ninja \
      com.google.android.youtube.tv com.tcl.keycustomfunctionservice; do
    echo "$p enabled" >> "$TMP/packages"
  done
  echo "com.google.android.videos disabled-user" >> "$TMP/packages"   # the owner had turned this off before
  echo "com.google.android.apps.tv.launcherx/.home.HomeActivity" > "$TMP/home"
  cp "$TMP/home" "$TMP/top"
  echo "android.uid.system" > "$TMP/wifi_owners"
  echo "com.tcl.tvinput" > "$TMP/input_pkgs"
  echo "screensaver_components=com.google.android.apps.tv.dreamx/.DreamService" > "$TMP/settings_secure"
  echo "screensaver_default_component=com.google.android.apps.tv.dreamx/.DreamService" >> "$TMP/settings_secure"
  : > "$TMP/settings_global"
  rm -rf "$ROOT/state/127.0.0.9_5555"
  cp "$TMP/packages" "$TMP/packages.initial"
  unset FAKE_KIND FAKE_SWITCH FAKE_ORIGINAL FAKE_FAIL_PKG FAKE_INSTALL FAKE_UNAUTH
}
run() { "$@" > "$TMP/out.txt" 2>&1; echo $?; }
IP=(--ip 127.0.0.9 --yes)

# ---------------------------------------------------------------------------------------------------------------------
SCEN="happy path: detect -> install-launcher -> debloat -> rollback leaves the TV as it was"
fake_tv_init
export FAKE_UNAUTH=2
rc=$(run "$ROOT/scripts/detect.sh" "${IP[@]}"); expect '[ "$rc" = 0 ]' "detect rc=$rc: $(tail -3 "$TMP/out.txt")"
expect 'grep -q "Allow USB debugging" "$TMP/out.txt"' "detect told the user about the TV's prompt"
expect '[ -f "$ROOT/state/127.0.0.9_5555/baseline-disabled.txt" ]' "detect saved the baseline"
expect 'grep -qx com.google.android.videos "$ROOT/state/127.0.0.9_5555/baseline-disabled.txt"' "baseline has the pre-disabled app"
unset FAKE_UNAUTH
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --wait 60)
expect '[ "$rc" = 0 ]' "install-launcher rc=$rc: $(tail -5 "$TMP/out.txt")"
expect '[ "$(state io.freemytv)" = enabled ]' "launcher installed"
expect '[ "$(cut -d/ -f1 "$FAKE_TV/home")" = io.freemytv ]' "launcher is Home after the switch"
# Safety order: the launcher was started and Home still went to the stock launcher BEFORE the switch happened.
expect 'awk "/am start -W -n io.freemytv/{a=NR} /SWITCHED/{s=NR} END{exit !(a && s && a < s)}" "$FAKE_TV/adb.log"' "launcher proven before the switch"
expect 'grep -q "Home still opens the stock home screen" "$TMP/out.txt"' "checked stock Home still answered before the switch"
expect 'grep -q "exec-in content write" "$FAKE_TV/adb.log"' "config pushed"
expect 'grep -q "pm grant io.freemytv android.permission.RECORD_AUDIO" "$FAKE_TV/adb.log"' "microphone granted for JARVIS"
expect 'grep "appops set io.freemytv" "$FAKE_TV/adb.log" | grep -q "appops write-settings"' "TclAppBoot allow saved to disk (write-settings)"
expect '! grep -q "pm disable-user --user 0 com.google.android.apps.tv.launcherx" "$FAKE_TV/adb.log"' "the Mac never disabled the stock launcher itself"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}"); expect '[ "$rc" = 0 ]' "debloat rc=$rc: $(tail -5 "$TMP/out.txt")"
expect '[ "$(state com.tcl.smartalexa)" = disabled-user ]' "smartalexa off"
expect '[ "$(state com.tcl.logkit)" = disabled-user ]' "logkit off"
expect '[ "$(state com.tcl.tv)" = enabled ]' "TCL Live TV untouched"
expect '[ "$(state com.google.android.katniss)" = enabled ]' "Assistant/speech untouched"
expect 'grep -q "skip  com.tcl.esticker — already off" "$TMP/out.txt"' "skipped what the launcher switch already turned off"
expect 'grep -q "skip  com.google.android.videos — already off" "$TMP/out.txt"' "skipped the owner's own pre-disabled app"
REC="$(sed -n 's/^tcl_tv_minimal_disabled=//p' "$FAKE_TV/settings_global")"
expect 'echo "$REC" | grep -q com.tcl.smartalexa && ! echo "$REC" | grep -q com.tcl.esticker' "TV record has only what debloat.sh turned off ($REC)"
rc=$(run "$ROOT/scripts/rollback.sh" "${IP[@]}" --wait 60); expect '[ "$rc" = 0 ]' "rollback rc=$rc: $(tail -8 "$TMP/out.txt")"
expect 'diff <(sort "$FAKE_TV/packages.initial") <(sort "$FAKE_TV/packages") >/dev/null' "package states identical to before: $(diff <(sort "$FAKE_TV/packages.initial") <(sort "$FAKE_TV/packages") | tr '\n' ' ')"
expect '[ "$(cat "$FAKE_TV/home")" = com.google.android.apps.tv.launcherx/.home.HomeActivity ]' "stock Home back"
expect '[ "$(state com.google.android.videos)" = disabled-user ]' "the owner's own disabled app stayed disabled"
expect '! grep -q tcl_tv_minimal_disabled "$FAKE_TV/settings_global"' "TV record cleared"
expect 'grep -q "match the snapshot from before" "$TMP/out.txt"' "rollback compared with the baseline"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="debloat stops on an unexpected failure; rollback undoes what it did"
fake_tv_init
export FAKE_FAIL_PKG=com.google.android.feedback
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}"); expect '[ "$rc" = 5 ]' "rc=$rc"
expect 'grep -q "Unexpected answer for com.google.android.feedback" "$TMP/out.txt"' "says which package failed"
expect '[ "$(state com.tcl.repairguide)" = enabled ]' "stopped: nothing after the failure was touched"
expect '[ "$(state com.tcl.logkit)" = disabled-user ]' "what came before was done"
unset FAKE_FAIL_PKG
rc=$(run "$ROOT/scripts/rollback.sh" "${IP[@]}"); expect '[ "$rc" = 0 ]' "rollback rc=$rc: $(tail -5 "$TMP/out.txt")"
expect 'diff <(sort "$FAKE_TV/packages.initial") <(sort "$FAKE_TV/packages") >/dev/null' "back to before"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="guards: Wi-Fi owner, input hardware, Home claimant"
fake_tv_init
echo "com.tcl.esticker" >> "$FAKE_TV/wifi_owners"
echo "com.tcl.exhibit" >> "$FAKE_TV/input_pkgs"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}"); expect '[ "$rc" = 0 ]' "rc=$rc"
expect '[ "$(state com.tcl.esticker)" = enabled ]' "Wi-Fi owner kept on"
expect '[ "$(state com.tcl.exhibit)" = enabled ]' "input-hardware package kept on"
expect 'grep -q "saved a Wi-Fi network" "$TMP/out.txt"' "said why (Wi-Fi)"
expect 'grep -q "inputs or remote need it" "$TMP/out.txt"' "said why (inputs)"
printf 'com.google.android.apps.tv.launcherx\n' > "$TMP/home-list.txt"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}" --list "$TMP/home-list.txt"); expect '[ "$rc" = 2 ]' "launcherx in a list is refused (do-not-touch) rc=$rc"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="refusals before touching the TV"
fake_tv_init
printf 'com.tcl.esticker\ncom.android.systemui\n' > "$TMP/core.txt"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}" --list "$TMP/core.txt"); expect '[ "$rc" = 2 ]' "core package refused rc=$rc"
expect '! grep -qs "pm disable-user" "$FAKE_TV/adb.log"' "nothing sent to the TV"
printf 'com.tcl.tv\n' > "$TMP/dnt.txt"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}" --list "$TMP/dnt.txt"); expect '[ "$rc" = 2 ]' "do-not-touch refused rc=$rc"
printf 'com.tcl.factory.view\n' > "$TMP/fac.txt"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}" --list "$TMP/fac.txt"); expect '[ "$rc" = 2 ]' "factory menu refused rc=$rc"
printf 'not a package; rm -rf /\n' > "$TMP/evil.txt"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}" --list "$TMP/evil.txt"); expect '[ "$rc" = 2 ]' "non-package line refused rc=$rc"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="the wrong kind of TV stops with a clear message"
fake_tv_init
export FAKE_KIND=fire-tv
rc=$(run "$ROOT/scripts/detect.sh" "${IP[@]}"); expect '[ "$rc" = 3 ]' "Fire TV rc=$rc"
expect 'grep -q "Amazon Fire TV" "$TMP/out.txt"' "says Fire TV"
rc=$(run "$ROOT/scripts/debloat.sh" "${IP[@]}"); expect '[ "$rc" = 3 ]' "debloat refuses Fire TV rc=$rc"
fake_tv_init
grep -v launcherx "$FAKE_TV/packages" > "$FAKE_TV/p" && mv "$FAKE_TV/p" "$FAKE_TV/packages"
rc=$(run "$ROOT/scripts/detect.sh" "${IP[@]}"); expect '[ "$rc" = 3 ]' "Android TV (not Google TV) stops rc=$rc"
rc=$(run "$ROOT/scripts/detect.sh" "${IP[@]}" --allow-android-tv); expect '[ "$rc" = 0 ]' "…unless --allow-android-tv rc=$rc"
fake_tv_init
export FAKE_KIND=roku
python3 -I -c '
import http.server, socketserver, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"<device-info><model-name>TCL Roku TV</model-name></device-info>")
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("127.0.0.9", 8060), H).handle_request()' &
sleep 0.5
rc=$(run "$ROOT/scripts/detect.sh" "${IP[@]}"); expect '[ "$rc" = 3 ]' "Roku stops rc=$rc: $(head -3 "$TMP/out.txt")"
expect 'grep -q "Roku TV" "$TMP/out.txt"' "says Roku"
wait 2>/dev/null

# ---------------------------------------------------------------------------------------------------------------------
SCEN="rollback --force when the app can't do Original"
fake_tv_init
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --wait 60)
expect '[ "$rc" = 0 ]' "install rc=$rc"
export FAKE_ORIGINAL=never
rc=$(run "$ROOT/scripts/rollback.sh" "${IP[@]}" --force); expect '[ "$rc" = 0 ]' "rollback --force rc=$rc: $(tail -6 "$TMP/out.txt")"
expect '[ "$(cat "$FAKE_TV/home")" = com.google.android.apps.tv.launcherx/.home.HomeActivity ]' "stock Home set"
expect 'awk "/pm enable com.google.android.apps.tv.launcherx/{l=NR} /pm enable com.google.android.tungsten.setupwraith/{s=NR} END{exit !(l && s && l < s)}" "$FAKE_TV/adb.log"' "launcherx on before setupwraith (Free My TV's order)"
expect '[ -z "$(state io.freemytv)" ]' "launcher uninstalled"
expect 'grep -q "screensaver_components=com.google.android.apps.tv.dreamx" "$FAKE_TV/settings_secure"' "screensaver restored"
expect '! grep -q "io.freemytv" "$FAKE_TV/settings_secure"' "remote-button service removed"
expect 'diff <(sort "$FAKE_TV/packages.initial") <(sort "$FAKE_TV/packages") >/dev/null' "package states identical to before: $(diff <(sort "$FAKE_TV/packages.initial") <(sort "$FAKE_TV/packages") | tr '\n' ' ')"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="rollback refuses to uninstall the launcher while it is still Home"
fake_tv_init
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --wait 60)
export FAKE_ORIGINAL=never
rc=$(run "$ROOT/scripts/rollback.sh" "${IP[@]}" --wait 5); expect '[ "$rc" = 6 ]' "times out waiting for Original, rc=$rc"
expect '[ "$(state io.freemytv)" = enabled ]' "launcher NOT uninstalled (it is still Home)"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="install-launcher: signature conflict, and a switch that never happens"
fake_tv_init
export FAKE_INSTALL=incompatible
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk"); expect '[ "$rc" = 5 ]' "conflict rc=$rc"
expect 'grep -q "signed with another key" "$TMP/out.txt"' "explains the conflict"
fake_tv_init
export FAKE_SWITCH=never
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --wait 10); expect '[ "$rc" = 6 ]' "no switch rc=$rc"
expect '[ "$(state com.google.android.apps.tv.launcherx)" = enabled ]' "stock launcher still on when the switch didn't happen"
fake_tv_init
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --no-switch); expect '[ "$rc" = 0 ]' "--no-switch rc=$rc"
expect '[ "$(cut -d/ -f1 "$FAKE_TV/home")" = com.google.android.apps.tv.launcherx ]' "--no-switch leaves stock Home"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="agent control and config push"
fake_tv_init
rc=$(run "$ROOT/scripts/install-launcher.sh" "${IP[@]}" --apk "$ROOT/release/TCLMinimal.apk" --no-switch)
rc=$(run "$ROOT/scripts/tvctl.sh" "${IP[@]}" status); expect '[ "$rc" = 0 ] && grep -q "\"ok\":true" "$TMP/out.txt"' "tvctl status: $(cat "$TMP/out.txt")"
rc=$(run "$ROOT/scripts/tvctl.sh" "${IP[@]}" say "switch to HDMI 2"); expect 'grep -q "\"action\":\"utterance\"" "$FAKE_TV/adb.log"' "tvctl say sends an utterance"
rc=$(run "$ROOT/scripts/tvctl.sh" "${IP[@]}" run volume step=-3); expect 'grep -q "\"step\":\"-3\"" "$FAKE_TV/adb.log"' "tvctl run passes args as JSON"
rc=$(run "$ROOT/scripts/tvctl.sh" "${IP[@]}" jarvis off); expect 'grep -q "\"enabled\":\"false\"" "$FAKE_TV/adb.log"' "tvctl jarvis off"
rc=$(run "$ROOT/scripts/push-config.sh" "${IP[@]}"); expect '[ "$rc" = 0 ] && cmp -s "$FAKE_TV/config.json" "$ROOT/config/config.json"' "push-config delivered config.json rc=$rc"
echo '{ broken' > "$TMP/bad.json"
rc=$(run "$ROOT/scripts/push-config.sh" "${IP[@]}" "$TMP/bad.json"); expect '[ "$rc" = 2 ]' "broken JSON refused before sending rc=$rc"

# ---------------------------------------------------------------------------------------------------------------------
SCEN="--dry-run changes nothing and needs no TV"
fake_tv_init
cp "$FAKE_TV/packages" "$TMP/before"
for s in detect debloat install-launcher rollback push-config; do
  rc=$(run "$ROOT/scripts/$s.sh" --dry-run --ip 127.0.0.9); expect '[ "$rc" = 0 ]' "$s --dry-run rc=$rc: $(tail -3 "$TMP/out.txt")"
done
rc=$(run "$ROOT/scripts/tvctl.sh" --dry-run --ip 127.0.0.9 status); expect '[ "$rc" = 0 ]' "tvctl --dry-run rc=$rc"
expect '[ ! -s "$FAKE_TV/adb.log" ]' "no adb call at all in dry-run: $(head -3 "$FAKE_TV/adb.log" 2>/dev/null)"
expect 'cmp -s "$TMP/before" "$FAKE_TV/packages"' "TV state unchanged"
expect '[ ! -d "$ROOT/state/127.0.0.9_5555" ]' "no state written on the Mac"

echo "script tests: $PASS passed, $FAIL failed"
rm -rf "$ROOT/state/127.0.0.9_5555"
[ "$FAIL" -eq 0 ]
