# Analysis cases

The phone and the extension each run their own song analysis (beats, downbeats, tempo, key, vocals).
These cases check the analysers and the mixes they lead to. Cases and thresholds:
`analysis-cases.json`. Runner on the extension: `scripts/analysis_cases.mjs`.

## Rules (decided 2026-11-18 and 2026-11-19)

1. **Neither side is tuned to copy the other.** Each platform runs its analyser as it is: its own
   resampler and model runtime. A case that fails is a finding to look at, not a number to chase.
2. **Two layers**, both strict:

   **Layer 1: the analysers agree.** Both sides analyse the same decoded file of each song, so this
   tests the analysers and not the streams.

   | What | Must |
   |---|---|
   | Tempo | equal within 0.5 BPM; a half or double reading fails |
   | Beats | at least 95% of each side's beats within 20 ms of a beat of the other side |
   | Downbeat | the same bar start on at least 90% of bars (every 4th beat from each side's first downbeat) |
   | Vocals | sung frames (the vocal curve at 0.25 or more, the planner's `VOCAL_SUNG`) overlap with an IoU of at least 0.8 |

   **Layer 2: the mixes agree.** Each pair of `pairs/` is planned on each side's analysis (the analyser's
   fields swapped into the captured pair, everything else as captured). For each engine (Classic and
   Automix v2) both must make the same loop / blend / hand-over choice, with mix points within one beat.

   One exception: when the deciding number sits within 5% of a planner threshold, the pair is reported
   as a **threshold case**, not a failure, and the report shows the number and the threshold. The
   thresholds are listed in `analysis-cases.json` (LOOP_MARGIN 0.02, the key score 0.55,
   V2_SUB_BASS_VETO 0.9).
3. **Real songs are allowed.** They make the best test. Their audio stays out of git:
   - Each song is decoded once into a local cache (the extension's is `.cache/analysis/`, git-ignored)
     as `<videoId>.wav`: 44100 Hz, stereo, 32-bit float, from `ffmpeg -i <audio> -ar 44100 -ac 2 -c:a pcm_f32le`.
   - Both sides read that same file.
   - Each side writes its analysis to `<cache>/<side>/<videoId>.json`, with the analysis.schema.json
     field names.
   - `--compare` checks one side's folder against the other's.
   - These cases run on the developer's machine, not in GitHub CI. The songs: every song of
     `pairs/*.json`, plus `real.extra`.
4. **Synthetic songs run everywhere,** CI included, against the truth they were built from (the same
   tolerances as layer 1). `scripts/analysis_signals.mjs` renders them, and its comments spell out every
   sound: mulberry32 noise, a click track, a drum kit with a bar marker, a voice-like tone, a chord pad.
   Each song is rendered at 44100 Hz for the analysis and at 22050 Hz for the beat model; both rates are
   rendered directly, with no resampler between them.

## Known misses (found 2026-11-19, kept as found)

On the extension's analyser (BitChord's worker with `beat_this_final0_q8` and `vocals_umxhq_int8`).
A known miss prints as KNOWN and doesn't fail; a side that gets it right should say so.

- `drums-85`: tempo and beats are right, but the bar start is half a bar late.
- `no-voice`: noise snares and hats read as voice frame by frame (about 29% of frames at 0.25 or more).
- `voice-over-chord`: a held sine chord reads as voice (92% of frames).

The last two matter to the loop planner when a song has no synced lyrics: it allows at most 3% sung
frames over a loop. A song whose drums or pads trip the vocal model loses its loop mixes.

## Running

```
node scripts/analysis_cases.mjs                         synthetic cases (about a minute)
node scripts/analysis_cases.mjs --real                  analyse .cache/analysis/*.wav into .cache/analysis/pc/
node scripts/analysis_cases.mjs --compare pc phone      layers 1 and 2, PC against the phone's results
```
