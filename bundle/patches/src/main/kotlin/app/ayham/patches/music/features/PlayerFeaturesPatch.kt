package app.ayham.patches.music.features

import app.ayham.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.mutableClassDefByOrNull
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXmlOrNull
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val FEATURES = "Lapp/ayham/extension/music/features/Features;"
private const val AUTOMIX = "app.ayham.extension.music.automix"
private const val SEEK_ACCESS = "Lapp/ayham/extension/music/features/SeekAccess;"

private const val LYRICS_GUARD = "Lapp/ayham/extension/music/features/LyricsGuard;"
private const val MORPHE_LYRICS_MATCH = "Lapp/morphe/extension/music/patches/lyrics/LyricsMatch;"
private const val MORPHE_LYRICS_CACHE = "Lapp/morphe/extension/music/patches/lyrics/storage/LyricsCache;"
private const val MORPHE_LYRICS_MODEL = "Lapp/morphe/extension/music/patches/lyrics/model/Lyrics;"
private const val MORPHE_CROSSFADE = "Lapp/morphe/extension/music/patches/CrossfadePatch;"
private const val MORPHE_EXO_ACCESS = "Lapp/morphe/extension/music/patches/CrossfadePatch\$ExoPlayerAccess;"
private const val MORPHE_STREAMING = "Lapp/morphe/extension/shared/spoof/requests/StreamingDataRequest;"
private const val MORPHE_RESOLVER = "Lapp/morphe/extension/shared/spoof/requests/StreamingDataRequest\$VideoIdResolver;"

internal object MusicActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/**
 * The player and everything around it: the BitChord screen (fullscreen and animated covers, artist
 * colours, lyric line, stats), automix, the song version of music videos, the equalizer, and the
 * "BitChord fork" screen in Morphe's settings.
 * Everything that touches Morphe's own classes runs in finalize, which comes after every
 * patch's execute, so Morphe's extension classes and its ExoPlayer bridge are already there.
 * Each hook is optional: if its target is missing it logs and skips, the build never fails.
 */
