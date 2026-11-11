# YT Music patches (work in progress)

A patch bundle for YouTube Music, applied next to the official Morphe bundle in Morphe Manager.
It is not a fork of Morphe: it adds its own patches and calls into Morphe's where needed.

**Not ready for use yet.** Targets YouTube Music 9.30.53 with Morphe 2.2.0.

What it adds:
- A player screen laid out like BitChord's, using YT Music's own views: artwork mesh backdrop, lyric line, volume row, output device.
- Animated album art (Apple Music motion art) and square covers for songs that play as music videos.
- Automix: beat-matched transitions between songs (BitChord's analysis engine), on top of Morphe's crossfade.
- Full offline: downloads keep their lyrics and animated art.
- Audio stats, equalizer presets.

Add to Morphe Manager (once released): `https://github.com/ayhamo/ytmusic-patches`

## Layout
- `bundle/` – the patch bundle (Morphe patches template): `patches/` and `extensions/`.
- `scripts/` – local build, patch and emulator helpers; `docker/` – the build toolbox image.
- `watcher/` – the daily check of the Apple Music token scrape (GitHub Action).
- `tests/` – Python tests (`python -m unittest discover -s tests`); Java tests are in the extension.
- `PLAN.md`, `docs/` – plan and notes.

Releases are made by hand with the **Release** workflow; it updates `patches-bundle.json`, which Manager reads.
