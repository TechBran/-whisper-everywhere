package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * `melbank-128.bin` — the shipped 128-bin filterbank, asserted against the bytes that ship (4.1 L3).
 *
 * ### What this asset IS, stated once
 *
 * A ggml whisper model's layout is magic, hparams, filterbank, vocab, tensors, **in that order**, so
 * everything a mel computation needs is a contiguous PREFIX of the file. The fork's
 * `whisper_init_from_file_mel_only` reads exactly that prefix and stops. It follows — and this is
 * the whole design — that a `large-v3-turbo` ggml truncated at the end of its filterbank is not a
 * damaged model file: **it is a valid mel-only ggml**, and the loader accepts it unchanged. 102,968
 * bytes of it, extracted by `tools/extract_melbank.py` from the `ultra` tier's own
 * `ggml-large-v3-turbo-q5_0.bin`, whose sha256 the catalog already pins as `SHA256_ULTRA`.
 *
 * That is why there is no donor model for a 128-bin tier and no second mel implementation: the
 * 80-bin path keeps taking its filterbank out of whichever whisper model is installed (they all
 * carry a byte-identical 80x201 matrix), and the 128-bin path hands `initMelOnly` the same *kind* of
 * file it already loads.
 *
 * ### Why the assertions are the ones they are
 *
 * **Nothing here executes `initMelOnly`, and no JVM test can** — `libwhisper_jni.so` is not on the
 * unit-test classpath and never will be. The load is first proved on device at L8, where it is
 * self-checking: the fork's `if (loaded && !fin)` guard refuses a file that is one byte short (
 * `istream::read` sets `eofbit` only on a SHORT read, so an exact truncation loads and a near-miss
 * does not), and its `n_mel`/`n_fft` bounds refuse a file that is not a filterbank at all.
 *
 * So these assertions target the exact MUTATIONS rather than asserting the file exists: the length
 * to the byte in both directions, the digest, and the two header agreements the fork's loader
 * itself checks — run here, against the shipped bytes, months before a device sees them.
 *
 * **The asset is read from `app/src/main/assets` by path**, with the house `source(relative)`
 * walker, because the assets directory is not on the JVM test classpath. It is read as BYTES with
 * no line-ending normalisation: this is a binary file, and `\r\n` -> `\n` inside float coefficients
 * would corrupt exactly the thing being measured.
 */
class MelbankAssetTest {

    /**
     * Locates a repo file from the test's working directory — the walker `NpuNativeContractTest`,
     * `MelExportContractTest` and `SegmentTimingTest` share, stopping at the `File` instead of its
     * text. Binary here, deliberately: [readText] plus the house `\r\n` normalisation would rewrite
     * coefficient bytes.
     */
    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** A repo file's text, LF-normalised at this single read site (the 3.7 N1 lesson). */
    private fun text(relative: String): String = source(relative).readText().replace("\r\n", "\n")

    private val assetFile: File by lazy { source("src/main/assets/${NpuModelSpec.MELBANK_128_ASSET}") }
    private val bytes: ByteArray by lazy { assetFile.readBytes() }

