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
 * ### The parse is STRICT, version 1 only
 *
 * Every field present and well-typed, entries exactly two, digests 64 lowercase hex — anything
 * else is a named [IllegalStateException]. A pack that fails to parse is not "a pack with
 * defaults", it is not our pack. Unknown EXTRA fields are tolerated (a future writer may add
 * them), but a `version` other than [VERSION] refuses outright: this reader cannot know what a
 * version-2 file means, and guessing is how a wrong pack installs.
 */
object NpuPackMetadata {

    /** The entry name in every delivery zip and pack variant — written first, so the peek
     *  reads it before either context binary. */
    const val ENTRY_NAME: String = "metadata.json"

    /** The largest entry the import peek will buffer. Real metadata is under 1 KB; a ~GB entry
     *  wearing the metadata name must never be read into memory on the way to Ignoring it. */
    const val MAX_BYTES: Int = 65_536

    /** The one format this reader understands. */
    const val VERSION: Int = 1

    /** One described entry: the delivery filename, its exact bytes, its sha256. */
    data class MetaEntry(
        val fileName: String,
        val bytes: Long,
        val sha256: String,
    )

    /** A parsed, well-formed `metadata.json`. Existence of this type IS the parse's promise. */
    data class Meta(
        val version: Int,
        val tierId: String,
        val familyId: String,
        val htpVersion: Int,
        val packGroup: String,
        val entries: List<MetaEntry>,
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
        check(version == VERSION) {
            "metadata version is $version and this build reads version $VERSION only"
        }
        val tierId = stringField(obj, "tierId")
        val familyId = stringField(obj, "familyId")
        val htpVersion = intField(obj, "htpVersion")
        val packGroup = stringField(obj, "packGroup")
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
        return Meta(version, tierId, familyId, htpVersion, packGroup, entries)
    }

    /**
     * Null when [meta] IS the pack this device needs; otherwise ONE sentence naming the first
     * disagreement, checked in declaration order (tier, family, htp, pack group, then the two
     * entries field by field).
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
        if (meta.htpVersion != family.htpVersion) {
            return "That pack says HTP v${meta.htpVersion} where the ${family.id} family is " +
                "v${family.htpVersion}, so it is not this family's published pack. Nothing " +
                "was installed."
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
