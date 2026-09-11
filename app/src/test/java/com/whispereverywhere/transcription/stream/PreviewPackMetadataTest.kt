package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The three catalogue fields that are facts about a FILE rather than decisions — `decode_chunk_len`
 * (the cadence) and `T` (the frames one forward pass consumes, and therefore the commit pad) —
 * pinned as literals AND, wherever the pack payload is on disk, re-read from the encoder's own
 * `metadata_props` and held equal to them (qualification table E1: *"add `decodeChunkLen` to
 * `StreamingPack`, derive `padMs` from `T`, and assert both against the Range-read metadata"*).
 *
 * ### Why the literals are the pin and the file is the corroboration
 *
 * The four pack files are a BUILD artifact — `tools/build_asset_packs.py preview` places them and
 * the module's `.gitignore` keeps them structurally uncommittable — so a clean clone has an empty
 * payload directory and this file cannot be a required input to anything. The literals therefore
 * carry the census, exactly as the byte counts and the sha256s do, and the live re-read runs where
 * the payload exists (a machine that has placed the pack) and is SKIPPED where it does not.
 *
 * That is not a hole: the payload can only change under a changed `sha256`, and the sha256s ARE
 * pinned inputs in three places (the catalogue, `verifyPreviewPack`'s table, the build script's
 * placement table, all held equal by `PreviewPackLayoutTest`). A payload that disagrees with these
 * literals is a payload that disagrees with its own digest first. This test is the only reader in
 * the suite that can catch the OTHER direction — a literal typed wrong against bytes that are
 * right — which is the direction a second language will make easy to get wrong, since `T` is
 * `decodeChunkLen + 13` on a `zipformer2` export and `decodeChunkLen + 7` on a v1 one (32 → 45
 * here, 32 → **39** for French) and neither is derivable from the other.
 */
class PreviewPackMetadataTest {

    private val pack = StreamingPackCatalog.EN

    // ---------------------------------------------------------------- the literals

    @Test fun theCatalogRecordsTheShippingEncodersCadenceAndFrameCount() {
        assertEquals("decode_chunk_len, read off the encoder's metadata_props", 32, pack.decodeChunkLen)
        assertEquals("T, read off the same place", 45, pack.encoderT)
        assertEquals("320 ms per forward pass after the first", 320L, pack.cadenceMs)
    }

    @Test fun thePadIsDerivedFromThatFrameCountAndReproducesTheMeasuredFiveHundred() {
        // The one pad ever measured on a device (rung 1 §4, rung 3 §5.3) is 500 ms, and it was
        // measured on THIS pack. The derivation must return it, or 4.4.1's validated behaviour
        // has quietly moved under a refactor that claimed to be a generalisation.
        assertEquals(500L, pack.padMs)
        assertEquals(StreamingPreviewTuning.padMsFor(pack.encoderT), pack.padMs)
    }

    // ---------------------------------------------------------------- the file itself

    @Test fun theRecordedCadenceAndFrameCountAreWhatTheEncoderFileSays() {
        val encoder = payloadEncoder()
        assumeTrue(
            "the pack payload is a build artifact and is absent from a clean clone — " +
                "tools/build_asset_packs.py preview places it",
            encoder != null,
        )
        val meta = OnnxMetadata.read(encoder!!)
        assertEquals("decode_chunk_len", pack.decodeChunkLen.toString(), meta["decode_chunk_len"])
        assertEquals("T", pack.encoderT.toString(), meta["T"])
        // And the export is the STREAMING one. The offline tell (qualification table §5.4): an
        // offline zipformer2 export writes `comment = "non-streaming zipformer2"` and OMITS
        // decode_chunk_len, query_head_dims and value_head_dims. Four plausible ja/vi candidates
        // died on exactly this read, so it is asserted here rather than remembered.
        assertEquals("streaming zipformer2", meta["comment"])
        assertTrue("query_head_dims is present on a streaming export", meta.containsKey("query_head_dims"))
        assertTrue("value_head_dims is present on a streaming export", meta.containsKey("value_head_dims"))
    }

    /** The encoder under the pack module's payload directory, or null when it has not been placed. */
    private fun payloadEncoder(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "${StreamingPackCatalog.PACK_EN}/src/main/assets/" +
            "${StreamingPackCatalog.PACK_EN}/${pack.encoder.name}"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile && candidate.length() == pack.encoder.bytes) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
