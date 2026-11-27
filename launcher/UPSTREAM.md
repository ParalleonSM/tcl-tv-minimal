# Upstream

`launcher/` is a fork of **Free My TV** — https://github.com/simbasjungles/free-my-tv (MIT, © its authors; see
`LICENSE`), imported at upstream commit `0e33f168b8` (release v1.5.0, 2026-11-13).

Imported: `app/`, `build.sh`, `config.example.properties`, `LICENSE`, `tools/translate.py`, the upstream README
(`UPSTREAM_README.md`) and its AI-assistant guide (`CLAUDE.md`). Not imported: the LG/Samsung web app (`web/`,
`webos/`, `tizen/`), the website (`docs/`) and upstream CI — this project targets one TCL Google TV.

The first commit touching `launcher/` is the unmodified import, so `git diff <that commit> -- launcher/` shows every
change this fork makes.
