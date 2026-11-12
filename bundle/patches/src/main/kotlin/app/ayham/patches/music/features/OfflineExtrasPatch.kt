package app.ayham.patches.music.features

import app.ayham.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.mutableClassDefByOrNull
import app.morphe.patcher.patch.patch
import java.util.logging.Logger

private const val OFFLINE_SAVE = "Lapp/ayham/extension/music/features/OfflineSave;"
private const val MORPHE_IN_APP_DOWNLOADS = "Lapp/morphe/extension/shared/patches/BaseInAppDownloadsPatch;"

/**
 * Morphe's "Downloads" patch saves songs into YT Music's own Downloads. This saves what our player
 * shows along with each song (lyrics, the animated cover, the video type), so a downloaded song
 * looks the same with no network.
 */
@Suppress("unused")
val offlineExtrasPatch = patch(
    name = "Offline lyrics and art",
    description = "Saves lyrics and animated covers along with songs downloaded by Morphe's Downloads patch, for offline play.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    extendWith("extensions/extension.mpe")

    val log = Logger.getLogger("OfflineExtras")

    execute {
        mutableClassDefByOrNull(MORPHE_IN_APP_DOWNLOADS)?.methods?.firstOrNull {
            it.name == "replaceDeclinedOfflinePlayerResponse" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        }?.let { method ->
            method.addInstruction(0, "invoke-static { p0 }, $OFFLINE_SAVE->onOfflinePlayerResponse(Ljava/lang/Object;)V")
            log.info("OfflineExtras: hooked Morphe replaceDeclinedOfflinePlayerResponse")
        } ?: log.warning("OfflineExtras: Morphe's Downloads patch is not applied, offline lyrics and art are off")
    }
}
