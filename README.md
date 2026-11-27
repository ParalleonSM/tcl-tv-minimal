# tcl-tv-minimal

A minimalist, **Apple TV-style home screen** and a **sourced, reversible debloat** for a TCL Google TV — plus a
**JARVIS** voice mode and an agent-drivable command layer, both behind one toggle. Deployed from a Mac over ADB in
about 15–20 minutes. **[→ DEPLOY.md](DEPLOY.md)** is the step-by-step.

## What it is — and what it isn't

**It is:** ADB (Android's own debugging bridge, which the TV lets you switch on) used to
1. install a minimalist launcher — a fork of the open-source [Free My TV](https://github.com/simbasjungles/free-my-tv)
   — and make it the home screen through Free My TV's own safe switch;
2. turn off (`pm disable-user`, not uninstall) 13 bloat packages, each backed by at least two independent sources.

**It is not** a root exploit or a "jailbreak": no root, no bootloader unlock, nothing that voids the warranty, nothing
uninstalled from the system. Anything that would have needed root was left out. Every change has an undo
(`scripts/rollback.sh`), and the launcher has a one-press *Original* and a crash watchdog.

## What it changes on the TV

| | Change | Undo |
|---|---|---|
| Home screen | Free My TV fork becomes Home; Google TV's home, its setup app and screensaver are turned off by the launcher's own switch | *Settings button → Home Screen → Original*, or `rollback.sh` |
| Bloat | 13 packages turned off: retail demo, hotel mode, TCL promos/account nags/log upload, manuals, Play Games, YouTube Music, Google TV store app, TCL Alexa ([list](config/debloat.list), [sources](docs/RESEARCH.md)) | `rollback.sh`, or `adb shell pm enable <pkg>` |
| Permissions | the launcher may run in the background (TCL `appops`), use the microphone (JARVIS) | uninstalling the launcher (rollback does) |
| Untouched | Live TV and HDMI inputs, remote and Bluetooth, keyboard, Google Assistant and speech, TTS, Settings, Play Store, Chromecast, Wi-Fi — see [do-not-touch.list](config/do-not-touch.list) | — |

## The look

Free My TV is already minimalist (one glass shelf of apps, no ads, no recommendation rows). This fork defaults it to an
Apple TV-like setup: dark theme with a near-black background, **no recommendations** (a big clock instead of picks),
smaller tvOS-like focus zoom and corners, Live TV / Inputs / Settings as small round buttons, TCL's helper apps hidden.
All of it is in [`config/config.json`](config/config.json) — change it and `scripts/push-config.sh`, no rebuild.

**JARVIS layout** (on at first install): the same home screen with a slim JARVIS pill on top ("Good evening, sir ·
Hold Back to talk"), a deep-navy background and a cyan accent. **One toggle** gives the classic layout back exactly.

## Deploy · Undo

```bash
git clone https://github.com/ParalleonSM/tcl-tv-minimal.git && cd tcl-tv-minimal
scripts/detect.sh          --ip <TV IP>      # read-only: what TV is this? stops on Roku / Fire TV
scripts/install-launcher.sh --ip <TV IP>     # install, prove it runs, then switch (on the TV)
scripts/debloat.sh          --ip <TV IP>     # the 13 packages, with guards
scripts/rollback.sh         --ip <TV IP>     # everything back, exactly
```

Add `--dry-run` to any of them to print every command without touching the TV. Details, screenshots-in-words and
troubleshooting: **[DEPLOY.md](DEPLOY.md)**.

## Customize

`config/config.json` (sent with `scripts/push-config.sh --ip <TV IP>`; read back with `--pull`). The launcher merges it
over its built-in copy, so a file may hold only the keys it changes. Keys starting with `_` are notes.

| Key | Meaning |
|---|---|
| `theme.mode` | `dark` / `light` / `auto` (follows the sunset) |
| `theme.background_top`, `background_bottom` (+ `_day_` pair) | the plain background's gradient |
| `theme.focus_zoom` | how much the focused tile grows (0.05–0.35; upstream 0.20, Apple-like 0.14) |
| `theme.corner_radius` | tile corners in px at 1080p (0–48) |
| `theme.clock` | `auto` / `12` / `24` |
| `layout.top_row` | `none` (a clock, no recommendations) or `picks` (trending; needs a TMDB key, see upstream) |
| `layout.weather`, `markets`, `photos`, `status_icons`, `picks_count` | rows and widgets |
| `layout.top_buttons` | round buttons: `live`, `inputs`, `settings`, `recents`, `store` |
| `apps.pinned` | the shelf's apps, in order (packages); empty = Free My TV's defaults |
| `apps.hidden` | apps left out of the shelf and All Apps (still installed) |
| `jarvis.enabled` | **the toggle**: JARVIS layout (`true`) / classic (`false`) |
| `jarvis.name`, `address`, `hint`, `hud` | persona ("sir"), the pill's text, pill on/off |
| `jarvis.wake.hold_back`, `wake.keys` | hold Back to talk; extra Android key codes that wake it |
| `jarvis.voice.language`, `rate`, `pitch`, `name`, `recognition_language` | TTS and speech recognition |
| `jarvis.theme.*` | colours used only while JARVIS is on |
| `aliases` | spoken app names → packages |
| `commands` | the command registry: `{"id", "action", "args", "phrases"}` (below) |

Layout/theme keys are written into the launcher's own settings when those sections change; tweaks made on the TV
afterwards stick until the next push.

## The command layer (the "agentic backbone")

One interface for voice, the TV and external agents: a **registry of actions**, not hard-coded buttons.

- **Action types** (built in, `Commands.java`): `launch_app` (app), `hdmi` (input), `live_tv`, `home`, `settings`,
  `search` (query), `volume` (step / level / mute), `media` (key), `status`, `jarvis` (enabled), `macro` (run =
  ids…); JARVIS build adds `speak` (text), `listen`, `voices`. Meta: `utterance` (text → matched → run), `list`,
  `reload_config`.
- **Commands** (config): phrases with `{slots}` mapped onto an action and fixed args:
  `{"id": "hdmi", "action": "hdmi", "phrases": ["switch to hdmi {input}"]}`. Add a phrase, a new app shortcut, or a
  `macro` chaining existing commands — **no rebuild**. A new action *type* is one `Commands.register(...)` call.
- **From an agent** (Mac/VM, over ADB; only adb's shell may call it — no network listener on the TV):

```bash
scripts/tvctl.sh --ip <TV IP> status                    # {"ok":true,"time":"…","volume":"12",…}
scripts/tvctl.sh --ip <TV IP> list                      # every action type and command
scripts/tvctl.sh --ip <TV IP> run hdmi input=2
scripts/tvctl.sh --ip <TV IP> say "open netflix"        # as if spoken
scripts/tvctl.sh --ip <TV IP> speak "Dinner is ready, sir."
scripts/tvctl.sh --ip <TV IP> jarvis off
# raw: adb shell content call --uri content://io.freemytv.control --method command --arg '{"action":"status"}'
```

Replies are one JSON object: `ok`, `say` (a plain sentence) and facts. JARVIS turns the facts into its own words.

## JARVIS

- **Wake:** hold **Back** on the home screen (configurable extra keys; "Hey Google, open JARVIS" may work too).
- **Listens** with the TV's speech recognizer (falls back to Google's speech screen, then to typing), **matches** the
  phrase against the registry, **runs** it, **answers aloud** (TTS, en-GB, polite, "sir", status reports, a little
  wit). OK = listen again, ▼ = type, Back = close.
- **Separable:** JARVIS lives in `launcher/app/src-jarvis/` and reaches the home screen through a few small hooks
  (`JarvisHooks`); its HUD is a separate view over the home screen. Off → every hook is a no-op → the classic layout,
  exactly. The classic build swaps in empty hooks (`src-classic/`).
- **Honest limit:** this is a phrase matcher with spoken templates, running on the TV. It is not an LLM, and a full
  conversational JARVIS can't run on the TV alone. The command layer is built so a **companion agent** on the Mac/VM
  can drive it (`tvctl.sh`, or the raw `content call`) and speak through it — the planned next phase
  ([NIGHT_REPORT.md](NIGHT_REPORT.md#next-the-companion-agent)).

## Builds and flavors

| APK | What | Use |
|---|---|---|
| `release/TCLMinimal.apk` | **jarvis** flavor: classic launcher + JARVIS layer behind the toggle | the one to install |
| `release/TCLMinimal-classic.apk` | **classic** flavor: no JARVIS code, permission or components | if you want JARVIS physically absent |

Free My TV builds without Gradle (a ~250-line `build.sh`), so the flavors are **build.sh flavors**, not Gradle
ones: `./build.sh --flavor jarvis|classic` picks `app/src-jarvis` or `app/src-classic` and strips the manifest's
`JARVIS:BEGIN…END` blocks for classic. (Adding Gradle would have meant the Android Gradle Plugin, which on ARM64
Linux needs an x86 `aapt2` — not worth it for two flavors.) Both share the package `io.freemytv` and the signing key,
so either can replace the other with `adb install -r`.

### Build

```bash
# Mac: brew install --cask temurin android-commandlinetools; sdkmanager "build-tools;34.0.0" "platforms;android-34"
# ARM64 Linux (how the release was built): tools/setup-toolchain-arm64-linux.sh && . ~/tcltv-toolchain/env.sh
tools/build-release.sh          # both flavors -> release/ + SHA256SUMS
```

Built `--generic` (no personal settings, no keys: the city comes from the TV's time zone). The first build creates
`launcher/freemytv.keystore` (git-ignored). **Updates must be signed with the same key**: the release APKs were signed
with a key that stays on the build VM; an APK you build on the Mac gets a new key, so uninstall first
(`rollback.sh`, then install), or copy that keystore over.

### Tests

```bash
tests/run-script-tests.sh     # 81 checks: every script for real against tests/fake-adb, a pretend TCL Google TV
tests/run-launcher-tests.sh   # 282 checks: phrase matcher, JARVIS persona, config.json (JVM, no TV)
```

## Layout

```
config/       config.json (launcher), debloat.list, do-not-touch.list
scripts/      detect, install-launcher, debloat, rollback, push-config, tvctl (+ lib.sh)
launcher/     the Free My TV fork (app/src, app/src-jarvis, app/src-classic, build.sh); UPSTREAM.md
release/      TCLMinimal.apk, TCLMinimal-classic.apk, SHA256SUMS
tests/        fake-adb + script tests; JVM launcher tests
tools/        build-release.sh, setup-toolchain-arm64-linux.sh
docs/         RESEARCH.md (Free My TV, debloat sources, per-package evidence)
DEPLOY.md     the morning runbook        NIGHT_REPORT.md   what was verified, and what couldn't be
```

## Credits and license

- **Free My TV** by its authors — MIT ([launcher/LICENSE](launcher/LICENSE)); this repo's `launcher/` is a fork
  (see [launcher/UPSTREAM.md](launcher/UPSTREAM.md); the first `launcher/` commit is upstream unmodified).
- Debloat guards ported from **CleanYourTV** (`tools/disable.sh`) and Free My TV (`HomeMode`), cited inline.
- Package evidence: CleanYourTV, imaginator/TCL-TV-Debloat, livvaa/TCL-Google-TV-Debloat-Optimizer,
  AdrianSantamariaR/tcl-tv-debloat — see [docs/RESEARCH.md](docs/RESEARCH.md).
- This repo's own code: MIT ([LICENSE](LICENSE)).
