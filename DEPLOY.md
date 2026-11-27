# Deploying to the TCL Google TV — from a cold start on the Mac

About **15–20 minutes**, with the TV and its remote in front of you. Every step is undoable (see [Undo](#undo)).

> **What this is, honestly.** This is *not* a root exploit or a "jailbreak". TCL Google TVs run Android TV, and the
> safe, reversible way to change them is ADB, Android's own debugging bridge, which the TV lets you switch on. Through
> it we (1) install a minimalist home screen (a fork of the open-source **Free My TV** launcher) and make it the home
> screen, and (2) turn off — not uninstall — a short, sourced list of TCL/Google bloat apps. No root, no bootloader
> unlock, nothing that voids the warranty. Nothing that would need root is in here.

Contents: [0 Before you start](#0-before-you-start) · [1 Mac](#1-on-the-mac-once-5-min) ·
[2 TV](#2-on-the-tv-developer-options-and-usb-debugging-3-min) · [3 Connect](#3-connect-and-check-the-tv-2-min) ·
[4 Launcher](#4-install-the-launcher-and-make-it-home-5-min) · [5 Debloat](#5-turn-off-the-bloat-2-min) ·
[6 JARVIS](#6-jarvis-and-the-classic-layout) · [7 Customize](#7-customize-without-rebuilding) ·
[Troubleshooting](#troubleshooting) · [Undo](#undo)

---

## 0. Before you start

- Mac and TV on the **same Wi-Fi network**.
- Know the TV's **IP address**: TV → *Settings → Network & Internet →* (your network) → IP address. Example below:
  `192.168.1.50` — use yours. It's never stored in the repo.
- Every script has `--dry-run`: it prints every command it *would* send and touches nothing (no TV needed). Run it
  first if you want to see exactly what will happen.
- Every script explains itself: `scripts/<name>.sh --help`.

## 1. On the Mac, once (5 min)

```bash
# adb (Android's debugging bridge). With Homebrew:
brew install --cask android-platform-tools
#   No Homebrew? Download https://dl.google.com/android/repository/platform-tools-latest-darwin.zip,
#   unzip it, and run:  export PATH="$PWD/platform-tools:$PATH"
adb version

# This package
git clone https://github.com/ParalleonSM/tcl-tv-minimal.git
cd tcl-tv-minimal
shasum -a 256 -c release/SHA256SUMS        # both APKs: OK
```

Nothing to build: the APKs are in `release/`. (To build them yourself see [README → Build](README.md#build).)

## 2. On the TV: Developer options and USB debugging (3 min)

1. *Settings → System → About*. Scroll to **Android TV OS build** and press OK on it **7 times**, until it says you're
   a developer.
2. Back to *Settings → System → **Developer options*** → turn on **USB debugging**.
   (Despite the name, this is also what opens ADB over the network on TCL TVs — port 5555.)

**What the TV looks like:** unchanged. A toast says "You are now a developer".

## 3. Connect and check the TV (2 min)

```bash
scripts/detect.sh --ip 192.168.1.50
```

- The TV pops up **"Allow USB debugging?"** with this Mac's key: tick **Always allow from this computer** → **Allow**.
- It prints the model, Android version, the current home screen, and **confirms it's a Google TV**. On a Roku TV or a
  Fire TV it **stops** and says why (the approach doesn't apply there). It changes nothing.
- It saves a "before" snapshot of the TV's package states in `state/` (on the Mac, git-ignored); rollback compares
  against it at the end.
- If it warns *"Your Wi-Fi network was saved by Google TV's setup app"*: on the TV, *Settings → Network & Internet*,
  **forget** your network and **join it again** (otherwise turning the setup app off would drop the TV off Wi-Fi —
  the launcher's switch refuses until you do).

**Expect:** `ok  This TV can take the package: google-tv.`

## 4. Install the launcher and make it Home (5 min)

```bash
scripts/install-launcher.sh --ip 192.168.1.50 --dry-run   # optional: see every command first
scripts/install-launcher.sh --ip 192.168.1.50
```

What happens, in this order (the stock home screen is never touched until the new one is proven):

| Step | What the script does | What you see on the TV |
|---|---|---|
| 1 | `adb install -r release/TCLMinimal.apk` | Maybe a Play Protect dialog: **More details → Install anyway** |
| 2 | allows it to run in the background (TCL blocks that by default) | nothing |
| 2b | grants the microphone (JARVIS's voice mode) | nothing |
| 3 | sends `config/config.json` | nothing |
| 4 | opens the new home screen, checks it's on screen and answers its command layer, then presses Home: **the stock Google TV home must come back** | the new dark home screen for a few seconds, then Google TV's again |
| 5 | opens the launcher's settings and **waits for you** | the *Free My TV* settings screen |
| 6 | checks Home now opens the new launcher | the new home screen |

At step 5, with the remote: **Home Screen → Free My TV**. The TV asks **"Allow USB debugging?"** once more — this time
for the launcher itself (it switches safely from inside the TV): tick **Always allow** → **Allow**. It shows
*Measuring…* and the old home screen may flash by a couple of times. That's expected. The script sees the switch
happen and finishes.

That switch (Free My TV's own, unmodified) turns off the stock Google TV home, its setup app and screensaver **in the
safe order** (setup app first), after its own checks (inputs, Wi-Fi owner), and keeps a watchdog: **if the new home
screen crashes 3 times in 5 minutes, the TV goes back to the stock one by itself.**

**What it looks like now** (the JARVIS build, which is what's installed): a dark, quiet home screen —
a big clock where Google TV had its recommendation rows, your apps on one glass shelf at the bottom (the focused one
grows), round buttons top-right (Live TV, Inputs, Settings), and a slim **JARVIS** pill at the top centre
("Good evening, sir · Hold Back to talk"). No ads, no rows of suggestions.

## 5. Turn off the bloat (2 min)

```bash
scripts/debloat.sh --ip 192.168.1.50 --dry-run   # see exactly which packages, and the checks
scripts/debloat.sh --ip 192.168.1.50
```

- Turns off the **13 packages** in `config/debloat.list` (retail demo, hotel mode, TCL promo messages and account nags,
  log upload, manuals, Play Games, YouTube Music, the Google TV store app, TCL's Alexa) — one at a time, printing each.
  Every one is disabled by at least two independent debloat sources and protected by none (sources in the file and in
  [docs/RESEARCH.md](docs/RESEARCH.md)).
- **Never** touches anything in `config/do-not-touch.list` (Live TV/HDMI, remote, keyboard, voice, Settings, System UI,
  the launcher fallbacks…) or Android's core pieces — it refuses the whole list if one sneaks in.
- Skips, and says why: an app that saved a Wi-Fi network; anything the HDMI inputs or the remote's Input key need;
  anything that answers Home; anything already off.
- Stops at the first unexpected answer. What it turned off is recorded on the TV and on the Mac, for rollback.
- Several of these the launcher's switch already turned off in step 4: they show as `skip … already off`. Fine.
- Want more gone? Uncomment lines under *OPTIONAL* in `config/debloat.list` (mirroring, USB media player…), run again.

**What it looks like:** nothing changes on screen; a few apps disappear from *All Apps*.

## 6. JARVIS and the classic layout

The installed APK has **one toggle** between the **JARVIS layout** (on at first install) and the **classic**
minimalist layout. Off = the classic layout exactly — JARVIS is a separate layer over it, not woven in. Instant: no
reinstall, nothing lost. Three ways, all the same switch (it's saved in the TV's `config.json` as `jarvis.enabled`):

- **On the TV:** on the home screen, the round **Settings** button (top right) opens the launcher's settings →
  **JARVIS mode** (last row) → OK.
- **By voice:** "classic mode" (JARVIS turns itself off).
- **From the Mac:** `scripts/tvctl.sh --ip 192.168.1.50 jarvis off` (or `on`).

**Talking to JARVIS:** on the home screen, **hold Back** (about a second). The screen dims, the ring appears, JARVIS
says "Yes, sir?" and listens. Try: *"open Netflix"*, *"switch to HDMI 2"*, *"volume down three"*, *"mute"*,
*"pause"*, *"search for lo-fi beats"*, *"status report"*, *"movie night"*. OK = listen again, ▼ = type instead,
Back = close. "Hey Google, open JARVIS" may also work (JARVIS is in the TV's app list while it's on).

Prefer two separate installs? `release/TCLMinimal-classic.apk` has no JARVIS code at all. Switching between the two
APKs: `adb install -r` the other one (same signature, settings kept). The toggle is the recommended way.

Honest limits: JARVIS is a phrase matcher + spoken templates on the TV, not an AI model. Speech recognition uses the
TV's own Google speech service; if the TV doesn't offer one to apps, JARVIS opens Google's speech screen, and failing
that lets you type. Open-ended conversation is the [companion-agent follow-up](NIGHT_REPORT.md#next-the-companion-agent).

## 7. Customize without rebuilding

Edit `config/config.json` on the Mac, then:

```bash
scripts/push-config.sh --ip 192.168.1.50          # applies at once; the home screen redraws
scripts/push-config.sh --ip 192.168.1.50 --pull   # what the TV is using now
```

Theme (background, focus zoom, corners), the top row, weather, pinned and hidden apps, JARVIS's name/address/voice/
wake keys, and the voice **commands** (phrases → actions) — see [README → Customize](README.md#customize). Settings you
change on the TV stay until the next push.

---

## Troubleshooting

**The TV never shows "Allow USB debugging?" / `unauthorized`.** Look at the TV — the prompt may be behind something;
press Home and run the script again. Still nothing: *Developer options → Revoke USB debugging authorizations*, then
`adb kill-server` on the Mac and run again.

**`Couldn't connect` / TV not found.**
- Same Wi-Fi? Right IP (it can change after a router restart)? TV on (not in standby)?
- USB debugging still on? (A TV update can turn Developer options off.)
- macOS **"No route to host"** while the TV is up: macOS's Local Network privacy is blocking adb's background server.
  *System Settings → Privacy & Security → Local Network* → allow your terminal app; or `adb kill-server` and run
  `adb nodaemon server &` in the same terminal, then retry. (From Free My TV's guide.)
- Some newer firmware only offers *Wireless debugging*: turn it on in Developer options, choose *Pair device with
  pairing code* and run `adb pair <IP:pairing port> <code>` with what that dialog shows; then
  `adb connect <IP:port>` with the (different) port on the *Wireless debugging* screen itself, then
  `adb -s <IP:port> tcpip 5555`. After that the scripts work with `--ip <IP>` until the next TV restart.

**Install hangs.** Look at the TV: Play Protect is asking (*More details → Install anyway*).

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** a Free My TV signed with another key is on the TV (the official release, or
one you built yourself) — *Free My TV → Home Screen → Original*, uninstall it (*Settings → Apps*), run again.

**The launcher doesn't stick / the switch never happens** (install-launcher.sh waits, then says so — nothing is
broken, Google TV is still Home):
- On TCL, a background killer can stop the switch while it measures: wait a minute or two, then press **Home**; Free
  My TV opening means it went through. Then `scripts/install-launcher.sh --ip … --no-switch` just to re-check.
- The settings screen says to forget and rejoin Wi-Fi: do that (step 3), switch again.
- It says it can't start in the background: run the script again (it re-allows that; an uninstall resets it).
- `adb logcat -s FreeMyTV` shows what the switch did and why it stopped.

**Black screen after a step.**
1. Wait 1–2 minutes, then press **Home**. (TCL's background killer can delay things; the watchdog switches back to the
   stock home by itself after 3 crashes in 5 minutes.)
2. ADB keeps working with a black screen. From the Mac: `scripts/rollback.sh --ip 192.168.1.50 --force` — it turns the
   Google TV home back on and makes it Home, then the setup app, screensaver and everything else, in that order.
3. Only the HDMI input is black? `scripts/rollback.sh` (the debloat part) — nothing on the list should touch inputs
   (they're checked), but it brings everything back.

**On the TCL 50Q651G (Q6) — model notes.** From two owner reports on this model line: a 50Q651G-CA (CleanYourTV PR
#78, not merged) and a 55Q651G (CleanYourTV `examples/tcl-g10-google-tv`). None of this was tested on your set.
- `detect.sh` will show model **Smart TV Pro** or similar: TCL doesn't put the retail model in what ADB reads. It
  recognizes the 50Q651G's platform instead (device `G10`, product `G10_4K_US_NF`, Android 12).
- **Black screen on Home** with Google TV's home off is Google's setup app (`setupwraith`) taking Home — both reports
  saw it. Free My TV's switch turns that app off itself, after its Wi-Fi check, so never disable Google TV's home by
  hand. Black anyway: *Black screen* above (`rollback.sh --force`).
- **Wi-Fi saved by the setup app**: on other `G10_4K_US_NF` sets (55QM5K, S551G) it was. `detect.sh` checks this
  (its "Wi-Fi owner check"); forget and rejoin the network before the switch.
- **Phone casting** can stop with the setup app off ("Untrusted device"; CleanYourTV #3446, a 65T69C — it still worked
  on a 55QM5K). If you cast, test it after the switch; *Home Screen → Original* brings it back.
- **After a restart the watchdog or boot check seems dead:** TCL's `TclAppBoot` lost the auto-start permission.
  `install-launcher.sh` now saves it (`appops write-settings`); by hand: `adb shell "appops set io.freemytv AUTO_START
  allow || appops set io.freemytv APP_AUTO_START allow; appops write-settings"`.
- **The 50Q651G-CA dropped off Wi-Fi/ADB for about a minute, twice in one session**, with no reboot. A script saying
  it can't reach the TV: wait a minute, run it again.
- Kept on purpose for this model: TCL Live (`channelplus`, Live TV's only source with no antenna), the remote's mic
  (`tv.remote.service`), the YouTube button's route (`partnercustomizer`), firmware updates (`ttvs`), AirPlay (both
  halves).

**JARVIS doesn't hear me.** Hold Back a bit longer (it needs the key to repeat). If it says speech isn't available,
it'll offer typing; check *Settings → Apps → Free My TV → Permissions → Microphone*. Wake keys can be added in
`config.json` (`jarvis.wake.keys`) if your remote's other buttons reach the home screen.

---

## Undo

**Everything, back exactly as it was:**

```bash
scripts/rollback.sh --ip 192.168.1.50 --dry-run    # see the plan
scripts/rollback.sh --ip 192.168.1.50
```

1. Turns back on every package `debloat.sh` turned off (only those; anything you'd turned off yourself before stays off).
2. Opens the launcher's settings on the TV (also reachable any time from the round **Settings** button, top right of
   the home screen): choose **Home Screen → Original**. Google TV's home, its setup app, screensaver and
   everything the switch turned off come back. (Can't? `--force` does it from the Mac — see *Black screen* above.)
3. Uninstalls the launcher (only once Google TV is Home again; `--keep-launcher` keeps it).
4. Compares the TV's package states with the snapshot from step 3 and prints any difference.

**Only the launcher:** on the TV, round **Settings** button → *Home Screen → Original*. **Only JARVIS:** the toggle (section 6).
**Only the debloat:** `adb shell pm enable <package>` for any one of them.

**Last resort — factory reset.** Only if the TV can't be reached any other way: Google TV's *Settings → System → About
→ Factory reset* (on some firmware under *About → Reset*) — it wipes the TV: apps,
accounts, settings. If the TV's own menus aren't reachable at all, TCL documents a
recovery-mode reset per model — follow TCL's support page for your exact model rather than guessing button sequences.
Nothing in this package should need it: nothing is uninstalled, and every change has an undo above.
