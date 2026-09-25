package com.whispereverywhere.npu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The pack's own `metadata.json` — OUR file, not the vendor's (4.2 F3).
 *
 * F4's build mode writes one of these as the FIRST entry of every delivery zip and pack
 * variant: which tier, which family, which HTP, which pack group, and the two entries the pack
 * carries with their exact bytes and digests. Two readers verify against it BEFORE any binary
 * inflates:
 *
 *  - `WhisperModelManager.importNpuAssetPair`'s metadata peek — a v79 user who grabbed the
 *    8gen3 zip learns "wrong family variant" in one second instead of "sha256 mismatch" after
 *    776 MB has inflated and hashed to learn the same thing;
 *  - F5's pack install flow, before it copies a fetched variant into the models directory.
 *
 * ### Identity, not integrity — two different guards, stated
 *
 * [crossCheckRefusal] is an IDENTITY check: does this pack claim to be the thing this device
 * needs? A disagreement means the WRONG PACK arrived (Play resolved another group, the user
 * picked another family's zip) — which is why its refusals never say "corrupted". The streamed
 * sha256 that every entry still passes afterwards is the INTEGRITY check: did the bytes arrive
 * whole? Metadata can lie where bytes cannot, so the peek never replaces the stream hash — it
 * only moves the wrong-pack answer before the expensive part.
 *
 * ### The parse is STRICT, versions 1 and 2 only
 *
 * Every field present and well-typed, entries exactly two, digests 64 lowercase hex — anything
 * else is a named [IllegalStateException]. A pack that fails to parse is not "a pack with
 * defaults", it is not our pack. Unknown EXTRA fields are tolerated (a future writer may add
 * them), but a `version` other than [VERSION] or [VERSION_2] refuses outright: this reader
 * cannot know what a third format means, and guessing is how a wrong pack installs.
 *
 * ### Two versions, one per runtime (P2-5; design §2.7 "Metadata")
 *
 * **Version 1** is the Qualcomm packs' document — its `htpVersion` names the Hexagon the context
 * binaries are compiled for — and every Qualcomm pack stays byte-identical at it.
 * **Version 2** is written for MediaTek rows only: it adds `neuronMajor` (the Neuron runtime
 * major the bytecode restores on), `socStamp` (the chip the files' own `LiteRtStamp` names) and
 * `compiler` (the bytecode's own compiler self-description, recorded at build time), and makes
 * `htpVersion` optional — a MediaTek pack has no Hexagon to name. The vendor is never stored: it
 * is the census row's, and [crossCheckRefusal] reads the row's runtime to decide which document
 * a family's pack must be.
 */
object NpuPackMetadata {

    /** The entry name in every delivery zip and pack variant — written first, so the peek
     *  reads it before either context binary. */
    const val ENTRY_NAME: String = "metadata.json"

    /** The largest entry the import peek will buffer. Real metadata is under 1 KB; a ~GB entry
     *  wearing the metadata name must never be read into memory on the way to Ignoring it. */
    const val MAX_BYTES: Int = 65_536

    /** Version 1: the Qualcomm packs' document, an HTP version and all. */
    const val VERSION: Int = 1

    /** Version 2 (P2-5): the MediaTek packs' document — see the object KDoc. */
    const val VERSION_2: Int = 2

    /** One described entry: the delivery filename, its exact bytes, its sha256. */
    data class MetaEntry(
        val fileName: String,
        val bytes: Long,
        val sha256: String,
    )

    /**
     * A parsed, well-formed `metadata.json`. Existence of this type IS the parse's promise.
     *
     * @property htpVersion required at version 1 (never null there); at version 2 present only
     *   if the document carries one, which on a MediaTek row is itself a refusal.
     * @property neuronMajor version 2 only (null at version 1): the Neuron major the bytecode was
     *   compiled for.
     * @property socStamp version 2 only: the chip the pack's model files are stamped for.
     * @property compiler version 2 only: the compiler's own self-description of the bytecode.
     */
    data class Meta(
        val version: Int,
        val tierId: String,
        val familyId: String,
        val htpVersion: Int?,
        val packGroup: String,
        val entries: List<MetaEntry>,
        val neuronMajor: Int?,
        val socStamp: String?,
        val compiler: String?,
    )

    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * Parse [text] strictly, or throw a named [IllegalStateException]. Never lenient: every
     * refusal names the first field that disagreed, because the message becomes user-visible
     * card copy through the import's bounded unreadable builder.
     */
    fun parse(text: String): Meta {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (bad: Exception) {
            error("not valid JSON (${bad.message ?: bad.javaClass.simpleName})")
        }
        val obj = root as? JsonObject ?: error("the metadata must be one JSON object")
        val version = intField(obj, "version")
        check(version == VERSION || version == VERSION_2) {
            "metadata version is $version and this build reads versions $VERSION and " +
                "$VERSION_2 only"
        }
        val tierId = stringField(obj, "tierId")
        val familyId = stringField(obj, "familyId")
        // Required at version 1; at version 2 optional, but well-typed when present.
        val htpVersion = if (version == VERSION || obj.containsKey("htpVersion")) {
            intField(obj, "htpVersion")
        } else {
            null
        }
        val packGroup = stringField(obj, "packGroup")
        // Version 2's three, each required there; a version-1 document is never read for them.
        val neuronMajor = if (version == VERSION_2) intField(obj, "neuronMajor") else null
        val socStamp = if (version == VERSION_2) stringField(obj, "socStamp") else null
        val compiler = if (version == VERSION_2) stringField(obj, "compiler") else null
        val entriesElement = obj["entries"] ?: error("the metadata is missing 'entries'")
        val array = entriesElement as? JsonArray ?: error("'entries' must be a JSON array")
        check(array.size == 2) {
            "'entries' must be exactly two (encoder then decoder); got ${array.size}"
        }
        val entries = array.map { element ->
            val entry = element as? JsonObject ?: error("every entry must be a JSON object")
            val fileName = stringField(entry, "fileName")
            val bytes = longField(entry, "bytes")
            check(bytes > 0L) { "'bytes' of '$fileName' must be positive; got $bytes" }
            val sha256 = stringField(entry, "sha256")
            check(HEX_64.matches(sha256)) {
                "'sha256' of '$fileName' must be 64 lowercase hex characters; got '$sha256'"
            }
            MetaEntry(fileName, bytes, sha256)
        }
        return Meta(
            version, tierId, familyId, htpVersion, packGroup, entries,
            neuronMajor, socStamp, compiler,
        )
    }

    /**
     * Null when [meta] IS the pack this device needs; otherwise ONE sentence naming the first
     * disagreement, checked in declaration order (tier, family, the runtime arm — the document's
     * version and HTP on a Qualcomm row; no HTP, the chip stamp and the Neuron major on a
     * MediaTek one — pack group, then the two entries field by field). `compiler` is recorded,
     * never compared: the census holds no compiler field, the build asserts it against its own
     * pinned literal, and the entries' digests already name the exact bytes.
     *
     * The family arm gets the clearest words — "this pack is the X variant and this device
     * is Y" — because it is the one Play could plausibly produce: device targeting resolves
     * groups server-side, and a wrong-group delivery is a wrong VARIANT of the right pack.
     * Every sentence ends the way every import refusal does, and truthfully: at peek time
     * nothing has been written.
     */
    fun crossCheckRefusal(
        meta: Meta,
        family: NpuSocFamily,
        artifact: PackArtifact,
        tierId: String,
    ): String? {
        if (meta.tierId != tierId) {
            return "That file is the '${meta.tierId}' tier's pack and this import is for " +
                "'$tierId', so it is the wrong model pair. Nothing was installed."
        }
        if (meta.familyId != family.id) {
            return "Wrong family variant: this pack is the ${meta.familyId} variant and this " +
                "device is ${family.id}. Its binaries are compiled for different silicon, so " +
                "get the ${family.id} pack instead. Nothing was installed."
        }
        // The runtime arm reads the row's own needs (P2 moved them off the row into `runtime`).
        // A version-1 document is a QUALCOMM pack's shape — `htpVersion` names a Hexagon — and a
        // version-2 document a MEDIATEK one; each row takes only its own vendor's document.
        when (val runtime = family.runtime) {
            is NpuRuntimeNeeds.Qnn -> {
                if (meta.version != VERSION) {
                    return "That pack is a MediaTek AI-chip pack (metadata version " +
                        "${meta.version}) and the ${family.id} family runs on Qualcomm's " +
                        "Hexagon, so it is not this family's published pack. Nothing was " +
                        "installed."
                }
                if (meta.htpVersion != runtime.htpVersion) {
                    return "That pack says HTP v${meta.htpVersion} where the ${family.id} family is " +
                        "v${runtime.htpVersion}, so it is not this family's published pack. Nothing " +
                        "was installed."
                }
            }
            is NpuRuntimeNeeds.LiteRtMediatek -> {
                // An HTP version — a version-1 document's, or one a version-2 document carries —
                // names a Hexagon, whatever its number says.
                if (meta.version == VERSION || meta.htpVersion != null) {
                    return "That pack says HTP v${meta.htpVersion}, which only a Qualcomm AI-chip " +
                        "pack does, and the ${family.id} family runs on MediaTek's APU, so it is " +
                        "not this family's published pack. Nothing was installed."
                }
                // THE MEDIATEK TWIN of the HTP arm (P2-5; design §2.3 item 4): the chip the
                // bytecode is stamped for, then the Neuron major it restores on — each against the
                // row the driver check and the engine's init also read.
                if (meta.socStamp != runtime.socStamp) {
                    return "That pack's model files are stamped for ${meta.socStamp} where the " +
                        "${family.id} family's chip is ${runtime.socStamp}, so their bytecode " +
                        "would not restore on this device. Nothing was installed."
                }
                if (meta.neuronMajor != runtime.neuronMajor) {
                    return "That pack was compiled for Neuron ${meta.neuronMajor} where the " +
                        "${family.id} family's driver check admits Neuron " +
                        "${runtime.neuronMajor}, so it is not this family's published pack. " +
                        "Nothing was installed."
                }
            }
        }
        if (meta.packGroup != family.packGroup) {
            return "That pack names group '${meta.packGroup}' where the ${family.id} family " +
                "ships under '${family.packGroup}', so it is not this family's published " +
                "pack. Nothing was installed."
        }
        val expected = listOf(artifact.encoder, artifact.decoder)
        for ((index, want) in expected.withIndex()) {
            val got = meta.entries[index]
            if (got.fileName != want.fileName) {
                return "That pack's metadata names '${got.fileName}' where this pair's file " +
                    "is '${want.fileName}', so it is a different pack. Nothing was installed."
            }
            if (got.bytes != want.bytes) {
                return "That pack's metadata describes ${want.fileName} as ${got.bytes} B " +
                    "where the ${family.id} family's published file is ${want.bytes} B, so " +
                    "it is a different pack. Nothing was installed."
            }
            if (got.sha256 != want.sha256) {
                return "That pack's metadata describes ${want.fileName} with sha256 " +
                    "${got.sha256} where the ${family.id} family's published file is " +
                    "${want.sha256} — a different pack, not a damaged one; damage is what " +
                    "the copy's own streamed hash would catch. Nothing was installed."
            }
        }
        return null
    }

    private fun stringField(obj: JsonObject, name: String): String {
        val element = obj[name] ?: error("the metadata is missing '$name'")
        val primitive = element as? JsonPrimitive ?: error("'$name' must be a JSON string")
        check(primitive.isString) { "'$name' must be a JSON string; got $primitive" }
        check(primitive.content.isNotBlank()) { "'$name' must not be blank" }
        return primitive.content
    }

    private fun intField(obj: JsonObject, name: String): Int {
        val element = obj[name] ?: error("the metadata is missing '$name'")
        val primitive = element as? JsonPrimitive ?: error("'$name' must be a JSON number")
        check(!primitive.isString) { "'$name' must be a JSON number, not a string" }
        return primitive.content.toIntOrNull()
            ?: error("'$name' is not an integer: ${primitive.content}")
    }

    private fun longField(obj: JsonObject, name: String): Long {
        val element = obj[name] ?: error("the metadata is missing '$name'")
        val primitive = element as? JsonPrimitive ?: error("'$name' must be a JSON number")
        check(!primitive.isString) { "'$name' must be a JSON number, not a string" }
        return primitive.content.toLongOrNull()
            ?: error("'$name' is not an integer: ${primitive.content}")
    }
}
