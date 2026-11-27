# Night report — Thu Nov 26 → Fri Nov 27, 2026

Built on the dev VM (Ubuntu, ARM64), 22:30–23:30 CST. **No TV was reachable from the VM, and there is no emulator
(no KVM)**: nothing here has run on a real TV or on Android at all. Below, "verified" means verified on the VM, by the
means stated.

## In one paragraph

The custom launcher **was built** (no fallback needed): a fork of Free My TV v1.5.0 with a config file, an agent
command layer, and a first working slice of the JARVIS voice mode behind a one-tap toggle, as one APK
(`release/TCLMinimal.apk`, JARVIS on) plus a classic-only APK. The four ADB scripts (+ two helpers) have `--dry-run`,
pass shellcheck, and pass an 81-check run against a simulated TCL Google TV, including install → switch → debloat →
rollback ending with the TV's package states identical to the start. What's unproven is everything that needs the
real TV: the pairing, which packages his model has, whether the launcher switch sticks, and whether the fork's new code
behaves on Android the way it compiles.

## Verified

| What | How | Result |
|---|---|---|
| ARM64 Android toolchain, in `~/tcltv-toolchain` only | built **unmodified** upstream v1.5.0 first | APK `io.freemytv` 1.5, v2+v3 signed, aligned |
| Launcher fork builds (both flavors) | `tools/build-release.sh` | `release/TCLMinimal.apk`, `release/TCLMinimal-classic.apk` + `SHA256SUMS` |
| Builds clean from a **fresh checkout** | `git clone` from GitHub into an empty dir → `tools/build-release.sh` | both build; `aapt2 dump badging`: `package: name='io.freemytv' versionName='1.5-tcl.1'`, launcher activity `io.freemytv.SettingsActivity` (leanback too); `apksigner verify` OK |
| Flavors are really separate | manifest tree + `classes.dex` scan | classic: no `JarvisActivity`/`Voice`/`JarvisHud`, no `RECORD_AUDIO`, no JARVIS components; both have `Control`, `Commands`, `UserConfig` |
| No API newer than minSdk 26 added | compiled all sources against `android-26` (upstream's own check) | every flagged call is upstream's (guarded); none in new code |
| Launcher logic (pure Java) | `tests/run-launcher-tests.sh` (JVM + org.json) | **282 passed, 0 failed**: phrase→command routing for 24 utterances, every configured phrase routes to its own command (no shadowing), slot extraction, number words, config.json schema/aliases/macros, persona wording |
| Scripts: shellcheck | `shellcheck -x scripts/*.sh tools/*.sh tests/*` | clean (upstream's `launcher/build.sh` left as upstream wrote it) |
| Scripts: `bash -n` | all | clean |
| Scripts: `--dry-run` | ran each, read the output line by line | prints exactly the intended `adb` commands in order; sends nothing (test: no adb call at all, no state written) |
| Scripts: real logic | `tests/run-script-tests.sh` vs `tests/fake-adb` | **81 passed, 0 failed** (below) |
| Debloat list sourcing | 5 repos read at pinned commits; matrix computed from their files | 13 safe packages, each ≥ 2 sources and protected by none — table in `docs/RESEARCH.md` |

The 81 script checks include: launcher proven (started, on screen, command layer answers, Home still stock) **before**
the switch; the Mac never disables the stock launcher itself; debloat skips what's already off and records only what
it did; stops at the first unexpected failure and rollback undoes the partial run; Wi-Fi-owner, input-hardware and
Home guards; refusal of core / do-not-touch / factory / non-package lines before any TV call; Fire TV, Android TV and
**Roku** (a real HTTP probe on port 8060) stop with a clear message; `rollback --force` restores in Free My TV's order
(launcherx on and Home before setupwraith), screensaver and accessibility service cleaned, launcher uninstalled;
rollback refuses to uninstall the launcher while it's still Home; signature conflict and a never-happening switch
explained; agent calls and config push; dry-run side-effect-free.

**Bug found and fixed by that harness:** `adb shell` passes the TV's exit status through, so any refused command
(e.g. `appops` on firmware without that op) killed the script under `set -e` before it could explain. That would have
bitten on the real TV.

## NOT tested — needs the real TV (please read before deploying)

- **ADB pairing on his TV**: the "Allow USB debugging?" flow, port 5555 being open with *USB debugging* on (true for
  TCL per Free My TV; some firmware may need *Wireless debugging* — DEPLOY.md covers both).
- **The disable list on his exact model**: which of the 13 packages exist there. Missing ones are skipped by design;
  the guards (Wi-Fi owner, input hardware, Home) run on the TV itself, so they judge his firmware, not a guess.
- **Whether the launcher sticks as default**: that's Free My TV's own switch (unchanged), which its authors report
  working on TCL Google TVs — but not verified on his set tonight. TCL's background killers are the known risk
  (DEPLOY.md → Troubleshooting).
- **Whether the fork's new code runs correctly on Android** — it compiles, but none of it has executed:
  the config file → Prefs mapping and the theme hooks in HomeView; the Settings row (drawn list grows from 9 to 10
  rows); the `Control` provider (`content call` / `content write` from adb); the JARVIS HUD, the hold-Back wake, the
  voice activity, TTS, and the command actions (HDMI switching via the TV-input intent, volume via AudioManager — may
  not move a soundbar's volume over eARC/CEC —, media keys, search intents). Safety net if it misbehaves: Free My
  TV's watchdog reverts Home after 3 crashes in 5 min; the classic APK; `rollback.sh`.
- **Speech recognition availability**: whether TCL's Google TV exposes a recognition service to third-party apps.
  JARVIS falls back to Google's speech screen, then typing.
- **Starting apps from an agent call while the launcher isn't in front**: Android's background-activity-start rules
  apply; the home app is usually exempt, but not verified. Voice-initiated actions start from the JARVIS screen,
  which is in front, so they're not affected.
- **"Hey Google, open JARVIS"** — plausible (JARVIS is in the TV's app list), unverified.
- **macOS**: the scripts are written for macOS's bash 3.2 and BSD tools and avoid bash-4 features, but were run only on
  Linux bash 5 here.

## Fallbacks and deviations (and why)

- **No launcher fallback needed.** Free My TV builds without Gradle, so only aapt2/zipalign needed ARM64 binaries:
  Ubuntu's own arm64 AOSP builds, extracted into `~/tcltv-toolchain` with `dpkg -x` (no system install, no sudo).
- **Gradle flavors → build.sh flavors.** Upstream has no Gradle; adding AGP on ARM64 Linux needs an x86 aapt2.
  `./build.sh --flavor jarvis|classic` gives the same result (two APKs from one codebase).
- **The switch is done on the TV, not by the script.** The brief says install-launcher.sh sets the default; Free My
  TV's guide says not to switch from the computer, because its in-app switch does the dangerous part safely (setup app
  before home, inputs and Wi-Fi checks, record for Original, watchdog). So the script proves the launcher, opens the
  switch screen, waits, and verifies. The safety order (stock launcher off only after the replacement is proven) holds.
- **Disable, not uninstall-for-user**: `pm disable-user --user 0` keeps app data and is undone by `pm enable`.
- **Package and name kept as `io.freemytv` / "Free My TV"**: upstream's switch, watchdog, ADB commands and docs use
  the package name throughout; renaming risks the very safety machinery. Consequence: the official Free My TV release
  can't be installed alongside (different key) — uninstall one before the other.
- **Upstream's self-updater is inert** here (it only runs for APKs signed with upstream's release key).
- **Repo is public** (so the Mac can `git clone` without credentials). It holds no keys, tokens, IPs or personal data.
- **Not consulted:** XDA / TCL forums (the GitHub sources were concrete, versioned and enough for every listed package).

## Things to know

- **Signing key:** the release APKs are signed with a key that lives only on the VM: `~/tcltv-keystore/freemytv.keystore`
  (copy of the git-ignored `launcher/freemytv.keystore`; password `freemytv`, upstream's default for self-builds).
  Cert SHA-256 `c05ddc8916150be430af919529717bb52dc554756749132ba4135820b1242301`. A build made elsewhere gets another
  key → uninstall before installing it, or copy this keystore over.
- **If the fork misbehaves tomorrow:** (1) toggle JARVIS off; (2) install `release/TCLMinimal-classic.apk`
  (`adb install -r`, same key); (3) `scripts/rollback.sh`; (4) the official Free My TV release
  (github.com/simbasjungles/free-my-tv/releases/latest, `FreeMyTV.apk`) is the untouched fallback — uninstall ours
  first (different key).

## Next: the companion agent

JARVIS on the TV is deliberately small: a phrase matcher and spoken templates, no language model — a full
conversational JARVIS can't run on the TV alone. The backbone is built so the next phase can live on the Mac or the VM:

1. **Tools from the registry:** `tvctl.sh list` returns every action type with its arguments and every configured
   command — turn that into an LLM's tool definitions (one tool per action type).
2. **Acting:** the agent calls `adb shell content call --uri content://io.freemytv.control --method command --arg
   '<json>'` (or `tvctl.sh run …`) and gets JSON back (`ok`, `say`, facts) — no network listener on the TV; only adb's
   shell can call it.
3. **Talking:** `speak` makes JARVIS say the agent's words on the TV; `status` gives it context (time, network,
   volume, apps, mode). Open-ended dialogue, memory and proactive suggestions happen in the agent.
4. **Hearing:** today speech is matched on the TV. Next: when no phrase matches, hand the transcript to the agent
   (a `fallback` hook in `JarvisActivity.answer`, e.g. a queued file the agent polls over adb) instead of
   "I didn't catch that".

## Time log (CST)

22:30 toolchain (aapt2/zipalign from Ubuntu arm64, Google's Java tools, JDK) · 22:35 baseline upstream build ·
22:40 research: Free My TV internals, 5 debloat sources, corroboration matrix · 22:45 scripts + lists ·
22:55 launcher: config, command layer, Control, JARVIS, flavors · 23:00 JVM tests · 23:05 fake-TV harness (found the
exit-status bug) · 23:10 docs, dry-run review, fresh-checkout build · 23:20 this report.