val playerFeaturesPatch = patch(
    name = "BitChord player and automix",
    description = "BitChord's now-playing screen (fullscreen and animated covers, artist colours, lyric line, stats), automix, song versions of music videos and an equalizer. Settings: Morphe settings > BitChord fork.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    extendWith("extensions/extension.mpe")

    val log = Logger.getLogger("PlayerFeatures")

    execute {
        MusicActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $FEATURES->onActivityCreate(Landroid/app/Activity;)V",
        )
    }

    finalize {
        // Item 2a: Morphe's crossfade reads its length here; the trigger point is length + buffer.
        mutableClassDefByOrNull(MORPHE_CROSSFADE)?.let { crossfade ->
            crossfade.methods.firstOrNull { it.name == "getCrossfadeDurationMs" && it.returnType == "I" }
                ?.let { method ->
                    val returns = method.instructions.withIndex()
                        .filter { it.value.opcode == Opcode.RETURN }
                        .map { it.index to (it.value as OneRegisterInstruction).registerA }
                    returns.reversed().forEach { (index, register) ->
                        method.addInstructions(
                            index,
                            """
                                invoke-static { v$register }, $FEATURES->overrideCrossfadeDurationMs(I)I
                                move-result v$register
                            """,
                        )
                    }
                    log.info("Features: hooked getCrossfadeDurationMs (${returns.size} returns)")
                } ?: log.warning("Features: getCrossfadeDurationMs not found")

            // Automix: a pause (or the first step of a skip) stops a running mix.
            crossfade.methods.firstOrNull { it.name == "onPauseVideo" && it.parameterTypes.isEmpty() }?.let { method ->
                method.addInstruction(0, "invoke-static {}, Lapp/ayham/extension/music/automix/Automix;->onPauseVideo()V")
                log.info("Features: hooked onPauseVideo")
            } ?: log.warning("Features: onPauseVideo not found")

            // Item 2b: the incoming player, right when Morphe starts fading it in.
            crossfade.methods.firstOrNull {
                it.name == "onPendingPlayerReady" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(MORPHE_EXO_ACCESS)
            }?.let { method ->
                method.addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $FEATURES->onIncomingPlayerReady(Ljava/lang/Object;)V",
                )
                log.info("Features: hooked onPendingPlayerReady")
            } ?: log.warning("Features: onPendingPlayerReady not found")
        } ?: log.warning("Features: Morphe crossfade class not in app, crossfade hooks skipped")

        // Item 3: seek on YT's ExoPlayer. Morphe already adds speed (patch_setPlaybackParameters).
        var exoImplType: String? = null
        classDefForEach { classDef ->
            if (MORPHE_EXO_ACCESS in classDef.interfaces) exoImplType = classDef.type
        }
        // Settings: our own rows in Morphe's settings screen (both the icon and plain variants).
        listOf("morphe_prefs.xml", "morphe_prefs_icons.xml", "morphe_prefs_icons_bold.xml").forEach { name ->
            resourceXmlOrNull("res/xml/$name")?.use { xml ->
                val root = xml.elements("PreferenceScreen").first()
                // All our settings in a screen of their own (Morphe opens nested screens itself); the
                // keys stay the same, so nothing set before is lost.
                root.insertChild(0, "PreferenceScreen") {
                setAttribute("android:key", "ayham_screen")
                setAttribute("android:title", "BitChord fork")
                setAttribute("android:summary", "BitChord's player screen, automix, animated covers, artist colours, lyric line, downloads and equalizer")
                // One switch per feature, stored by Morphe's settings screen in its own prefs file.
                appendChild("PreferenceCategory") {
                    setAttribute("android:key", "ayham_features")
                    setAttribute("android:title", "Player")
                    listOf(
                        Triple("ayham_ui", "BitChord player screen", "The now-playing screen laid out like BitChord: info lines, title, lyric line, seek bar, big controls, volume and output. YT's own buttons and menus stay underneath, so Morphe's settings still apply to them."),
                        Triple("ayham_fullArt", "Fullscreen cover art", "On the BitChord screen in portrait, the cover fills the top of the screen and fades into the colours below while a song plays (or its animated cover shows); paused, it goes back to a card. Also in the player's … menu."),
                        Triple("ayham_artistArt", "Artist colours", "The player takes the artist's colours and photo from their Apple Music page."),
                        Triple("ayham_canvas", "Animated covers", "The album's motion art from Apple Music plays muted over YT's art, where the album has one. Also in the player's … menu."),
                        Triple("ayham_lyricLine", "Lyric line", "The current synced lyric line under the title (Morphe's lyrics; needs its third-party lyrics on). Tap it to open the lyrics."),
                        Triple("ayham_stats", "Stats lines", "The audio format (codec, bitrate, channels, itag) and automix's status for this and the next song."),
                        Triple("ayham_autoSong", "Always play the song version", "A music video plays as its album song, from anywhere: search, home, playlists, radio."),
                        Triple("ayham_native", "Offline lyrics and art", "Save lyrics and the animated cover along with each downloaded song, for offline play."),
                        Triple("ayham_eq", "Equalizer", "A preset on YT's audio output and on automix's mixes. A change applies at once."),
                    ).forEach { (key, title, summary) ->
                        appendChild("SwitchPreference") {
                            setAttribute("android:key", key)
                            setAttribute("android:title", title)
                            setAttribute("android:summary", summary)
                            setAttribute("android:defaultValue", if (key == "ayham_eq") "false" else "true")
                        }
                        if (key == "ayham_canvas") appendChild("app.ayham.extension.music.features.ClearCanvasCachePreference") {
                            setAttribute("android:key", "ayham_clear_canvas_cache")
                            setAttribute("android:dependency", "ayham_canvas")
                        }
                    }
                    appendChild("$AUTOMIX.AutomixVersionPreference") {
                        setAttribute("android:key", "ayham_eq_preset")
                        setAttribute("android:title", "Equalizer preset")
                        setAttribute("android:summary", "%s")
                        setAttribute("android:defaultValue", "bass")
                        setAttribute("android:dependency", "ayham_eq")
                    }
                }
                // Automix, as in the YT Music Extension's popup.
                appendChild("PreferenceCategory") {
                    setAttribute("android:key", "ayham_automix")
                    setAttribute("android:title", "Automix")
                    appendChild("SwitchPreference") {
                        setAttribute("android:key", "ayham_automix_enabled")
                        setAttribute("android:title", "Automix")
                        setAttribute("android:summary", "BitChord's automix: the playing and the next song are analysed on your device (tempo, downbeats, intro, outro, where it is sung). When a song ends on its own it loops an instrumental bar into the next song, or blends on a phrase with the next song matched to its tempo, or uses a filter mix, or leaves a pair it can't beatmatch to Morphe's crossfade.")
                        setAttribute("android:defaultValue", "true")
                    }
                    appendChild("$AUTOMIX.AutomixVersionPreference") {
                        setAttribute("android:key", "ayham_automix_version")
                        setAttribute("android:title", "Automix version")
                        setAttribute("android:summary", "%s. Original: BitChord web's automix, a gentle bass and treble cross, equal-power fade. Advanced: plus the BitChord phone's ideas: a blend point where the two songs don't sing over each other, a quick bass swap at the middle, the old song's highs fading while the new one opens up, blends that end where the music ends, the next song never more than a quarter in.")
                        setAttribute("android:defaultValue", "1")
                        setAttribute("android:dependency", "ayham_automix_enabled")
                    }
                    appendChild("SwitchPreference") {
                        setAttribute("android:key", "ayham_automix_key_aware")
                        setAttribute("android:title", "Shorter blend when keys clash")
                        setAttribute("android:summary", "Advanced AutoDJ only. Two songs in keys that don't go together get a short blend (2 bars, about 4-6 s) so the out-of-tune moment is brief. Keys that go together (the same, relative major/minor, a neighbour on the Camelot wheel) keep the normal length.")
                        setAttribute("android:defaultValue", "false")
                    }
                    appendChild("$AUTOMIX.AutomixVersionPreference") {
                        setAttribute("android:key", "ayham_automix_bars")
                        setAttribute("android:title", "Blend length")
                        setAttribute("android:summary", "%s")
                        setAttribute("android:defaultValue", "auto")
                        setAttribute("android:dependency", "ayham_automix_enabled")
                    }
                    appendChild("$AUTOMIX.AnalysedSongsPreference") {
                        setAttribute("android:key", "ayham_automix_saved")
                    }
                }
                }
                log.info("Features: settings row added to $name")
            } ?: log.warning("Features: $name not found")
        }

        // Stats for nerds: capture every player instance at the end of its constructors.
        exoImplType?.let { implType ->
            mutableClassDefBy(implType).methods.filter { it.name == "<init>" }.forEach { ctor ->
                val returns = ctor.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                returns.reversed().forEach { index ->
                    ctor.addInstruction(
                        index,
                        "invoke-static/range { p0 .. p0 }, Lapp/ayham/extension/music/features/PlayerTap;->capturePlayer(Ljava/lang/Object;)V",
                    )
                }
                log.info("Features: stats capture in $implType <init> (${returns.size} returns)")
            }
        }

        exoImplType?.let { implType ->
            // BasePlayer.seekTo(long): this.getCurrentMediaItemIndex(); seekTo(index, position, false).
            var seekRef: String? = null
            var type: String? = implType
            while (type != null && seekRef == null) {
                val classDef = classDefByOrNull(type) ?: break
                classDef.methods.firstOrNull { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@firstOrNull false
                    method.returnType == "V" &&
                            method.parameterTypes.map(CharSequence::toString) == listOf("J") &&
                            AccessFlags.FINAL.isSet(method.accessFlags) &&
                            insns.size == 5 &&
                            insns[0].opcode == Opcode.INVOKE_VIRTUAL &&
                            ((insns[0] as ReferenceInstruction).reference as MethodReference).let {
                                it.returnType == "I" && it.parameterTypes.isEmpty()
                            } &&
                            insns[3].opcode == Opcode.INVOKE_VIRTUAL &&
                            ((insns[3] as ReferenceInstruction).reference as MethodReference).let {
                                it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "J", "Z")
                            }
                }?.let { seekRef = "${classDef.type}->${it.name}(J)V" }
                type = classDef.superclass
            }

            if (seekRef == null) {
                log.warning("Features: seekTo(long) not found in the hierarchy of $implType")
            } else {
                mutableClassDefBy(implType).apply {
                    interfaces.add(SEEK_ACCESS)
                    methods.add(
                        ImmutableMethod(
                            this.type,
                            "ayham_seekTo",
                            listOf(ImmutableMethodParameter("J", null, null)),
                            "V",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(3),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    invoke-virtual { p0, p1, p2 }, $seekRef
                                    return-void
                                """,
                            )
                        },
                    )
                }
                log.info("Features: added ayham_seekTo to $implType -> $seekRef")
            }

            // Automix: a gate at the start of ExoPlayer.setVolume(float), found as the
            // method Morphe's patch_setVolume bridge calls, so our mix can hold YT's players silent.
            val bridge = classDefByOrNull(implType)?.methods?.firstOrNull {
                it.name == "patch_setVolume" && it.parameterTypes.map(CharSequence::toString) == listOf("F")
            }
            val volumeRef = bridge?.implementation?.instructions?.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)
                    ?.takeIf { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("F") }
            }
            var gated = false
            var owner: String? = volumeRef?.definingClass
            while (volumeRef != null && owner != null && !gated) {
                val classDef = mutableClassDefByOrNull(owner) ?: break
                classDef.methods.firstOrNull { m ->
                    m.name == volumeRef.name && m.returnType == "V" &&
                            m.parameterTypes.map(CharSequence::toString) == listOf("F") && m.implementation != null
                }?.let { method ->
                    method.addInstructions(
                        0,
                        """
                            invoke-static/range { p0 .. p1 }, Lapp/ayham/extension/music/automix/Automix;->gate(Ljava/lang/Object;F)F
                            move-result p1
                        """,
                    )
                    gated = true
                    log.info("Features: volume gate in ${classDef.type}->${method.name}(F)V")
                }
                owner = classDef.superclass
            }
            if (!gated) log.warning("Features: setVolume not found (bridge=$bridge, ref=$volumeRef), no volume gate")
        } ?: log.warning("Features: no class implements Morphe's ExoPlayerAccess, seek skipped")

        // Item 8: wrap whatever resolver Morphe installs, so ours runs after Morphe's.
        mutableClassDefByOrNull(MORPHE_STREAMING)?.methods?.firstOrNull {
            it.name == "setVideoIdResolver" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(MORPHE_RESOLVER)
        }?.let { method ->
            method.addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $FEATURES->wrapResolver(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p0
                    check-cast p0, $MORPHE_RESOLVER
                """,
            )
            log.info("Features: hooked setVideoIdResolver")
        } ?: log.warning("Features: setVideoIdResolver not found")

        // Item 8 rough edge: the length of the song, wrapping Morphe's length resolver the same way.
        val lengthResolver = "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch\$VideoLengthResolver;"
        mutableClassDefByOrNull("Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;")?.methods?.firstOrNull {
            it.name == "setVideoLengthResolver" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(lengthResolver)
        }?.let { method ->
            method.addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $FEATURES->wrapLengthResolver(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p0
                    check-cast p0, $lengthResolver
                """,
            )
            log.info("Features: hooked setVideoLengthResolver")
        } ?: log.warning("Features: setVideoLengthResolver not found")

        // Lyrics: refuse wrong-language matches, and let a missed song be looked up once more.
        mutableClassDefByOrNull(MORPHE_LYRICS_MATCH)?.methods?.firstOrNull {
            it.name == "isValidLyrics" && it.parameterTypes.size == 2 && it.returnType == "Z"
        }?.takeIf { (it.implementation?.registerCount ?: 0) > 2 }?.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1 }, $LYRICS_GUARD->refuses(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :check
                const/4 v0, 0x0
                return v0
                :check
                nop
            """,
        )?.also { log.info("Features: hooked Morphe isValidLyrics") }
            ?: log.warning("Features: Morphe isValidLyrics not found")

        mutableClassDefByOrNull(MORPHE_LYRICS_CACHE)?.methods?.firstOrNull {
            it.name == "get" && it.parameterTypes.size == 3 && it.returnType == MORPHE_LYRICS_MODEL
        }?.takeIf { (it.implementation?.registerCount ?: 0) > 3 }?.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1, p2 }, $LYRICS_GUARD->aroundCacheGet(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :original
                # p1 (the source) is free here: the original code is skipped
                const/4 p1, 0x0
                aget-object v0, v0, p1
                check-cast v0, $MORPHE_LYRICS_MODEL
                return-object v0
                :original
                nop
            """,
        )?.also { log.info("Features: hooked Morphe LyricsCache.get") }
            ?: log.warning("Features: Morphe LyricsCache.get not found")
    }
}
