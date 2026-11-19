# Where the phone differs from spec/ on purpose

spec/ is the shared rulebook (the YT Music Extension's public repo is the master). Playback timing is
per platform: each platform sets how long its own players need. The phone's values are listed here.

- **The lead before `earliest`** (editor.md §6, the planner's `earliest` and the drag's lower limit):
  the phone uses the playhead + 6.5 s, the PC 4.5 s. On the phone it is `Automix.EARLIEST_LEAD` =
  `Mix.HAND_LEAD` (4 s, the takeover before the mix) + 2.5 s, which is how long the engine needs to get
  the incoming player ready: held silent, then lined up on YT's clock by a seek and speed nudges.
