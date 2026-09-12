package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.CanaryAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * THE CANARY CLIPS — one per language, each held to the things that make a clip a verdict input
 * rather than a sound file (4.5.0 T3).
 *
 * ### Why each gate is here
 *
 * A [PreviewCanary] Fail switches a language's live words off for the process. So the clip is the
 * input to the only guard the previewer has against the FEAT_SME silent miscompute (sherpa-onnx
 * #3845 — EMPTY text for a whole stream on SM8850 + ORT 1.27.0; #3791 — `"MY WOMAN"` for a
 * five-word clip on an M4). Five properties are pinned, and the first four need no model at all,
 * because these assets are COMMITTED — unlike the pack payload:
 *
 *  1. **the bytes are the bytes** — length and sha256, so a re-encoded, re-recorded or re-cut clip
 *     is a red test and not a silently different verdict;
 *  2. **the format is the one the loader accepts** — PCM16 mono 16 kHz, through `CanaryAudio`'s own
 *     parsers, because a clip the loader rejects is [CanaryVerdict.NoClip] and takes the row OFF;
 *  3. **the rule is DERIVED from the clip** — every position is a rendering the real pack was
 *     OBSERVED producing from this audio, the recorded measurement matches EVERY position (so
 *     `minMatches` is slack and not the operating point), the healthy output sits at half the
 *     runaway ceiling or below, and every corruption shape fails;
 *  4. **the rule is SPECIFIC to its pack** — no row's recorded output satisfies another row's rule,
 *     which is the whole reason the clip is per-pack;
 *  5. **every rendering is spellable from that pack's own `tokens.txt`** — the mechanical half of
 *     *"checkable against tokens.txt, never by ear"*, and the reason a non-speaker may ship a
 *     Korean or a Russian canary at all. Payload-gated, like `PreviewPackMetadataTest`.
 *
 * ### Where the recorded measurements come from
 *
 * [Clip.tokens] and [Clip.text] are the AAR's two answers, measured on **2026-09-12** on this
 * branch by driving each pack's own downloaded payload through **sherpa-onnx 1.13.7** — the version
 * the shipped AAR reports — with `SherpaPreviewRecognizerFactory`'s exact configuration (16 kHz /
 * 80-dim features, `dither = 0`, `provider = "cpu"`, `model_type = ""`, `enableEndpoint = false`,
 * `greedy_search`, 2 threads) and [PreviewCanary.run]'s exact procedure: the clip in 512-sample
 * chunks, decoding while `isReady`, then ONE pad of `padSamplesFor(encoderT)` zeros, then
 * `inputFinished` and a drain.
 *
 * The harness was made to reproduce the DEVICE's own recorded English result first — `ONE TWO
 * THREE FOUR FIVE`, 9 decodes, 8,000 pad samples (rung 3 §4 and
 * `PreviewCanaryTest.theMeasuredTextPasses`) — before it was believed about any other language.
 * Each clip was then re-measured across **nine cells** (1/2/4 threads × 0.85/1.00/1.15 input gain)
 * and every cell matched every position: threads change the reduction order inside the encoder,
 * which is the same class of difference an ARM build has against an x86 one, and the gain walks
 * the decode off its exact operating point.
 *
 * **It is still a PC measurement.** The device arm is the acceptance sheet's
 * (`stream-open: … canary=pass`, per language), and these clips are what it will read.
 */
class PreviewCanaryClipsTest {

    /**
     * @property tokens what `result.tokens` came back as — the AAR's pieces, each with the leading
     *   space `SymbolTable` writes for `▁`.
     * @property text what `result.text` came back as. **Recorded separately from [tokens] because
     *   on a CJK-classified pack they are different strings**, and the canary scores the strip
     *   (the tokens) rather than the text. Holding both is what makes that difference an asserted
     *   fact instead of a remembered one.
     * @property source where the audio came from and under what licence — recorded here as well as
     *   in the catalogue row's comment, because this is the file a reviewer checking the licence
     *   page will land on.
     */
    private data class Clip(
        val language: String,
        val asset: String,
        val bytes: Long,
        val sha256: String,
        val samples: Int,
        val tokens: List<String>,
        val text: String,
        val source: String,
    )

    private val clips = listOf(
        Clip(
            language = "en",
            asset = "canary_digits.wav",
            bytes = 81_998L,
            sha256 = "a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4",
            samples = 40_960,
            tokens = listOf(" ONE", " TWO", " THREE", " F", "OUR", " FI", "VE"),
            text = "ONE TWO THREE FOUR FIVE",
            // The owner's own recording, bundled in 3.6.0 Workstream C (commit 5484e3d, "bundle
            // the canary clip — owner-recorded spoken digits"). No third-party licence: the
            // speaker is the app's author. Its length and digest are also recorded in
            // docs/measurements/2026-09-10-tab-sherpa-rung3.md, where the DEVICE produced this
            // exact text in 8 of 8 runs.
            source = "owner recording, 3.6.0 Workstream C — no third-party licence",
        ),
        Clip(
            language = "fr",
            asset = "canary_fr_digits.wav",
            bytes = 47_498L,
            sha256 = "4fcf2d1d3553f631840cd6883121d8cd245accbf66a8f5944b692aa6ff312443",
            samples = 23_710,
            tokens = listOf(" UN", " DEUX", " TROIS", " QUATRE", " CINQ"),
            text = "UN DEUX TROIS QUATRE CINQ",
            // SYNTHESIZED in-repo from the voice model this app already ships:
            // kokoro-multi-lang-v1_0 (Apache-2.0 — the archive `tts_kokoro` delivers, and the
            // licence page's "Kokoro-82M (hexgrad)" entry), speaker id 30 = ff_siwis
            // (TtsVoices.kt:47), text "un deux trois quatre cinq", speed 1.0, phonemized by the
            // archive's own espeak-ng-data under the voice name `fr`, resampled 24 → 16 kHz by
            // ffmpeg. Kokoro's model card licenses the weights Apache-2.0 and the audio is that
            // model's OUTPUT, so nothing here is third-party recorded speech.
            source = "kokoro-multi-lang-v1_0 / ff_siwis (Apache-2.0) — synthesized, not recorded",
        ),
        Clip(
            language = "zh",
            // The SAME asset as the English row, which is the whole point: this row's canary is
            // free. `noRowsMeasuredOutputSatisfiesAnotherRowsRule` skips the en/zh pair for
            // exactly this reason — two rows naming one clip are expected to agree.
            asset = "canary_digits.wav",
            bytes = 81_998L,
            sha256 = "a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4",
            samples = 40_960,
            // The bilingual pack's OWN decomposition of the same audio, and it is not the English
            // pack's: `▁F` + `IVE` where English emits `▁FI` + `VE`. Both render FIVE, which is
            // why the shared clip works — and why the row's old prediction of the split was wrong
            // while its conclusion was right.
            tokens = listOf(" ONE", " TWO", " THREE", " FOUR", " F", "IVE"),
            text = "ONE TWO THREE FOUR FIVE",
            source = "owner recording, 3.6.0 Workstream C — shared with the en row at zero cost",
        ),
    )

    // ------------------------------------------------------------------ the catalogue agrees

    @Test fun everyRowWithACanaryHasAClipRecordedHereAndNothingElseDoes() {
        val rows = StreamingPackCatalog.packs.filter { it.canary != null }.map { it.language }
        assertEquals(
            "a row that grows a canary must record its clip in this table — these gates are the " +
                "only mechanical check that a clip is a verdict input and not a sound file",
            rows.sorted(), clips.map { it.language }.sorted(),
        )
        for (clip in clips) {
            assertEquals(
                "${clip.language}: the row and this table must name the SAME asset",
                clip.asset, packOf(clip).canary!!.asset,
            )
        }
    }

    // ------------------------------------------------------------------ the bytes are the bytes

    @Test fun everyClipIsInMainAssetsAtItsPinnedByteCountAndDigest() {
        for (clip in clips) {
            val bytes = asset(clip.asset).readBytes()
            assertEquals("${clip.asset} byte count", clip.bytes, bytes.size.toLong())
            assertEquals("${clip.asset} sha256", clip.sha256, sha256(bytes))
        }
    }

    @Test fun everyClipIsThePcm16MonoSixteenKilohertzTheLoaderAccepts() {
        // Through `CanaryAudio`'s OWN parsers, not a second reading of the header: a clip the
        // loader rejects is `CanaryVerdict.NoClip`, which takes the language off, and a clip that
        // parsed differently here than in production would prove nothing.
        for (clip in clips) {
            val bytes = asset(clip.asset).readBytes()
            assertTrue(
                "${clip.asset}: CanaryAudio.formatIsValid must accept it — anything but " +
                    "mono/16 kHz/PCM16 is NO VERDICT, and no verdict on a NAMED clip disables the row",
                CanaryAudio.formatIsValid(bytes),
            )
            assertEquals(
                "${clip.asset}: PCM16 frames in the data chunk",
                clip.samples * 2, CanaryAudio.dataChunk(bytes).size,
            )
            // The clip is decoded whole on every warm, so its length is a cost on the load path as
            // well as in the APK. The band is stated rather than left implicit: below ~1 s there is
            // not enough speech to carry five positions, and above ~5 s the canary starts costing
            // more than the model load it guards.
            val seconds = clip.samples / 16_000.0
            assertTrue(
                "${clip.asset} is $seconds s — a canary clip belongs in [1.0, 5.0] s",
                seconds in 1.0..5.0,
            )
        }
    }

    // ------------------------------------------------------------------ the rule is DERIVED

    @Test fun theStripIsWhatEachRulesPositionsWereDerivedFrom() {
        for (clip in clips) {
            val rule = packOf(clip).canary!!.rule
            assertTrue(
                "${clip.language}: the measured output must pass its own rule — '${shown(clip)}'",
                PreviewCanary.passes(shown(clip), rule),
            )
            assertEquals(
                "${clip.language}: EVERY position must match the measurement, not just " +
                    "minMatches of them. minMatches is the tolerance for a device that differs " +
                    "from this measurement; if the measurement itself needed the tolerance, the " +
                    "clip would be one drop away from disabling the language.",
                rule.expected.size, matches(shown(clip), rule),
            )
        }
    }

    @Test fun everyPositionsRenderingIsOneTheMeasurementActuallyProduced() {
        // The anti-invention pin. A position may be expected only because the model was OBSERVED
        // emitting it from THIS audio — never because a reference transcript says the word is in
        // there. (Every FLEURS row's positions are a subset of its published transcript AND of
        // what the decoder produced; this asserts the second half, which is the harder one.)
        for (clip in clips) {
            val tokens = PreviewCanary.normalize(shown(clip))
            for (aliases in packOf(clip).canary!!.rule.expected) {
                assertTrue(
                    "${clip.language}: no rendering in $aliases appears in the measured output " +
                        "'${shown(clip)}'. An expected position the decoder has never produced is " +
                        "a guess, and a canary made of guesses disables working languages.",
                    aliases.any { it in tokens },
                )
            }
        }
    }

    @Test fun everyMeasurementSitsWELLInsideItsOwnRunawayCeiling() {
        // A ceiling the healthy output nearly touches fails on ordinary variation instead of on
        // garbage. Half is the stated margin; the English row's 5 tokens against 20 is a quarter.
        for (clip in clips) {
            val rule = packOf(clip).canary!!.rule
            val tokens = PreviewCanary.normalize(shown(clip))
            assertTrue(
                "${clip.language}: ${tokens.size} tokens against a ${rule.maxTokens} ceiling",
                tokens.size * 2 <= rule.maxTokens,
            )
        }
    }

    @Test fun everyCorruptionShapeFailsEveryRowsRule() {
        // The shapes a device has produced, plus what any non-speech input produces. Digital
        // silence, white noise and a 440 Hz tone all decode to `""` on the real de/ru/ko packs
        // (measured 2026-09-12), so EMPTY is not hypothetical — it is what corruption looks like
        // here, and it is #3845's exact signature.
        for (clip in clips) {
            val rule = packOf(clip).canary!!.rule
            for (garbage in listOf("", "   ", ".,!?", "MY WOMAN", "шшш ののの ¿¿¿ qwx zzz")) {
                assertFalse(
                    "${clip.language}: '$garbage' must not pass this row's rule",
                    PreviewCanary.passes(garbage, rule),
                )
            }
            // And a runaway built from this row's OWN first rendering — the shape that contains
            // every expected position and is still garbage, so only the ceiling can fail it.
            val runaway = List(rule.maxTokens + 1) { rule.expected.first().first() }
                .joinToString(" ")
            assertFalse(
                "${clip.language}: a runaway of its own renderings must fail on the ceiling",
                PreviewCanary.passes(runaway, rule),
            )
        }
    }

    @Test fun noRowsMeasuredOutputSatisfiesAnotherRowsRule() {
        // THE reason the clip is per-pack, and it was measured: fed the English digits clip, the
        // French pack answers `TH TREE FORFACE`, the German one `VORFALL`, the Indonesian one
        // `TWI FOR` — none matching a single English position, which is indistinguishable from
        // the SME signature. This asserts the converse: each rule is tight enough that another
        // language's HEALTHY output fails it.
        //
        // The one legitimate collision is a SHARED clip: the bilingual zh-en row runs the English
        // clip, and therefore the English output, at zero canary cost. Rows naming the same asset
        // are expected to agree and are skipped.
        for (a in clips) {
            for (b in clips) {
                if (a.language == b.language || a.asset == b.asset) continue
                assertFalse(
                    "${b.language}'s rule accepts ${a.language}'s measured output " +
                        "'${shown(a)}' — a rule that loose cannot tell a wrong language from a " +
                        "corrupt one",
                    PreviewCanary.passes(shown(a), packOf(b).canary!!.rule),
                )
            }
        }
    }

    @Test fun aRowWhoseTEXTDiffersFromItsTOKENSIsWhyTheCanaryScoresTheStrip() {
        // The measurement that made the canary read the strip. For every Latin/Cyrillic pack the
        // AAR's two answers agree (the tokens joined ARE the text, modulo the leading space); on a
        // CJK-classified pack they do not, because `RemoveSpaceBetweenCjk` runs over the assembled
        // TEXT only (`online-recognizer-transducer-impl.h:69`) and its `IsCJK` range 0xA840-0xD7AF
        // contains Hangul. Asserting which rows agree and which do not keeps that a fact about
        // measured data rather than a claim in a comment.
        for (clip in clips) {
            val joined = clip.tokens.joinToString("").trim()
            if (clip.language == "ko") {
                assertNotEquals(
                    "ko: the tokens must carry the word spaces the text has lost — if these two " +
                        "ever agree, either the AAR stopped removing them or this measurement is " +
                        "stale, and scoring the text would then be harmless",
                    clip.text, joined,
                )
                assertEquals(
                    "ko: and the text is exactly the tokens with the spaces taken out",
                    clip.text, joined.replace(" ", ""),
                )
            } else {
                assertEquals(
                    "${clip.language}: tokens-joined and text must agree, which is why moving " +
                        "the canary to the strip cannot have changed this row's verdict",
                    clip.text, joined,
                )
            }
        }
    }

    // --------------------------------------------- the renderings are checkable against tokens.txt

    @Test fun everyPositionHasARenderingItsOwnVocabularyCanSpell() {
        // *"Checkable mechanically against tokens.txt, never by ear"* — the whole reason a
        // non-speaker may ship a Korean or a Russian canary. A position is reachable when the
        // pack's own vocabulary can spell one of its renderings, which is a DP over the piece set
        // and not a whole-piece lookup: `▁HABEN` is one German piece and `▁CAMPINGBEREICHE` is
        // several, and both arrive as one space-delimited word on the strip.
        //
        // Payload-gated exactly as `PreviewPackMetadataTest` is — `tokens.txt` is placed by
        // tools/build_asset_packs.py and absent from a clean clone. The gates above need no model,
        // so a clean clone still holds the bytes, the format and the derivation.
        var checked = 0
        for (clip in clips) {
            val pack = packOf(clip)
            val tokens = payloadTokens(pack) ?: continue
            checked++
            val pieces = PackTokenFacts.piecesOf(tokens).toSet()
            for (aliases in pack.canary!!.rule.expected) {
                // The alias is what `normalize` produces, i.e. lowercase; the vocabulary carries
                // its own case (ALL CAPS for en/fr/de/id/zh, lowercase Cyrillic for ru, single
                // characters for ko). Both spellings are offered and one must work. The string the
                // vocabulary has to spell starts with U+2581, because a whole token on the strip
                // comes from a piece that carried the word marker.
                assertTrue(
                    "${clip.language}: this vocabulary cannot spell any rendering in $aliases. " +
                        "Either the rule expects something the model cannot emit, or the payload " +
                        "under ${pack.packName} is not the pack this rule was derived from.",
                    aliases.any { alias ->
                        listOf(MARKER + alias, MARKER + alias.uppercase())
                            .any { PackTokenFacts.spellable(pieces, it) }
                    },
                )
            }
        }
        assumeTrue("no pack payload has been placed on this machine", checked > 0)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What [PreviewCanary] scores for this clip: the STRIP, built by the production function from
     * the measured tokens and text, so this test reads the same string the app paints.
     */
    private fun shown(clip: Clip): String = PreviewText.strip(
        PreviewResult(clip.text, clip.tokens, FloatArray(clip.tokens.size)),
        packOf(clip),
    )

    private fun packOf(clip: Clip): StreamingPack =
        StreamingPackCatalog.packs.single { it.language == clip.language }

    private fun matches(text: String, rule: PreviewCanaryRule): Int {
        val tokens = PreviewCanary.normalize(text)
        return rule.expected.count { aliases -> aliases.any { it in tokens } }
    }

    private fun sha256(of: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

    /** A committed main asset, found by walking up from the test's working directory. */
    private fun asset(name: String): File {
        val relative = "src/main/assets/$name"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** A pack's placed `tokens.txt`, or null — the payload is a build artifact. */
    private fun payloadTokens(pack: StreamingPack): File? {
        val module = pack.packName ?: return null
        val relative = "$module/src/main/assets/$module/${pack.tokens.name}"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile && candidate.length() == pack.tokens.bytes) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        /** U+2581, the word marker sherpa rewrites to a space on the way out. */
        const val MARKER = "▁"
    }
}
