package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack metadata's two promises, executed (4.2 F3): [NpuPackMetadata.parse] is STRICT —
 * version 1, every field present and well-typed, entries exactly two, digests 64-hex, a named
 * `IllegalStateException` otherwise — and [NpuPackMetadata.crossCheckRefusal] is an IDENTITY
 * check whose refusals name the first disagreement in words a user can act on. The
 * wrong-family arm gets the clearest words because it is the one Play could plausibly
 * produce, and the digest arm defers to entry equality: identity ("a different pack"), never
 * integrity ("corrupted"), because the streamed sha256 downstream is the integrity gate.
 */
class NpuPackMetadataTest {

    private val family = NpuFleetCensus.familyById("8elite_galaxy")!!
    private val artifact = NpuFleetCensus.artifactFor("8elite_galaxy", "npu-turbo")!!

    /** One golden metadata document, field-overridable; a null field is OMITTED. */
    private fun metaJson(
        version: String? = "1",
        tierId: String? = "\"npu-turbo\"",
        familyId: String? = "\"8elite_galaxy\"",
        htpVersion: String? = "79",
        packGroup: String? = "\"soc_8elite_galaxy\"",
        entries: String? = entriesJson(),
    ): String {
        val fields = listOfNotNull(
            version?.let { "\"version\": $it" },
            tierId?.let { "\"tierId\": $it" },
            familyId?.let { "\"familyId\": $it" },
            htpVersion?.let { "\"htpVersion\": $it" },
            packGroup?.let { "\"packGroup\": $it" },
            entries?.let { "\"entries\": $it" },
        )
        return "{" + fields.joinToString(",") + "}"
    }

    private fun entriesJson(
        encoderName: String = artifact.encoder.fileName,
        encoderBytes: Long = artifact.encoder.bytes,
        encoderSha: String = artifact.encoder.sha256,
        decoderName: String = artifact.decoder.fileName,
        decoderBytes: Long = artifact.decoder.bytes,
        decoderSha: String = artifact.decoder.sha256,
    ): String =
        "[{\"fileName\": \"$encoderName\", \"bytes\": $encoderBytes, \"sha256\": \"$encoderSha\"}," +
            "{\"fileName\": \"$decoderName\", \"bytes\": $decoderBytes, \"sha256\": \"$decoderSha\"}]"

