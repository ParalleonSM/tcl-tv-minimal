# Research notes (Nov 26–27, 2026)

Everything below was read on the VM from the sources' own repositories at the commits listed; nothing here is from
memory. Where sources disagree, this package takes the safer side and says so.

## Free My TV (the launcher this package forks)

- **Repo:** https://github.com/simbasjungles/free-my-tv — MIT. Latest release **v1.5.0** (2026-11-13), commit
  `0e33f168b8`, imported unmodified as the first commit of `launcher/`.
- **What it is:** a hand-drawn (one Canvas), ad-free home screen for Google TV / Android TV, in plain Java, built
  **without Gradle** by `build.sh` (aapt2 → javac → d8 → zipalign → apksigner). ~24k lines.
- **How it makes itself the default home** (`HomeMode.java`, README "How it works"): the app connects to the TV's
  **own ADB at 127.0.0.1:5555** with its own key (the second *Allow USB debugging?* prompt) and runs, from inside the
  TV, `cmd package set-home-activity io.freemytv/.HomeActivity`, `settings put secure screensaver_components …` and
  `pm disable-user --user 0` for the stock home (`com.google.android.apps.tv.launcherx`), Google TV's setup app
  (`com.google.android.tungsten.setupwraith` — turned off *before* the home, back on *after* it, or its
  RecoveryActivity takes Home), the stock screensaver, and its `DEBLOAT` list. It records what it turned off in
  `settings global free_my_tv_disabled`. **Original** undoes it all in the reverse order; a **watchdog** in another
  process reverts to the stock home after 3 crashes in 5 minutes; a boot check keeps the choice after TV updates.
- **Guards it applies before any disable:** a `PROTECTED` set (picture, sound, inputs, remote, store…), Android core
  packages (`CORE`), factory menus (`FACTORY`), anything requesting `TV_INPUT_HARDWARE` or receiving the remote's Input
  key (`neededByTv`), and apps that saved a Wi-Fi network (`wifiOwners`).
- **Install steps (README "The ready APK"):** Developer options (press *Android TV OS build* 7×) → USB debugging →
  `adb connect <IP>:5555` → `adb install -r FreeMyTV.apk` (Play Protect may ask) → on TCL,
  `appops set io.freemytv AUTO_START allow || … APP_AUTO_START allow` → on the TV, *Free My TV → Home Screen → Free My
  TV* and accept the debugging prompt. Its guide insists: **don't switch modes from the computer** — which is why
  `install-launcher.sh` proves the launcher and then hands the switch to the TV instead of disabling the stock
  launcher itself.
- TCL specifics in its notes: TCL's `TclAppBoot` blocks background jobs (hence the appops line); some TCL firmware's
  `TGuard` can kill the switch mid-way (wait, press Home); TCL's Settings key sends `KEYCODE_NOTIFICATION`.

## Debloat sources

| Key | Source | Read | What it is |
|---|---|---|---|
| FMTV | simbasjungles/free-my-tv `HomeMode.java` (`DEBLOAT`, `PROTECTED`) | `0e33f168b8` | launcher with a TCL list and a protected list, tested on TCL Google TVs |
| CYTV | Gathin23/cleanyourtv `examples/*tcl*/debloat*.txt` (95 TCL lists) + `tools/disable.sh` guards | `be6087056a` | large community project (≈61k stars), per-model lists with "keep" notes |
| IMAG | imaginator/TCL-TV-Debloat `disable-tcl-bloatware.sh` | `e069e413ae` | small TCL script (aggressive: includes setup/factory packages) |
| LIVV | livvaa/TCL-Google-TV-Debloat-Optimizer `tcl-debloat-optimizer.ps1` | `a10680aa23` | TCL Google TV script (aggressive: uninstalls, incl. setup/providers) |
| ADRI | AdrianSantamariaR/tcl-tv-debloat `data/catalog.json` | `9a05dbd73e` | TCL package catalog with labels and a `protected` flag |

IMAG and LIVV are used only to **corroborate** that a package exists on TCL TVs and is considered bloat — never as
evidence that something is safe (both remove things FMTV/CYTV say must stay). Safety comes from FMTV's `PROTECTED`,
ADRI's `protected`, and CYTV's per-model "keep" notes and guards.

Not consulted tonight: XDA threads and TCL's community forum (the GitHub sources above were concrete, versioned and
cross-checkable; forum posts weren't needed for any package on the list). Worth a look if the list grows.

**Rule for the safe list:** disabled by ≥ 2 independent sources, protected by none, and a promo / demo / telemetry /
manual / unused-extra app. The guards in `scripts/lib.sh` and `scripts/debloat.sh` are ported from FMTV and CYTV and
cite them inline.

### Safe (active in config/debloat.list)

