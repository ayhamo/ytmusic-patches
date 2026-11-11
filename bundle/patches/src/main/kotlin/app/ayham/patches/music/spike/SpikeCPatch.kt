package app.ayham.patches.music.spike

import app.ayham.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.mutableClassDefByOrNull
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patcher.patch.classDefByOrNull
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger

private const val SPIKE_C = "Lapp/ayham/extension/music/spike/SpikeC;"
private const val MORPHE_DOWNLOADS = "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val MORPHE_SPOOF = "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;"

/** YT Music's OfflineVideoCommandResolver.resolve: the song's offline video endpoint. */
internal object SpikeMusicOfflineVideoResolveFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(string("Object is not an offlineable video: %s")),
)

/** Fetches the player response a video is saved with, and puts it into the cache (Morphe's YouTube fingerprint). */
internal object SpikeOfflinePlayerResponseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        string("Some error occurred when reading from the cache player response"),
    ),
)

internal fun spikePlayerResponseModelFromBytesFingerprint(playerResponseModel: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = playerResponseModel,
    parameters = listOf("[B", "J"),
    filters = listOf(
        methodCall(
            parameters = listOf("[B", "Lcom/google/protobuf/MessageLite;"),
            returnType = "Lcom/google/protobuf/MessageLite;",
        ),
        opcode(Opcode.CHECK_CAST),
    ),
)

internal fun spikePlayerResponseGetterFingerprint(modelClass: String, playerResponse: String) = Fingerprint(
    definingClass = modelClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = playerResponse,
    parameters = listOf(),
)

/** Updates the offline state of a saved video from a 'player/refresh' response (two methods). */
internal object SpikeUpdateOfflineVideoPolicyFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "J", "L", "L"),
    filters = listOf(
        anyInstruction(
            string("[Offline] UpdateVideoPolicy failed for video "),
            string("[Offline] UpdateVideoPolicy failed for video %s"),
        ),
    ),
)

/**
 * Throwaway spike, batch C again: YouTube Music's own offline engine for a free account.
 * Each hook is optional: a missing target logs and skips.
 */