    private fun sha256(of: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

    /** Little-endian `int32` at [at] — the byte order every ggml header field is written in. */
    private fun int32(at: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

    // The header's field offsets, spelled out so a reader can check them against the fork's
    // `whisper_model_load_mel_only` read order rather than trusting a number.
    private val magicAt = 0                 // uint32 GGML_FILE_MAGIC
    private val nMelsAt = 4 + 9 * 4         // hparams[9], after n_vocab..n_text_layer
    private val filtersNMelAt = 4 + 11 * 4  // the first field after the eleven hparams
    private val filtersNFftAt = 4 + 12 * 4

    // ---------------------------------------------------------------- the asset itself

    /**
     * **THE ASSERTION THIS TASK EXISTS FOR.** The shipped file is byte-for-byte the first 102,968
     * bytes of `ggml-large-v3-turbo-q5_0.bin`, and it is that length for an arithmetic reason
     * rather than by measurement.
     *
     * Three claims, and each catches a different way to regenerate this file wrongly:
     *
     *  - **the exact length**, so a byte of slop in either direction is a red. Long is the
     *    dangerous direction: the fork's truncation guard fires on a SHORT read only
     *    (`istream::read` sets `eofbit` when it cannot fill the request, and never when it can), so
     *    102,969 bytes would LOAD, with one stray byte of vocabulary silently past the filterbank.
     *    Nothing downstream would report it.
     *  - **the length as `56 + n_mel * n_fft * 4`**, computed from the header the file itself
     *    carries. The literal above and this identity are two independent readings; an extractor
     *    that wrote the wrong slice would have to be wrong in both.
     *  - **the digest**, compared against [NpuModelSpec.MELBANK_128_SHA256] *and* against the
     *    literal, because the constant is what the runtime stages against and a test that only
     *    compared the file to the constant would stay green if both moved together.
     */
    @Test
    fun theShippedFilterbankIsExactlyTheTurboPrefix() {
        assertEquals(
            "melbank-128.bin must be exactly 102,968 bytes. A byte SHORT and the fork's " +
                "`loaded && !fin` guard refuses the file, which is loud; a byte LONG and it loads " +
                "with a fragment of the vocabulary appended, which is silent, because " +
                "istream::read sets eofbit only on a read it could not satisfy.",
            NpuModelSpec.MELBANK_128_BYTES,
            assetFile.length(),
        )
        assertEquals(
            "…and that length must be 56 + n_mel * n_fft * 4 computed from the file's OWN header " +
                "(4 magic + 11 int32 hparams + n_mel + n_fft, then the coefficients). Same number, " +
                "read a second way: an extractor that sliced at a hardcoded offset would have to " +
                "be wrong here too.",
            (56 + int32(filtersNMelAt).toLong() * int32(filtersNFftAt).toLong() * 4L),
            assetFile.length(),
        )
        val digest = sha256(bytes)
        assertEquals(
            "melbank-128.bin's sha256 must be the digest tools/extract_melbank.py refuses to " +
                "write anything else under. This is the prefix of the ggml whose own sha256 is " +
                "the catalog's SHA256_ULTRA, so the provenance chain runs from a value the app " +
                "already ships to the bytes in the APK.",
            "72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c",
            digest,
        )
        assertEquals(
            "…and the constant the RUNTIME stages against must be that same digest. Two readings " +
                "of one value: comparing the file only against the constant would stay green if " +
                "somebody regenerated both together.",
            NpuModelSpec.MELBANK_128_SHA256,
            digest,
        )
    }

    /**
     * The magic, which is the only thing standing between an arbitrary 103 KB file and 102,912
     * bytes of it being read as float32 filter coefficients.
     */
    @Test
    fun theAssetCarriesTheGgmlMagicAndIsThereforeAWhisperModelPrefix() {
        assertEquals(
            "the first four bytes must be GGML_FILE_MAGIC (0x67676d6c, little-endian). The " +
                "mel-only loader checks this first and refuses everything else; an asset that " +
                "failed it would decline at stage=mel-init on device, which is correct but is a " +
                "device round trip to learn something measurable here.",
            0x67676d6c,
            int32(magicAt),
        )
    }

    /**
     * The two band counts, and the fact that they AGREE — which is the fork loader's own second
     * check, run here before the file ships rather than after.
     *
     * They are different fields. `hparams.n_mels` is what `whisper_model_n_mels()` reports and what
     * `pcmToMel` gates the caller on; `filters.n_mel` is what `log_mel_spectrogram` actually indexes
     * with. Nothing in the FULL loader compares them, because nothing in the full path depends on
     * them agreeing — but this file exists to be handed to exactly the two functions that read one
     * each, so a disagreement would be a wrong mel with nothing to attribute it to.
     */
    @Test
    fun theHeaderAndTheFilterbankBothDeclareOneHundredAndTwentyEightBands() {
        assertEquals(
            "hparams.n_mels must be 128 — the count whisper_model_n_mels() reports, which is what " +
                "pcmToMel compares against the tier's melBins",
            128,
            int32(nMelsAt),
        )
        assertEquals(
            "filters.n_mel must be 128 — the count log_mel_spectrogram indexes the coefficients " +
                "with, and the one that sizes this file",
            128,
            int32(filtersNMelAt),
        )
        assertEquals(
            "and they must AGREE. This is whisper_model_load_mel_only's own refusal, made here " +
                "instead: a file declaring 80 over a 128-band filterbank passes a caller's " +
                "bin-count gate and then writes 1,536,000 bytes into a 960,000-byte destination.",
            int32(nMelsAt),
            int32(filtersNMelAt),
        )
    }

    /**
     * `n_fft = 201` — the second of the fork loader's two checks, and the other factor in the
     * length.
     *
     * 201 is `WHISPER_N_FFT / 2 + 1` for whisper's 400-sample window, i.e. the number of positive
     * frequency bins each mel band weights. It is the same on every whisper model at every size;
     * what varies is the band count.
     */
    @Test
    fun theFilterbankIsTwoHundredAndOneFftBinsWide() {
        assertEquals(
            "filters.n_fft must be 201 (400/2 + 1 positive frequency bins). The mel-only loader " +
                "bounds this field precisely because a garbage value would be allocated before " +
                "anything else noticed; 201 is also the factor that makes the file 102,968 bytes " +
                "rather than some other length.",
            201,
            int32(filtersNFftAt),
        )
        assertEquals(
            "and the coefficient block must be exactly n_mel * n_fft floats — the file minus its " +
                "56-byte header",
            128L * 201L * 4L,
            assetFile.length() - 56L,
        )
    }

    // ---------------------------------------------------------------- the three readings agree

    /**
     * The asset, the spec's constants and the extractor are **one value read three times**.
     *
     * `tools/extract_melbank.py` asserts the digest on the way out, this suite asserts it against
     * the shipped bytes, and `NpuWhisperBackend` stages against `NpuModelSpec`'s copy at run time.
     * Three readings is the design: if the extractor and the test each carried their own literal,
     * a wrong regeneration would simply make the two agree about the wrong thing, which is the
     * shape of every "the test was updated to match" defect.
     */
    @Test
    fun theSpecTheAssetAndItsNameAreOneValue() {
        assertEquals(
            "NpuModelSpec.MELBANK_128_ASSET must be the name of the file that actually ships, " +
                "because it is the string handed to AssetManager.open() at run time and a typo " +
                "there is a FileNotFoundException on a device rather than a red here",
            NpuModelSpec.MELBANK_128_ASSET,
            assetFile.name,
        )
        assertEquals(
            "NpuModelSpec.MELBANK_128_BYTES must be the shipped length — NpuAssetStage refuses a " +
                "staged copy of any other size, so a stale constant would refuse the correct asset",
            NpuModelSpec.MELBANK_128_BYTES,
            assetFile.length(),
        )
        assertEquals(
            "and the file must live in app/src/main/assets, which is the only directory " +
                "AssetManager can reach. A correctly-named file one directory up is invisible to " +
                "the APK and produces exactly the same red as a missing one, so the location is " +
                "asserted rather than assumed.",
            "assets",
            assetFile.parentFile?.name,
        )
        // AND `melAsset` HAS NO DEFAULT — the L2 no-default doctrine, arriving at the field that
        // decides where a tier's mel comes from.
        //
        // `melAsset: String? = null` compiles, keeps `SMALL` working, keeps every test in this
        // suite green, and makes the wrong answer the SILENT one: a future 128-bin row that forgot
        // the line would take the installed-donor arm, load an 80-bin filterbank, and hand 240,000
        // floats to a graph expecting 384,000. It is caught at run time by `pcmToMel`'s band check
        // — loudly, and only on a device — whereas the whole point of the required field is that
        // the row's author has to decide in the file where the row is written.
        //
        // A source pin because no call can observe it: a construction that omitted the argument
        // would not compile, so there is nothing for a test to execute.
        val spec = text("src/main/java/com/whispereverywhere/npu/NpuModelSpec.kt")
        assertTrue(
            "NpuModelSpec must declare `val melAsset: String?,` as a constructor field",
            spec.contains("val melAsset: String?,"),
        )
        assertTrue(
            "…with NO DEFAULT. A row that does not say where its mel comes from is a row whose " +
                "author did not decide, and the default would decide donor for them — silently, " +
                "and wrongly for every 128-bin tier. Same rule and same reason as `spec` on " +
                "NpuWhisperBackend and `family` on NpuDecodePolicy. Found: " +
                spec.lines().filter { it.contains("melAsset: String?") },
            !spec.contains("val melAsset: String? ="),
        )
    }

    /**
     * The extractor pins **both** digests as literals, so the derivation cannot be quietly widened.
     *
     * The source digest is the provenance claim — this asset came out of the `ultra` tier's ggml
     * and not out of some other 128-bin file — and the output digest is the reproducibility claim.
     * An extractor that asserted neither would still produce this file today and would produce a
     * different one from a different input, with nothing between the two.
     */
    @Test
    fun theExtractorPinsBothDigestsAsLiterals() {
        val script = text("tools/extract_melbank.py")
        assertTrue(
            "tools/extract_melbank.py must carry the SOURCE ggml's sha256 as a literal — the " +
                "catalog's SHA256_ULTRA (394221709cd5…). Checking the provenance against a value " +
                "the app already ships, rather than against the script's own opinion, is what " +
                "makes the extraction auditable from inside the repo.",
            script.contains("394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
        )
        assertTrue(
            "…and the OUTPUT's sha256 as a literal, asserted after the write. Without it the " +
                "script is a truncation with no opinion about what it produced.",
            script.contains(NpuModelSpec.MELBANK_128_SHA256),
        )
        assertTrue(
            "…and the byte count, so the slice is a stated number rather than an emergent one",
            script.contains("102968") || script.contains("102_968"),
        )
        assertTrue(
            "the extractor must run under the ABSOLUTE interpreter, like tools/fetch_qnn_headers.py " +
                "and CMake's Python3_EXECUTABLE: bare `python` on this machine is the Windows-Store " +
                "alias stub, which resolves first and does nothing",
            script.contains("Python313/python.exe") || script.contains("Python313\\python.exe"),
        )
    }

    // ---------------------------------------------------------------- the two ship gates

    /**
     * THE SHIPPED LICENCE PAGE ATTRIBUTES THIS ASSET, AND NAMES THE DERIVATION.
     *
     * Q5's I1 established that `oss_licenses.html` is a **ship gate** and that its edits must be
     * pin-protected — measured as a battery survivor there, where reverting an attribution to the
     * wrong licence left 1,489 tests green. This asset is the same class of thing and sharper: it
     * is a *derived work* of a published model file, so the page has to say what it was derived
     * from and how, not merely that whisper exists.
     */
    @Test
    fun theShippedLicencePageAttributesThisFilterbank() {
        val page = text("src/main/assets/oss_licenses.html")
        assertTrue(
            "the licence page must name the mel filterbank as its own item. The existing entries " +
                "cover the model WEIGHTS (MIT) and the tokenizer VOCABULARY (Apache-2.0); a " +
                "filterbank extracted from a ggml conversion is a third kind of material and is " +
                "not covered on its face by either.",
            page.contains("Whisper mel filterbank"),
        )
        val entry = page.substringAfter("Whisper mel filterbank").substringBefore("</div>")
        assertTrue(
            "the attribution must name the model it is derived from, so the claim is checkable " +
                "rather than remembered. Found: $entry",
            entry.contains("large-v3-turbo"),
        )
        assertTrue(
            "…and state the derivation in bytes — the first 102,968 bytes, i.e. magic + hparams + " +
                "filterbank. \"Derived from\" without the method is not a statement anybody can " +
                "check. Found: $entry",
            entry.contains("102,968") || entry.contains("102968"),
        )
        assertTrue(
            "…and carry the licence. MIT for the model data it is a slice of, Apache-2.0 for the " +
                "ggml conversion tooling that produced the layout. Found: $entry",
            entry.contains("MIT") && entry.contains("Apache"),
        )
    }

    /**
     * The asset and the extractor are declared inputs of the test task.
     *
     * D5, the stale-evidence hazard, and it bites hardest exactly here: **a binary asset and a
     * Python script are inputs to no compile task at all**, so regenerating `melbank-128.bin`
     * wrongly, or loosening the extractor's assertions, changes not one `.class` file. Without
     * these entries `:app:testDebugUnitTest` reports UP-TO-DATE and every assertion above passes
     * against the file as it used to be — which is precisely the change being guarded against.
     */
    @Test
    fun theAssetAndTheExtractorAreDeclaredInputsOfTheTestTask() {
        val gradle = text("build.gradle.kts")
        assertTrue(
            "app/build.gradle.kts must list \"src/main/assets/melbank-128.bin\" in " +
                "sourcePinnedInputs. This suite is the only reader of those bytes, and a binary " +
                "asset is an input to no compile task, so without the entry a regenerated file " +
                "leaves the test task UP-TO-DATE and the digest pin never re-runs.",
            gradle.contains("\"src/main/assets/melbank-128.bin\""),
        )
        assertTrue(
            "…and rootProject.file(\"tools/extract_melbank.py\"), for the same reason and one " +
                "step further out: the script lives outside the app module, so it is not even a " +
                "candidate input by convention. Same shape as the .gitignore entry beside it.",
            gradle.contains("rootProject.file(\"tools/extract_melbank.py\")"),
        )
    }
}