| Package | Free My TV `DEBLOAT` | CleanYourTV TCL lists: disable (left on) | imaginator | livvaa | Protected by FMTV/ADRI |
|---|---|---|---|---|---|
| `com.tcl.esticker` | ✓ | 80 (1 left on) | ✓ | ✓ | — |
| `com.tcl.exhibit` | ✓ | 24 (4 left on) | ✓ | ✓ | — |
| `com.tcl.hotelmenu` | ✓ | 19 (2 left on) | ✓ | · | — |
| `com.tcl.messagebox` | ✓ | 66 (6 left on) | ✓ | ✓ | — |
| `com.tcl.usercenter` | ✓ | 57 (11 left on) | ✓ | ✓ | — |
| `com.tcl.logkit` | ✓ | 37 (3 left on) | ✓ | ✓ | — |
| `com.google.android.feedback` | · | 76 (2 left on) | · | ✓ | — |
| `com.tcl.repairguide` | ✓ | 29 (1 left on) | ✓ | ✓ | — |
| `com.tcl.ocean.instructions` | ✓ | 37 (2 left on) | ✓ | ✓ | — |
| `com.google.android.play.games` | ✓ | 64 (2 left on) | · | ✓ | — |
| `com.google.android.youtube.tvmusic` | ✓ | 37 (4 left on) | · | ✓ | — |
| `com.google.android.videos` | · | 51 (8 left on) | · | ✓ | — |
| `com.tcl.smartalexa` | · | 43 (11 left on) | ✓ | ✓ | — |

### Optional (commented out; your call)

| Package | Free My TV `DEBLOAT` | CleanYourTV TCL lists: disable (left on) | imaginator | livvaa | Protected by FMTV/ADRI |
|---|---|---|---|---|---|
| `com.tcl.magiconnectfree` | ✓ | 14 (12 left on) | ✓ | ✓ | — |
| `com.tcl.miracast` | ✓ | 18 (17 left on) | · | · | — |
| `com.tcl.ui_mediaCenter` | · | 38 (14 left on) | ✓ | ✓ | — |
| `com.tcl.gallery` | · | 17 (7 left on) | ✓ | · | — |
| `com.google.android.syncadapters.calendar` | · | 38 (3 left on) | · | ✓ | — |

### Disabled by some sources but kept ON here (conflicting evidence, or needed)

| Package | Free My TV `DEBLOAT` | CleanYourTV TCL lists: disable (left on) | imaginator | livvaa | Protected by FMTV/ADRI |
|---|---|---|---|---|---|
| `com.tcl.channelplus` | · | 36 (6 left on) | ✓ | ✓ | **protected** |
| `com.tcl.ttvs` | · | 19 (22 left on) | · | ✓ | **protected** |
| `com.tcl.partnercustomizer` | · | 57 (13 left on) | ✓ | ✓ | **protected** |
| `com.tcl.waterfall.overseas` | · | 60 (2 left on) | ✓ | ✓ | **protected** |
| `com.tcl.guard` | · | 52 (13 left on) | ✓ | ✓ | **protected** |
| `com.tcl.tv.tclhome_passive` | · | 48 (2 left on) | ✓ | ✓ | **protected** |
| `com.tcl.dashboard` | · | 39 (15 left on) | ✓ | ✓ | **protected** |
| `com.tcl.t_solo` | · | 26 (13 left on) | ✓ | ✓ | **protected** |
| `com.tcl.interactive` | · | 19 (7 left on) | ✓ | ✓ | **protected** |
| `com.tcl.useragreement` | · | 30 (4 left on) | ✓ | ✓ | **protected** |
| `com.tcl.hearaid` | · | 16 (9 left on) | ✓ | ✓ | **protected** |
| `com.tcl.gamebar` | · | 16 (16 left on) | ✓ | · | **protected** |
| `com.google.android.tungsten.setupwraith` | · | 45 (10 left on) | · | ✓ | **protected** |
| `com.google.android.apps.tv.launcherx` | · | 19 (4 left on) | · | · | **protected** |
| `com.google.android.katniss` | · | 41 (20 left on) | · | · | **protected** |
| `com.google.android.tts` | · | 13 (4 left on) | · | ✓ | — |

The kept-on table is why `do-not-touch.list` exists: e.g. CleanYourTV disables `com.tcl.channelplus` on many TCL
lists, but Free My TV protects it because with it off TCL's Live TV shows a black screen; `com.google.android.katniss`
and `com.google.android.tts` are kept for JARVIS (speech recognition and text-to-speech).

## Building on ARM64

Google's Linux build-tools ship x86_64 `aapt2`/`zipalign` only. Ubuntu packages AOSP's own `aapt2` and `zipalign` for
arm64 (`aapt` 1:14~beta1, `zipalign` 1:10.0.0); with Google's Java tools (`d8`, `apksigner`) and `android.jar` they
build Free My TV unchanged — verified by building upstream v1.5.0 before forking it (`aapt2 dump badging` →
`io.freemytv` 1.5, v2+v3 signature, zip-aligned). Script: `tools/setup-toolchain-arm64-linux.sh`.