val spikeCPatch = patch(
    name = "Spike C",
    description = "Test hooks for the native offline spike. Not for daily use.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    extendWith("extensions/extension.mpe")

    val log = Logger.getLogger("SpikeC")

    execute {
        fun addOfflineManagerGetter(managerType: String, log: Logger) {
            // 1. The switching provider method that builds the manager, and the case key that leads there.
            var providerType: String? = null
            var caseKey: Int? = null
            classDefForEach { classDef ->
                if (caseKey != null) return@classDefForEach
                classDef.methods.forEach { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@forEach
                    val newIndex = insns.indexOfFirst {
                        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == managerType
                    }
                    if (newIndex < 0) return@forEach
                    val address = IntArray(insns.size + 1)
                    insns.forEachIndexed { i, insn -> address[i + 1] = address[i] + insn.codeUnits }
                    insns.forEachIndexed { i, insn ->
                        if (insn.opcode != Opcode.PACKED_SWITCH && insn.opcode != Opcode.SPARSE_SWITCH) return@forEachIndexed
                        val payloadAddress = address[i] + (insn as OffsetInstruction).codeOffset
                        val payloadIndex = address.indexOf(payloadAddress)
                        val payload = insns.getOrNull(payloadIndex) as? SwitchPayload ?: return@forEachIndexed
                        val target = payload.switchElements
                            .map { it.key to address[i] + it.offset }
                            .filter { it.second <= address[newIndex] }
                            .maxByOrNull { it.second } ?: return@forEachIndexed
                        if (caseKey == null) {
                            caseKey = target.first
                            providerType = classDef.type
                        }
                    }
                }
            }
            val key = caseKey ?: return log.warning("SpikeC: no switching provider builds $managerType")
            val provider = providerType!!
            log.info("SpikeC: $managerType is case $key of $provider")

            // 2. Where a component builds that provider with that key, and the field keeping the scoped one.
            val found = mutableListOf<FieldReference>()
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@forEach
                    insns.forEachIndexed { i, insn ->
                        if (insn.opcode != Opcode.INVOKE_DIRECT && insn.opcode != Opcode.INVOKE_DIRECT_RANGE) return@forEachIndexed
                        val ref = (insn as ReferenceInstruction).reference as MethodReference
                        if (ref.definingClass != provider || ref.name != "<init>") return@forEachIndexed
                        val regs = registersOf(insn)
                        val keyRegister = regs.last()
                        val constant = insns.subList(0, i).lastOrNull {
                            (it as? OneRegisterInstruction)?.registerA == keyRegister
                        } as? NarrowLiteralInstruction
                        if (constant?.narrowLiteral != key) return@forEachIndexed
                        val instanceRegister = regs.first()

                        // The wrapper call taking the instance, its result, and the field it goes into.
                        var scoped: FieldReference? = null
                        var raw: FieldReference? = null
                        for (j in i + 1 until minOf(insns.size, i + 12)) {
                            val next = insns[j]
                            if (next.opcode == Opcode.INVOKE_STATIC && registersOf(next) == listOf(instanceRegister) &&
                                insns.getOrNull(j + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
                            ) {
                                val result = (insns[j + 1] as OneRegisterInstruction).registerA
                                scoped = insns.subList(j + 2, minOf(insns.size, j + 5)).firstOrNull {
                                    it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == result
                                }?.let { (it as ReferenceInstruction).reference as FieldReference }
                                if (scoped != null) break
                            }
                            if (next.opcode == Opcode.IPUT_OBJECT && raw == null &&
                                (next as TwoRegisterInstruction).registerA == instanceRegister
                            ) {
                                raw = (next as ReferenceInstruction).reference as FieldReference
                            }
                        }
                        val field = scoped ?: raw ?: return@forEachIndexed
                        log.info("SpikeC: provider of $managerType kept in ${field.definingClass}->${field.name} (${if (scoped != null) "scoped" else "raw"})")
                        found += field
                    }
                }
            }

            found.distinctBy { it.definingClass }.forEach { field ->
                // 3. The provider interface's getter: an abstract no-arg method returning Object.
                fun getterOf(type: String): Pair<String, String>? {
                    val def = classDefByOrNull(type) ?: return null
                    def.methods.firstOrNull {
                        it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;" && AccessFlags.ABSTRACT.isSet(it.accessFlags)
                    }?.let { return type to it.name }
                    return def.interfaces.firstNotNullOfOrNull { getterOf(it) }
                }
                val (getterOwner, getter) = getterOf(field.type) ?: return@forEach log.warning("SpikeC: no getter on ${field.type}")
                val component = mutableClassDefBy(field.definingClass)
                component.methods.add(
                    ImmutableMethod(
                        component.type, "ayham_offlineManager",
                        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                        "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                        null, null, MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                check-cast p0, ${component.type}
                                iget-object v0, p0, ${field.definingClass}->${field.name}:${field.type}
                                invoke-interface { v0 }, $getterOwner->$getter()Ljava/lang/Object;
                                move-result-object v0
                                return-object v0
                            """,
                        )
                    },
                )
                component.methods.filter { it.name == "<init>" }.forEach { init ->
                    init.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (index, _) ->
                        init.addInstructions(index, "invoke-static/range { p0 .. p0 }, $SPIKE_C->captureActivityComponent(Ljava/lang/Object;)V")
                    }
                }
                log.info("SpikeC: added ${component.type}->ayham_offlineManager via $getterOwner->$getter, component capture")
            }
        }

        // Capture the offline video command resolver, which holds the offline manager and logger.
        runCatching {
            val resolve = SpikeMusicOfflineVideoResolveFingerprint
            val resolverType = resolve.classDef.type
            mutableClassDefBy(resolverType).methods.filter { it.name == "<init>" }.forEach { init ->
                val returns = init.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
                returns.reversed().forEach { (index, _) ->
                    init.addInstructions(index, "invoke-static/range { p0 .. p0 }, $SPIKE_C->captureVideoResolver(Ljava/lang/Object;)V")
                }
            }
            log.info("SpikeC: capture hook in $resolverType")

            // The offline manager's save(videoId, offlineability, logger, metadata), called by the resolver.
            val saveCall = resolve.method.instructions.firstNotNullOfOrNull { insn ->
                if (insn.opcode != Opcode.INVOKE_INTERFACE) return@firstNotNullOfOrNull null
                val ref = (insn as ReferenceInstruction).reference as MethodReference
                ref.takeIf {
                    it.returnType == "V" && it.parameterTypes.size == 4 &&
                            it.parameterTypes[0].toString() == "Ljava/lang/String;"
                }
            } ?: error("save call not found")
            log.info("SpikeC: save interface ${saveCall.definingClass}->${saveCall.name}")

            val implementations = mutableListOf<String>()
            classDefForEach { if (saveCall.definingClass in it.interfaces) implementations += it.type }
            implementations.forEach { impl ->
                mutableClassDefBy(impl).methods.firstOrNull {
                    it.name == saveCall.name &&
                            it.parameterTypes.map(CharSequence::toString) == saveCall.parameterTypes.map(CharSequence::toString)
                }?.addInstructions(
                    0,
                    """
                        invoke-static/range { p2 .. p2 }, $SPIKE_C->unlockOfflineability(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p2
                        check-cast p2, ${saveCall.parameterTypes[1]}
                    """,
                )
                log.info("SpikeC: unlock hook in $impl")
                // Capture the manager itself, so the save can be called without the resolver.
                mutableClassDefBy(impl).methods.filter { it.name == "<init>" }.forEach { init ->
                    init.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (index, _) ->
                        init.addInstructions(index, "invoke-static/range { p0 .. p0 }, $SPIKE_C->captureOfflineManager(Ljava/lang/Object;)V")
                    }
                }
                log.info("SpikeC: manager capture in $impl constructors")
            }
            // The manager is built lazily by a Dagger switching provider kept in the activity component.
            // Found from bytecode: the provider method that builds it, its switch case, the component
            // field that keeps the scoped provider of that case, and the provider interface's getter.
            implementations.forEach { impl -> addOfflineManagerGetter(impl, log) }
        }.onFailure { log.warning("SpikeC: save hooks failed: $it") }

        // Replace the player response the server declines to let a free account save (Morphe YouTube port).
        runCatching {
            val fp = SpikeOfflinePlayerResponseFingerprint
            val modelType = fp.method.returnType
            val fromBytes = spikePlayerResponseModelFromBytesFingerprint(modelType)
            val fromBytesMethod = fromBytes.method
            val playerResponseType =
                ((fromBytes.instructionMatches.last().instruction as ReferenceInstruction).reference as TypeReference).type
            val getter = spikePlayerResponseGetterFingerprint(fromBytesMethod.definingClass, playerResponseType).method
            log.info("SpikeC: offline player response in ${fp.method.definingClass}->${fp.method.name}, model $modelType, fromBytes ${fromBytesMethod.definingClass}->${fromBytesMethod.name}, getter ${getter.name}")

            val helper = ImmutableMethod(
                fp.classDef.type,
                "ayham_replaceDeclinedOfflinePlayerResponse",
                listOf(ImmutableMethodParameter(modelType, null, null)),
                modelType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        if-eqz p0, :keep
                        invoke-interface { p0 }, $modelType->${getter.name}()$playerResponseType
                        move-result-object v0
                        invoke-static { v0 }, $SPIKE_C->replaceDeclinedOfflinePlayerResponse(Ljava/lang/Object;)[B
                        move-result-object v0
                        if-eqz v0, :keep
                        const/4 v1, 0x1
                        invoke-static { v1 }, $MORPHE_SPOOF->setKeepStreamsOnThread(Z)V
                        invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                        move-result-wide v1
                        invoke-static { v0, v1, v2 }, ${fromBytesMethod.definingClass}->${fromBytesMethod.name}([BJ)$modelType
                        move-result-object v0
                        invoke-static { v0 }, $SPIKE_C->onOfflinePlayerResponseReplaced(Ljava/lang/Object;)V
                        if-eqz v0, :keep
                        return-object v0
                        :keep
                        return-object p0
                    """,
                )
            }
            mutableClassDefBy(fp.classDef.type).methods.add(helper)

            // YT Music caches the response in a lambda, so hook right after the network fetch:
            // the last call in the method that returns the model, and its move-result.
            val insns = fp.method.instructions.toList()
            insns.forEachIndexed { i, insn ->
                if (insn.opcode.name.startsWith("invoke")) {
                    log.info("SpikeC: azdj #$i ${insn.opcode} ${(insn as ReferenceInstruction).reference} next=${insns.getOrNull(i + 1)?.opcode}")
                }
            }
            val fetchIndex = insns.indices.last { i ->
                val ref = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference
                insns[i].opcode.name.startsWith("invoke") && ref?.returnType == modelType &&
                        insns.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
            }
            val register = (insns[fetchIndex + 1] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
            fp.method.addInstructions(
                fetchIndex + 2,
                """
                    invoke-static/range { v$register .. v$register }, ${fp.classDef.type}->ayham_replaceDeclinedOfflinePlayerResponse($modelType)$modelType
                    move-result-object v$register
                """,
            )
            log.info("SpikeC: offline player response hook after fetch #$fetchIndex ${(insns[fetchIndex] as ReferenceInstruction).reference} (v$register)")
        }.onFailure { log.warning("SpikeC: offline player response hook failed: $it") }

        // Keep saved songs playable if the server disables them on refresh.
        runCatching {
            SpikeUpdateOfflineVideoPolicyFingerprint.matchAll().forEach { match ->
                val stateType = match.method.parameterTypes[2].toString()
                match.method.addInstructions(
                    0,
                    """
                        invoke-static/range { p4 .. p4 }, $SPIKE_C->replaceDisabledOfflineState(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p4
                        check-cast p4, $stateType
                    """,
                )
                log.info("SpikeC: refresh hook in ${match.method.definingClass}->${match.method.name}")
            }
        }.onFailure { log.warning("SpikeC: refresh hook failed: $it") }
    }

    finalize {
        // Every Download tap goes through Morphe's onMenuDownload; in native mode we take it.
        mutableClassDefByOrNull(MORPHE_DOWNLOADS)?.methods?.firstOrNull {
            it.name == "onMenuDownload" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $SPIKE_C->onMenuDownload(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :morphe
                    return v0
                    :morphe
                    nop
                """,
            )
            log.info("SpikeC: hooked Morphe onMenuDownload (registers ${method.implementation?.registerCount})")
        } ?: log.warning("SpikeC: Morphe onMenuDownload not found")

        // YT's own offline playlists: find the app methods that call Morphe's hooks (YT's command
        // resolver and YT's offline playlist endpoint handler) by those calls, not by names.
        val callers = mutableMapOf<String, Pair<String, String>>() // Morphe method -> (class, method name + descriptor)
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/")) return@classDefForEach
            classDef.methods.forEach { method ->
                method.implementation?.instructions?.forEach { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                    if (ref.definingClass == MORPHE_DOWNLOADS &&
                        (ref.name == "commandResolverOnClick" || ref.name == "offlinePlaylistEndpointOnClick")
                    ) {
                        callers[ref.name] = classDef.type to (method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType)
                    }
                }
            }
        }

        callers["commandResolverOnClick"]?.let { (type, signature) ->
            // The caller is the router's static "run one resolver" step; the router's own dispatch
            // method (command, Map) picks the resolver and calls that step. Capture the router there.
            val classDef = mutableClassDefBy(type)
            val step = classDef.methods.first { it.name + "(" + it.parameterTypes.joinToString("") + ")" + it.returnType == signature }
            val commandType = step.parameterTypes.getOrNull(1)?.toString()
            val dispatch = classDef.methods.firstOrNull { m ->
                !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" &&
                    m.parameterTypes.map(CharSequence::toString) == listOf(commandType, "Ljava/util/Map;") &&
                    m.implementation?.instructions?.any { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        ref != null && ref.definingClass == type && ref.name == step.name
                    } == true
            }
            if (dispatch == null) {
                log.warning("SpikeC: command router dispatch in $type not found")
                return@let
            }
            dispatch.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $SPIKE_C->captureRouter(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            // SpikeC.routerMethod() returns the dispatch method's name.
            mutableClassDefBy(SPIKE_C).methods.first { it.name == "routerMethod" }.apply {
                addInstructions(
                    0,
                    """
                        const-string v0, "${dispatch.name}"
                        return-object v0
                    """,
                )
            }
            log.info("SpikeC: command router $type->${dispatch.name} captured")
        } ?: log.warning("SpikeC: caller of Morphe commandResolverOnClick not found")

        callers["offlinePlaylistEndpointOnClick"]?.let { (type, signature) ->
            val handler = mutableClassDefBy(type).methods.first { it.name + "(" + it.parameterTypes.joinToString("") + ")" + it.returnType == signature }
            val commandType = handler.parameterTypes[0].toString()
            handler.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $SPIKE_C->unlockPlaylistCommand(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $commandType
                """,
            )
            log.info("SpikeC: offline playlist handler $type->$signature unlocks its endpoint")
        } ?: log.warning("SpikeC: caller of Morphe offlinePlaylistEndpointOnClick not found")

        // The player's own Download button skips onMenuDownload and calls startDownload directly;
        // every in-app download ends there, so in native mode it goes to YT's own save as well.
        mutableClassDefByOrNull(MORPHE_DOWNLOADS)?.methods?.firstOrNull {
            it.name == "startDownload" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Z")
        }?.let { method ->
            val registers = method.implementation!!.registerCount
            if (registers <= 2) {
                log.warning("SpikeC: startDownload has no free register, skipped")
            } else {
                method.addInstructionsWithLabels(
                    0,
                    """
                        if-nez p1, :morphe
                        invoke-static/range { p0 .. p0 }, $SPIKE_C->onMenuDownload(Ljava/lang/String;)Z
                        move-result v0
                        if-eqz v0, :morphe
                        return-void
                        :morphe
                        nop
                    """,
                )
                log.info("SpikeC: hooked Morphe startDownload (registers $registers)")
            }
        } ?: log.warning("SpikeC: Morphe startDownload not found")

        // Playlist/album page Download (a Premium upsell for free accounts): native playlist download.
        mutableClassDefByOrNull(MORPHE_DOWNLOADS)?.methods?.firstOrNull { it.name == "commandResolverOnClick" }?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $SPIKE_C->onCommand(Ljava/lang/Object;)I
                    move-result v0
                    if-eqz v0, :morphe
                    const/4 p0, 0x1
                    if-ne v0, p0, :app
                    return p0
                    :app
                    const/4 v0, 0x0
                    return v0
                    :morphe
                    nop
                """,
            )
            log.info("SpikeC: hooked Morphe commandResolverOnClick")
        } ?: log.warning("SpikeC: Morphe commandResolverOnClick not found")

        // Playlist/album downloads: Morphe lists the tracks; each track is saved natively instead.
        mutableClassDefByOrNull("Lapp/morphe/extension/music/patches/downloads/LocalDownloadManager;")?.methods?.firstOrNull {
            it.name == "downloadPlaylistTrack" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $SPIKE_C->onPlaylistTrack(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :morphe
                    return v0
                    :morphe
                    nop
                """,
            )
            log.info("SpikeC: hooked Morphe downloadPlaylistTrack")
        } ?: log.warning("SpikeC: Morphe downloadPlaylistTrack not found")

        // The app's own offline playlist Download: optionally left to the app.
        mutableClassDefByOrNull(MORPHE_DOWNLOADS)?.methods?.firstOrNull {
            it.name == "offlinePlaylistEndpointOnClick"
        }?.let { method ->
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $SPIKE_C->dumpCommand(Ljava/lang/Object;)V
                    invoke-static { }, $SPIKE_C->letAppSavePlaylist()Z
                    move-result v0
                    if-eqz v0, :morphe
                    const/4 v0, 0x0
                    return v0
                    :morphe
                    nop
                """,
            )
            log.info("SpikeC: hooked Morphe offlinePlaylistEndpointOnClick")
        } ?: log.warning("SpikeC: Morphe offlinePlaylistEndpointOnClick not found")
    }
}

private fun registersOf(insn: Instruction): List<Int> = when (insn) {
    is RegisterRangeInstruction -> (insn.startRegister until insn.startRegister + insn.registerCount).toList()
    is FiveRegisterInstruction -> listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG).take(insn.registerCount)
    else -> emptyList()
}