    private fun refusalOf(json: String): String {
        try {
            NpuPackMetadata.parse(json)
        } catch (named: IllegalStateException) {
            return named.message ?: throw AssertionError("the refusal must carry a message")
        }
        throw AssertionError("parse accepted what it must refuse: $json")
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun aGoldenMetadataParsesEveryField() {
        val meta = NpuPackMetadata.parse(metaJson())
        assertEquals(1, meta.version)
        assertEquals("npu-turbo", meta.tierId)
        assertEquals("8elite_galaxy", meta.familyId)
        assertEquals(79, meta.htpVersion)
        assertEquals("soc_8elite_galaxy", meta.packGroup)
        assertEquals(
            "the two entries arrive in order, every field carried",
            listOf(
                NpuPackMetadata.MetaEntry(
                    artifact.encoder.fileName, artifact.encoder.bytes, artifact.encoder.sha256
                ),
                NpuPackMetadata.MetaEntry(
                    artifact.decoder.fileName, artifact.decoder.bytes, artifact.decoder.sha256
                ),
            ),
            meta.entries,
        )
        // Unknown EXTRA fields are tolerated — a future writer may add one, and refusing it
        // would strand every already-published pack behind an app update.
        assertNotNull(
            NpuPackMetadata.parse(metaJson().dropLast(1) + ",\"comment\": \"rider\"}")
        )
    }

    @Test
    fun theVersionMustBeExactlyOne() {
        assertTrue(
            "a missing version is named",
            refusalOf(metaJson(version = null)).contains("version")
        )
        val two = refusalOf(metaJson(version = "2"))
        assertTrue(
            "a version-2 file is refused BY ITS NUMBER — this reader cannot know what " +
                "version 2 means, and guessing is how a wrong pack installs: $two",
            two.contains("2") && two.contains("version 1")
        )
        assertTrue(
            "a string '1' is not the number 1 — strict about types, because lenient parsing " +
                "is how a half-written file becomes 'a pack with defaults'",
            refusalOf(metaJson(version = "\"1\"")).contains("version")
        )
    }

    @Test
    fun everyMissingTopLevelFieldIsRefusedByItsName() {
        assertTrue(refusalOf(metaJson(tierId = null)).contains("tierId"))
        assertTrue(refusalOf(metaJson(familyId = null)).contains("familyId"))
        assertTrue(refusalOf(metaJson(htpVersion = null)).contains("htpVersion"))
        assertTrue(refusalOf(metaJson(packGroup = null)).contains("packGroup"))
        assertTrue(refusalOf(metaJson(entries = null)).contains("entries"))
    }

    @Test
    fun malformedFieldsAreRefusedByName() {
        assertTrue(
            "an htpVersion written as a string is a type fault, named",
            refusalOf(metaJson(htpVersion = "\"79\"")).contains("htpVersion")
        )
        assertTrue(
            "a blank tierId is a decision nobody made",
            refusalOf(metaJson(tierId = "\"\"")).contains("tierId")
        )
        assertTrue(
            "entries as an object is not a list of two",
            refusalOf(metaJson(entries = "{}")).contains("entries")
        )
        assertTrue(
            "a fractional htpVersion is not an integer",
            refusalOf(metaJson(htpVersion = "79.5")).contains("htpVersion")
        )
        assertTrue(
            "not JSON at all is refused as such, not as a missing field",
            refusalOf("seventy-nine").contains("JSON")
        )
        assertTrue(
            "a JSON array at top level is not our document",
            refusalOf("[1, 2]").contains("object")
        )
    }

    @Test
    fun entriesMustBeExactlyTwo() {
        val zero = refusalOf(metaJson(entries = "[]"))
        assertTrue("zero entries, named with the count: $zero", zero.contains("0"))
        val one = metaJson(
            entries = "[{\"fileName\": \"a.bin\", \"bytes\": 1, \"sha256\": \"${"a".repeat(64)}\"}]"
        )
        assertTrue("one entry is half a pair", refusalOf(one).contains("1"))
        val three = metaJson(
            entries = entriesJson().dropLast(1) +
                ",{\"fileName\": \"c.bin\", \"bytes\": 1, \"sha256\": \"${"a".repeat(64)}\"}]"
        )
        assertTrue("three entries is not our pack either", refusalOf(three).contains("3"))
    }

    @Test
    fun anEntryMissingOrCorruptingAFieldIsRefusedByName() {
        assertTrue(
            refusalOf(
                metaJson(entries = "[{\"bytes\": 1, \"sha256\": \"${"a".repeat(64)}\"}," +
                    "{\"fileName\": \"b\", \"bytes\": 1, \"sha256\": \"${"b".repeat(64)}\"}]")
            ).contains("fileName")
        )
        assertTrue(
            refusalOf(
                metaJson(entries = "[{\"fileName\": \"a\", \"sha256\": \"${"a".repeat(64)}\"}," +
                    "{\"fileName\": \"b\", \"bytes\": 1, \"sha256\": \"${"b".repeat(64)}\"}]")
            ).contains("bytes")
        )
        assertTrue(
            refusalOf(
                metaJson(entries = "[{\"fileName\": \"a\", \"bytes\": 1}," +
                    "{\"fileName\": \"b\", \"bytes\": 1, \"sha256\": \"${"b".repeat(64)}\"}]")
            ).contains("sha256")
        )
        assertTrue(
            "a zero byte count is not a measured file",
            refusalOf(
                metaJson(entries = "[{\"fileName\": \"a\", \"bytes\": 0, \"sha256\": " +
                    "\"${"a".repeat(64)}\"}," +
                    "{\"fileName\": \"b\", \"bytes\": 1, \"sha256\": \"${"b".repeat(64)}\"}]")
            ).contains("positive")
        )
    }

    @Test
    fun aDigestThatIsNotSixtyFourLowercaseHexIsRefused() {
        val upper = artifact.encoder.sha256.uppercase()
        assertTrue(
            "uppercase hex is refused — one canonical rendering, or every comparison " +
                "needs a folding rule somebody will forget",
            refusalOf(metaJson(entries = entriesJson(encoderSha = upper))).contains("sha256")
        )
        assertTrue(
            "63 characters is not a digest",
            refusalOf(metaJson(entries = entriesJson(encoderSha = "a".repeat(63))))
                .contains("sha256")
        )
        assertTrue(
            "and 64 non-hex characters is not one either",
            refusalOf(metaJson(entries = entriesJson(encoderSha = "g".repeat(64))))
                .contains("sha256")
        )
    }

    // ------------------------------------------------------------------ crossCheckRefusal

    @Test
    fun crossCheckAnswersNullForEveryMatchingFamilyAndTier() {
        // The whole fleet: a metadata document built FROM each artifact row cross-checks
        // silently against its own family and tier — all eight, so no row's pack is refused
        // by the peek that exists to protect it.
        for (a in NpuFleetCensus.artifacts) {
            val f = NpuFleetCensus.familyById(a.familyId)!!
            val meta = NpuPackMetadata.parse(
                "{\"version\": 1, \"tierId\": \"${a.tierId}\", \"familyId\": \"${a.familyId}\"," +
                    "\"htpVersion\": ${f.htpVersion}, \"packGroup\": \"${f.packGroup}\"," +
                    "\"entries\": [" +
                    "{\"fileName\": \"${a.encoder.fileName}\", \"bytes\": ${a.encoder.bytes}, " +
                    "\"sha256\": \"${a.encoder.sha256}\"}," +
                    "{\"fileName\": \"${a.decoder.fileName}\", \"bytes\": ${a.decoder.bytes}, " +
                    "\"sha256\": \"${a.decoder.sha256}\"}]}"
            )
            assertNull(
                "${a.familyId}/${a.tierId}: the matching pack passes its own peek",
                NpuPackMetadata.crossCheckRefusal(meta, f, a, a.tierId)
            )
        }
    }

    @Test
    fun theFamilyMismatchRefusalNamesBothFamiliesInTheClearestWords() {
        // THE ARM PLAY COULD PLAUSIBLY PRODUCE: same tier, wrong family variant. A v79 user
        // holding the 8gen3 zip learns this in one second, in these words, instead of
        // "sha256 mismatch" after 776 MB inflates.
        val meta = NpuPackMetadata.parse(metaJson(
            familyId = "\"8gen3\"", htpVersion = "75", packGroup = "\"soc_8gen3\"",
        ))
        val refusal = NpuPackMetadata.crossCheckRefusal(meta, family, artifact, "npu-turbo")
        assertNotNull("a wrong-family pack must refuse at the peek", refusal)
        assertTrue(
            "the sentence names BOTH families, pack first, device second: $refusal",
            refusal!!.contains("this pack is the 8gen3 variant and this device is 8elite_galaxy")
        )
        assertTrue(
            "and tells the user which pack to get instead: $refusal",
            refusal.contains("8elite_galaxy pack")
        )
        assertTrue("nothing was installed, which at peek time is true: $refusal",
            refusal.contains("Nothing was installed"))
    }

    @Test
    fun tierHtpAndPackGroupMismatchesAreEachNamed() {
        val wrongTier = NpuPackMetadata.parse(metaJson(tierId = "\"npu\""))
        val tierRefusal = NpuPackMetadata.crossCheckRefusal(wrongTier, family, artifact, "npu-turbo")
        assertNotNull(tierRefusal)
        assertTrue(
            "the tier arm names both tiers — the small pack picked for a turbo import is a " +
                "user mistake the sentence can undo: $tierRefusal",
            tierRefusal!!.contains("'npu'") && tierRefusal.contains("'npu-turbo'")
        )
        val wrongHtp = NpuPackMetadata.parse(metaJson(htpVersion = "75"))
        val htpRefusal = NpuPackMetadata.crossCheckRefusal(wrongHtp, family, artifact, "npu-turbo")
        assertNotNull(htpRefusal)
        assertTrue(
            "the htp arm names both versions: $htpRefusal",
            htpRefusal!!.contains("v75") && htpRefusal.contains("v79")
        )
        val wrongGroup = NpuPackMetadata.parse(metaJson(packGroup = "\"soc_8gen3\""))
        val groupRefusal =
            NpuPackMetadata.crossCheckRefusal(wrongGroup, family, artifact, "npu-turbo")
        assertNotNull(groupRefusal)
        assertTrue(
            "the group arm names both groups: $groupRefusal",
            groupRefusal!!.contains("'soc_8gen3'") && groupRefusal.contains("'soc_8elite_galaxy'")
        )
    }

    @Test
    fun theDigestMismatchArmDefersToEntryEqualityIdentityNotIntegrity() {
        // Identity vs integrity, stated as behaviour: a metadata digest that disagrees with
        // the family's artifact row means the WRONG PACK arrived — the file may be perfectly
        // intact. The refusal says "different pack"; the copy's own streamed sha256 remains
        // the corruption gate, and nothing here weakens it.
        val otherDigest = NpuFleetCensus.artifactFor("8gen3", "npu-turbo")!!.encoder.sha256
        val meta = NpuPackMetadata.parse(
            metaJson(entries = entriesJson(encoderSha = otherDigest))
        )
        val refusal = NpuPackMetadata.crossCheckRefusal(meta, family, artifact, "npu-turbo")
        assertNotNull("a digest disagreement refuses at the peek", refusal)
        assertTrue(
            "it names the entry and BOTH digests: $refusal",
            refusal!!.contains(artifact.encoder.fileName) &&
                refusal.contains(otherDigest) && refusal.contains(artifact.encoder.sha256)
        )
        assertTrue(
            "and calls it what it is — a different pack, with the damage question explicitly " +
                "left to the stream hash: $refusal",
            refusal.contains("different pack")
        )
        // The name and byte arms carry the same identity framing:
        val wrongBytes = NpuPackMetadata.parse(
            metaJson(entries = entriesJson(encoderBytes = artifact.encoder.bytes + 1))
        )
        val bytesRefusal =
            NpuPackMetadata.crossCheckRefusal(wrongBytes, family, artifact, "npu-turbo")
        assertNotNull(bytesRefusal)
        assertTrue(
            "the byte arm names both counts: $bytesRefusal",
            bytesRefusal!!.contains("${artifact.encoder.bytes + 1}") &&
                bytesRefusal.contains("${artifact.encoder.bytes}")
        )
        val wrongName = NpuPackMetadata.parse(
            metaJson(entries = entriesJson(encoderName = "encoder_qairt_context.bin"))
        )
        val nameRefusal =
            NpuPackMetadata.crossCheckRefusal(wrongName, family, artifact, "npu-turbo")
        assertNotNull(nameRefusal)
        assertTrue(
            "the name arm names both files — the vendor bare name against turbo's renamed " +
                "delivery name is exactly the repack fault it would catch: $nameRefusal",
            nameRefusal!!.contains("encoder_qairt_context.bin") &&
                nameRefusal.contains("turbo_encoder_qairt_context.bin")
        )
        assertFalse(
            "no cross-check sentence blames a corrupted download — identity refusals must " +
                "not send the user re-downloading a file that arrived intact",
            listOf(refusal, bytesRefusal, nameRefusal).any { it.contains("corrupt") }
        )
    }
}
