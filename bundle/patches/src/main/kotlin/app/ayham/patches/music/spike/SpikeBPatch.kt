package app.ayham.patches.music.spike

import app.ayham.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import java.util.logging.Logger

private val NATIVE_LIBS = listOf(
    "libbitchord_analysis.so",
    "libbitchord_loopmix.so",
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so",
)

/**
 * Throwaway spike, batch B (PLAN.md item 4): can a patch add native libraries to the APK?
 * Copies BitChord's analyser and ONNX Runtime (arm64-v8a, the only ABI YT Music ships) into lib/.
 */
val spikeBPatch = patch(
    name = "Spike B",
    description = "Adds the native libraries for the batch B spike. Not for daily use.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    val log = Logger.getLogger("SpikeB")

    execute {
        val libDir = resourceFile("lib/arm64-v8a")
        log.info("SpikeB: lib/arm64-v8a exists=${libDir.isDirectory} files=${libDir.list()?.size}")
        NATIVE_LIBS.forEach { name ->
            val stream = object {}.javaClass.getResourceAsStream("/spike/lib/arm64-v8a/$name")
            if (stream == null) {
                log.warning("SpikeB: $name not bundled")
                return@forEach
            }
            val out = resourceFile("lib/arm64-v8a/$name")
            out.parentFile.mkdirs()
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            log.info("SpikeB: added ${out.path} (${out.length()} bytes)")
        }

        // Automix's models (Beat This!, the vocal mask), copied to the app's files on first use.
        listOf("beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx").forEach { name ->
            val stream = object {}.javaClass.getResourceAsStream("/automix/$name")
            if (stream == null) {
                log.warning("SpikeB: model $name not bundled")
                return@forEach
            }
            val out = resourceFile("assets/automix/$name")
            out.parentFile.mkdirs()
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            log.info("SpikeB: added ${out.path} (${out.length()} bytes)")
        }

        // New files are written compressed, and YT ships extractNativeLibs=false, which only
        // accepts uncompressed, page-aligned libraries. Let the installer extract them instead.
        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()
            log.info("SpikeB: extractNativeLibs was ${application.attribute("android:extractNativeLibs")}")
            application.setAttribute("android:extractNativeLibs", "true")
        }
    }
}
