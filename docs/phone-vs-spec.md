# Where the phone differs from spec/ on purpose

spec/ is the shared rulebook (the YT Music Extension's public repo is the master). Playback timing is
per platform: each platform sets how long its own players need. The phone's values are listed here.

- **The lead before `earliest`** (editor.md §6, the planner's `earliest` and the drag's lower limit):
  the phone uses the playhead + 6.5 s, the PC 4.5 s. On the phone it is `Automix.EARLIEST_LEAD` =
  `Mix.HAND_LEAD` (4 s, the takeover before the mix) + 2.5 s, which is how long the engine needs to get
  the incoming player ready: held silent, then lined up on YT's clock by a seek and speed nudges.
  spec/automix/editor.md §6 lists each platform's lead (extension 610a643); editor-cases.json's
  `plans` give `earliest` = pos + 4.5 s as a fixture input, and EditorCasesTest passes it as given.
- **A pause between our Next and the new song** (playback.md, the queue during a mix, rule 3): the
  PC waits out the ad flag for 2 s and stops the mix only on a settled pause (readyState ≥ 3, not
  seeking). The phone has no readyState: its pause hook (Morphe's `onPauseVideo`) also fires for our
  own media Next, 10-50 ms before it. So the phone ignores a pause for 3 s after our Next, then asks
  YT's media session 1.5 s later and stops the mix only if it still says paused (buffering is no
  pause). `Automix.onPauseVideo`, dc2e613.
