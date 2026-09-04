package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 3.7 tuning table (spec "Tuning constants") pinned verbatim. Every value here is a decision
 * with a written derivation; a silent edit is a behaviour change, so it fails this test first.
 *
 * Two of these tests read `EndpointerTuning.kt` as TEXT instead of calling it, for the reason
 * `NativeVadSourceContractTest` gives for reading C++: what is load-bearing about a tuning constant
 * is not only its value but the derivation written beside it (the A/B ranges an owner turns, the
 * single-owner rulings Task D4 aliases against, the native line the number was copied from), and
 * this object's SCOPE ruling — no commit-cadence constant may ever live here — is a rule about what
 * must NEVER be added, which no value assertion can express.
 */
class EndpointerTuningTest {

    @Test fun the_shipped_tuning_table_is_pinned_verbatim() {
        assertEquals(0.50f, EndpointerTuning.ONSET_THRESHOLD, 0.0f)
        assertEquals(0.35f, EndpointerTuning.RELEASE_THRESHOLD, 0.0f)
        assertEquals(350L, EndpointerTuning.HANGOVER_MS)
        // The ACOUSTIC floor under it. ABSOLUTE, because it is not a knob: it is where the
        // hangover stops ending utterances and starts cutting inside words.
        assertEquals(300L, EndpointerTuning.HANGOVER_MIN_MS)
        assertEquals(300L, EndpointerTuning.MIN_SPEECH_MS)
        // THE SPEECH-EVIDENCE FLOOR (4.3.2): the encode gate, not a cut knob. Its own test below
        // carries the derivation; this row is the verbatim value.
        assertEquals(256L, EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)
        assertEquals(98L, EndpointerTuning.MICRO_PAUSE_MS)
        assertEquals(8L, EndpointerTuning.PROBE_BUDGET_MS)
        // The unit every consumer of the budget actually compares in (Task C10's retune). Pinned
        // beside the millisecond spelling rather than derived from it: a conversion this table
        // computed for itself would agree with a broken constant.
        assertEquals(8_000L, EndpointerTuning.PROBE_BUDGET_US)
        assertEquals(16, EndpointerTuning.PROBE_CUTOUT_FRAMES)
        assertEquals(-1.0f, EndpointerTuning.NO_VERDICT, 0.0f)
    }

    @Test fun the_frame_geometry_is_the_silero_window() {
        // whisper.cpp model header: n_window = 512 @ 16 kHz mono PCM16.
        assertEquals(512, EndpointerTuning.FRAME_SAMPLES)
        // ABSOLUTE first, derivation second (the D1/D2 lesson): a purely differential assertion
        // survives a mutation that moves both of its sides, so each of the three geometry numbers
        // is pinned to its own literal, and the two identities below stay as the explanation of
        // WHY those literals are the only legal ones.
        assertEquals(1024, EndpointerTuning.FRAME_BYTES)
        assertEquals(32L, EndpointerTuning.FRAME_MS)
        assertEquals(EndpointerTuning.FRAME_SAMPLES * 2, EndpointerTuning.FRAME_BYTES)
        assertEquals(1_000L * EndpointerTuning.FRAME_SAMPLES / 16_000L, EndpointerTuning.FRAME_MS)
    }


    // ---------------------------------------------------------------------------------------
    // THE SPEECH-EVIDENCE FLOOR (4.3.2). ABSOLUTE first, then the three relations its KDoc
    // argues from. Reference twin: tools/vadsim's `Tuning.min_evidence_ms`.
    // ---------------------------------------------------------------------------------------

    @Test fun the_speech_evidence_floor_is_256_eight_onset_frames_so_a_lone_quiet_yes_passes_and_a_six_frame_flicker_does_not() {
        assertEquals(256L, EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)
        // Eight whole 32 ms frames of p >= ONSET: the floor is a frame count wearing milliseconds.
        assertEquals(8 * EndpointerTuning.FRAME_MS, EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)
        // A lone quiet "yes" is ~300 ms of speech — nine or ten onset frames — and must ENCODE.
        assertTrue(9 * EndpointerTuning.FRAME_MS >= EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)
        // A 15 s bed where Silero flickered over ONSET for six frames must NOT.
        assertTrue(6 * EndpointerTuning.FRAME_MS < EndpointerTuning.MIN_SPEECH_EVIDENCE_MS)
        // At most MIN_SPEECH_MS: a VAD cut that passed the 300 ms span floor must not be skippable
        // on evidence in the normal case — SileroEndpointerEvidenceTest shows the 9-frame burst
        // that commits is also encoded.
        assertTrue(EndpointerTuning.MIN_SPEECH_EVIDENCE_MS <= EndpointerTuning.MIN_SPEECH_MS)
        // Native's batch floor is 250 ms on a RUN at 0.40; this is a TOTAL at 0.50, so "below in
        // spirit" is a statement about the frame grid: 256 is the first multiple of 32 above 250.
        assertTrue(EndpointerTuning.MIN_SPEECH_EVIDENCE_MS > 250L)
        assertTrue(EndpointerTuning.MIN_SPEECH_EVIDENCE_MS - EndpointerTuning.FRAME_MS <= 250L)
    }

    @Test fun the_release_threshold_is_the_native_schmitt_hysteresis() {
        // whisper.cpp:5258 -> neg_threshold = threshold - 0.15f
        assertEquals(0.15f, EndpointerTuning.ONSET_THRESHOLD - EndpointerTuning.RELEASE_THRESHOLD, 1e-6f)
    }

    @Test fun the_endpointer_onset_is_NOT_the_batch_filters_0_40() {
        // whisper_jni.cpp:191-192 keeps 0.40/150 ms for we_vad_filter: the probe decides WHEN to
        // cut, the batch filter decides WHAT reaches the encoder. Independent knobs, by design.
        assertNotEquals(0.40f, EndpointerTuning.ONSET_THRESHOLD)
    }

    // ---------------------------------------------------------------------------------------
    // THE FLATLINE CUT (4.4). Two constants, each ABSOLUTE first and then explained by the
    // arithmetic its KDoc argues from. Reference twin: tools/vadsim/vadsim/machine.py.
    // ---------------------------------------------------------------------------------------

    @Test fun the_flat_floor_is_10_an_order_of_magnitude_under_room_tone_because_only_digital_zero_transfers_from_desk_to_device() {
        assertEquals(10, EndpointerTuning.FLATLINE_RMS_MAX)
        // Room tone in a natural pause measures 50-300 RMS (the brief's premise, and the band the
        // simulator's sweep straddles on purpose): the floor must sit UNDER all of it, or a quiet
        // talker in a quiet room fires the trigger on real silence. Non-zero audio's scale does
        // not transfer between a PC wav and post-mixer device capture, so the floor is digital
        // zero plus decoder noise and nothing more.
        assertTrue(EndpointerTuning.FLATLINE_RMS_MAX < 50)
        assertTrue("strictly above zero: a 1-3 RMS decode floor must still read as flat",
            EndpointerTuning.FLATLINE_RMS_MAX > 0)
    }

    @Test fun the_flat_hold_is_5_chunks_because_a_150ms_stop_closure_fills_at_most_4_and_a_192ms_editor_gap_fills_5_at_every_phase() {
        assertEquals(5, EndpointerTuning.FLATLINE_CHUNKS)
        val chunkMs = EndpointerTuning.FRAME_MS
        // A plosive closure is 50-150 ms of near-silence INSIDE a word; 150 / 32 = 4.68, so it
        // can fill at most four whole 32 ms chunks. Five is therefore one more than any stop
        // closure can supply.
        assertTrue(EndpointerTuning.FLATLINE_CHUNKS > 150 / chunkMs)
        // The simulator's hold: the run's fifth chunk is 128 ms old (ages 0, 32, 64, 96, 128) —
        // `flatline_hold_ms = 128`, measured as the hangover measures a dip.
        assertEquals(128L, (EndpointerTuning.FLATLINE_CHUNKS - 1) * chunkMs)
        // The gap it catches: 160 ms when the gap happens to be chunk-aligned...
        assertEquals(160L, EndpointerTuning.FLATLINE_CHUNKS * chunkMs)
        // ...and 192 ms at EVERY alignment, because an unaligned gap's first and last chunks each
        // carry a sliver of speech (machine.py `flatline_gap_any_ms`).
        assertEquals(192L, (EndpointerTuning.FLATLINE_CHUNKS + 1) * chunkMs)
        // And it fires BEFORE the hangover could, or it would buy nothing: 5 < 12 at 350 ms.
        assertTrue(EndpointerTuning.FLATLINE_CHUNKS < EndpointerGrid.HANGOVER_FRAMES)
    }

    /**
     * The derivations, the owner-facing A/B ranges and the two single-owner rulings are pinned as
     * WHOLE SENTENCES, each scoped to the member it documents.
     *
     * Whole sentences and not distinctive words, because of the N6 K10 lesson: two contract items
     * that share one distinctive word mean the shorter anchor pins NEITHER — deleting the sentence
     * leaves the anchor satisfied by its neighbour, and the deletion is invisible.
     */
    @Test fun the_load_bearing_derivations_are_pinned_in_the_source() {
        val pins = listOf(
            // --- object KDoc: the two rulings that decide what this object is FOR ---
            Pin(
                "the batch filter is a different job, not a stale copy of this one",
                classKdoc(),
                "the streaming probe decides WHEN to cut an utterance, the batch filter decides " +
                    "WHAT audio inside that commit reaches the encoder"
            ),
            Pin(
                "why the batch filter's 0.40 is right there and wrong here",
                classKdoc(),
                "the batch filter's 0.40 buys onset headroom that `suppress_nst` absorbs at the " +
                    "token layer, and endpointing has no token layer"
            ),
            Pin(
                "smoothing is an absence with a reason, not an omission",
                classKdoc(),
                "There is deliberately NO smoothing/EMA constant."
            ),
            Pin(
                "the cost of adding one anyway",
                classKdoc(),
                "An EMA would add lag and a second thing to tune."
            ),
            // --- FRAME_BYTES / NO_VERDICT: the single-owner rulings Task D4 aliases against ---
            Pin(
                "FRAME_BYTES is the single owner of the native frame size",
                kdocFor("FRAME_BYTES"),
                "SINGLE OWNER: this object owns the JVM side of the native frame contract."
            ),
            Pin(
                "and D4 must alias it rather than restate it",
                kdocFor("FRAME_BYTES"),
                "`VadProbe.FRAME_BYTES` (Task D4) is an alias of this constant, not a second literal"
            ),
            Pin(
                "NO_VERDICT is not silence",
                kdocFor("NO_VERDICT"),
                "\"No verdict\" from the native probe — NEVER \"silence\"."
            ),
            Pin(
                "why the native side refuses a short frame instead of padding it",
                kdocFor("NO_VERDICT"),
                "A short frame zero-padded into the model still advances the LSTM and poisons the " +
                    "recurrence, so the native side refuses and the client keeps the previous state."
            ),
            Pin(
                "NO_VERDICT is the single owner of the sentinel",
                kdocFor("NO_VERDICT"),
                "SINGLE OWNER, as for [FRAME_BYTES]: `VadProbe.NO_VERDICT` (Task D4) aliases this."
            ),
            // --- the acoustic numbers: where each one came from, and which are owner knobs ---
            Pin(
                "the onset is the native default, copied not invented",
                kdocFor("ONSET_THRESHOLD"),
                "Native default (`whisper_vad_default_params`, whisper.cpp:4454)."
            ),
            Pin(
                "the release is the native Schmitt hysteresis",
                kdocFor("RELEASE_THRESHOLD"),
                "Schmitt hysteresis, native `neg_threshold = threshold - 0.15f` (whisper.cpp:5258)."
            ),
            Pin(
                "the hysteresis is the fix for a shipped defect, not a refinement",
                kdocFor("RELEASE_THRESHOLD"),
                "This is the exact mechanism whose absence causes today's 251-499 RMS dead band"
            ),
            Pin(
                "the release threshold is an owner A/B knob",
                kdocFor("RELEASE_THRESHOLD"),
                "Widen to 0.30 if mid-word splits appear in A/B."
            ),
            Pin(
                "the retune's premise: an extra encoder pass is a DUTY cost, not a per-cut one",
                kdocFor("HANGOVER_MS"),
                "the npu/npu-turbo encoder input is a fixed `[1,melBins,3000]` 30-second window " +
                    "(`whisper_jni.cpp:661-673`), so a pass costs the same ~1.78 s whether it " +
                    "carries one second of speech or fifteen"
            ),
            Pin(
                "and therefore which object owns the cost, now that this one does not",
                kdocFor("HANGOVER_MS"),
                "An extra encoder pass is a DUTY-CYCLE cost, and duty cycle is " +
                    "`CommitCadencePolicy.minCommitIntervalMs`'s job — not this constant's."
            ),
            Pin(
                "the cost this constant DOES still own, on every tier",
                kdocFor("HANGOVER_MS"),
                "inter-clause pauses run 200-500 ms, and a mid-clause boundary is one " +
                    "`no_context = true` makes unrepairable"
            ),
            Pin(
                "the hangover also feeds the batch filter's padding",
                kdocFor("HANGOVER_MS"),
                "Also feeds the batch filter's `speech_pad_ms = 150`, which needs trailing audio " +
                    "to expand into."
            ),
            Pin(
                "the hangover is an owner A/B knob",
                kdocFor("HANGOVER_MS"),
                "Owner A/B range 350-800."
            ),
            Pin(
                "the acoustic floor is a DIFFERENT floor from the two the suite already enforced",
                kdocFor("HANGOVER_MIN_MS"),
                "The suite already enforces two other floors and NEITHER of them is this one"
            ),
            Pin(
                "and what going below it actually breaks",
                kdocFor("HANGOVER_MIN_MS"),
                "reaches into inter-word junctures in fast connected speech (100-200 ms) and stop " +
                    "closures (50-150 ms)"
            ),
            Pin(
                "300 ms is agreement with the native filter, not a guess",
                kdocFor("MIN_SPEECH_MS"),
                "The native filter already drops <250 ms before `whisper_full`; 300 keeps client " +
                    "and native agreeing instead of fighting."
            ),
            Pin(
                "the micro-pause floor is the native max-speech split value",
                kdocFor("MICRO_PAUSE_MS"),
                "native `min_silence_samples_at_max_speech`, whisper.cpp:5255"
            ),
            Pin(
                "which frame of the dip is the first to qualify (the C1-review off-by-one)",
                kdocFor("MICRO_PAUSE_MS"),
                "At the 32 ms frame cadence, with the dip clock started at the FIRST " +
                    "sub-[RELEASE_THRESHOLD] frame (as native starts temp_end at that frame's " +
                    "curr_sample), the first qualifying frame is the 5th of the dip: " +
                    "128 ms > 98 ms, while the 4th is only 96 ms old."
            ),
            Pin(
                "the floor's clock domain: wall-clock ms here, samples natively",
                kdocFor("MICRO_PAUSE_MS"),
                "CLOCK DOMAIN: this floor is WALL-CLOCK milliseconds because the endpointer's dip " +
                    "clock is `nowMs`, while native counts SAMPLES"
            ),
            // --- THE FLATLINE CUT: the two sentences that keep its constants from being retuned
            //     as if they were acoustic knobs like the others (4.4) ---
            Pin(
                "the flat floor is safe ONLY because it sits under all room tone",
                kdocFor("FLATLINE_RMS_MAX"),
                "a natural pause in a natural room measures 50-300 RMS and never reaches zero, so " +
                    "a run of flat chunks can only come from an editor's gate or a muted stream"
            ),
            Pin(
                "and why it is not simply 'somewhere under room tone' — only zero transfers",
                kdocFor("FLATLINE_RMS_MAX"),
                "Only digital zero is scale-invariant between a PC wav and post-mixer device capture"
            ),
            Pin(
                "the flat floor's comparison differs from the simulator's by one unit, on purpose",
                kdocFor("FLATLINE_RMS_MAX"),
                "at the single value 10 itself this constant is the simulator's `--flatline-rms 11`."
            ),
            Pin(
                "the hold is a COUNT because the device clock is bursty",
                kdocFor("FLATLINE_CHUNKS"),
                "A count is deterministic on the device in a way a wall-clock hold is not"
            ),
            Pin(
                "five is one more than a stop closure can supply",
                kdocFor("FLATLINE_CHUNKS"),
                "yields at most FOUR fully-flat 32 ms chunks, so five cannot cut a word at a /p/ " +
                    "or a /k/"
            ),
            Pin(
                "and what gap five actually buys, at any phase",
                kdocFor("FLATLINE_CHUNKS"),
                "every gap of 192 ms and up is caught at EVERY phase"
            ),
            Pin(
                "the probe budget is the probe's OWN cost, not the frame period",
                kdocFor("PROBE_BUDGET_MS"),
                "the probe's own cost budget inside the 32 ms frame period, not the frame period " +
                    "itself"
            ),
            Pin(
                "why 8 ms and not a tighter number",
                kdocFor("PROBE_BUDGET_MS"),
                "Generous against the 0.2-1.5 ms the probe is expected to cost, so an overrun " +
                    "means something is really wrong rather than that the estimate was tight."
            ),
            Pin(
                "the cutout is latched for the session, not per frame",
                kdocFor("PROBE_CUTOUT_FRAMES"),
                "Consecutive overruns that latch the probe off for the rest of the session."
            ),
            // --- the scope ruling, in prose (its structural twin is the next test) ---
            Pin(
                "the commit cadence is NOT an acoustic knob and is NOT here",
                commitCadenceNote(),
                "NO COMMIT-INTERVAL CONSTANTS LIVE HERE."
            ),
            Pin(
                "and the object that does own it is named",
                commitCadenceNote(),
                "is owned solely by com.whispereverywhere.service.CommitCadencePolicy"
            ),
            Pin(
                "and the route it takes to the endpointer is named",
                commitCadenceNote(),
                "reaches the endpointer per SESSION via Endpointer.onSessionStart(nowMs, " +
                    "minCommitIntervalMs)"
            ),
            // Build 85: the backpressure governor's SLOW row rides the same call. The pin above
            // still holds verbatim (the fast row's route did not move); this one says the third
            // argument exists and where it comes from, so a reader of this file learns that the
            // floor is no longer ONE number per session.
            Pin(
                "and so is the SLOW row the backpressure governor steps up to (build 85)",
                commitCadenceNote(),
                "the same call carries the SLOW row as its third argument, slowCommitIntervalMs"
            ),
        )

        pins.forEach { (item, scope, sentence) ->
            assertTrue(
                "EndpointerTuning.kt no longer states: \"$sentence\"\n" +
                    "That sentence is the written derivation for: $item.\n" +
                    "A tuning constant without its derivation is a number nobody may safely " +
                    "change: the next person cannot tell an owner-tunable knob from a value " +
                    "copied out of whisper.cpp, and the A/B session that follows re-derives it " +
                    "from scratch or gets it wrong. Restore the sentence, or — if the DECISION " +
                    "changed — change the value, the sentence and this pin together.",
                prose(scope).contains(sentence)
            )
        }
    }

    /**
     * The scope ruling, structurally. The prose pin above says the cadence does not live here; this
     * one makes the statement executable, because prose cannot stop an addition.
     *
     * Two objects both answering "how often may this tier commit" is how the log and the code start
     * disagreeing. The cadence is a per-SESSION function of the installed tier and of whether every
     * commit becomes a provider request — `CommitCadencePolicy` (Task D3) owns it, and hands it to
     * the endpointer at `onSessionStart`.
     */
    @Test fun no_commit_cadence_constant_may_live_in_this_object() {
        // ANY val, not just `const val`: a plain `val MIN_COMMIT_INTERVAL_MS = 1200L` (or a
        // `private val`, or an `internal val`) is the same defect and the narrower regex let it
        // through — proved by mutation, not assumed. `(?:\w+\s+)*` absorbs whatever modifiers
        // precede the keyword; comment lines cannot match, because `//` is not `val`.
        val named = Regex("""(?m)^\s*(?:\w+\s+)*val (\w*(?:COMMIT|INTERVAL|CADENCE)\w*)""")
            .findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(
            "EndpointerTuning declares a commit-cadence constant: $named. It belongs to " +
                "com.whispereverywhere.service.CommitCadencePolicy (Task D3) and NOWHERE else — " +
                "the endpointer receives it per session through onSessionStart(nowMs, " +
                "minCommitIntervalMs), because it depends on the installed tier AND on whether " +
                "every commit becomes a provider request, neither of which is an acoustic knob.",
            emptyList<String>(),
            named
        )

        listOf("\"eco\"", "\"base\"", "\"pro\"", "\"extreme\"", "\"multi\"", "\"ultra\"").forEach { id ->
            assertTrue(
                "EndpointerTuning mentions the tier id $id. A per-tier table in this file is the " +
                    "same defect as a per-tier constant wearing a different name (1200 pro / 6000 " +
                    "multi / 8000 extreme+ultra / 3000 cloud batch live in CommitCadencePolicy): " +
                    "this object holds only what the state machine itself decides with — " +
                    "thresholds, durations, frame geometry, the probe budget.",
                !src.contains(id)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Source-reading helpers. Same shape as NativeVadSourceContractTest's, same reasons.
    // ---------------------------------------------------------------------------------------

    private data class Pin(val item: String, val scope: String, val sentence: String)

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "could not locate $relative from ${System.getProperty("user.dir")}"
        )
    }

    /**
     * Line endings are normalized to LF at the single read site, so no anchor below can be defeated
     * by a CRLF checkout (the N1/N2 lesson: `readText()` does not normalize, and a needle with an
     * embedded "\n" can never match CRLF text).
     */
    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/EndpointerTuning.kt")
            .readText().replace("\r\n", "\n")
    }

    /** The object's own KDoc: the first block in the file, ending at the `object` declaration. */
    private fun classKdoc(): String {
        val at = src.indexOf("object EndpointerTuning")
        assertTrue("EndpointerTuning.kt no longer declares `object EndpointerTuning`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue(
            "no KDoc block opens above `object EndpointerTuning`. lastIndexOf returns -1 when the " +
                "block is gone, so this assert names the real cause — the object's two scope " +
                "rulings (independent of the batch filter; deliberately no smoothing) were " +
                "deleted — instead of failing four sentence pins for what reads like drift.",
            open >= 0
        )
        return head.substring(open)
    }

    /**
     * The KDoc block immediately above one constant — from its opening marker down to the
     * declaration. (Spelling that marker out here is not possible: Kotlin NESTS block comments, so
     * it would open one that never closes.)
     */
    /**
     * THE MERGE-REJECTION BLOCK IS PINNED, because it is the most load-bearing prose in this file
     * and nothing else guards it.
     *
     * It records why a merge memory was designed, implemented, and then removed in review: it made
     * [EndpointerTuning.MIN_SPEECH_MS] unenforceable (the merged run's span is measured across the
     * GAP, which is longer than the hangover by construction) and committed three times on a
     * percussive music bed where the shipped machine commits nothing — inverting the one behaviour
     * the owner protects. Every other pin in this class guards a KDoc attached to a constant;
     * this block guards the ABSENCE of a constant, so `kdocFor` cannot reach it and a deletion
     * would be silent. Three sentences, each carrying one leg of the argument.
     */
    @Test fun the_merge_pass_rejection_keeps_its_reasoning() {
        for (sentence in listOf(
            "IT MADE MIN_SPEECH_MS UNENFORCEABLE",
            "IT INVERTED THE ONE BEHAVIOUR THE OWNER PROTECTS",
            "THE PORT CLAIM WAS FALSE",
        )) {
            assertTrue(
                "EndpointerTuning.kt no longer explains why the merge pass was rejected " +
                    "(missing: \"$sentence\"). That block is the only thing standing between the " +
                    "next hangover A/B and a re-derivation of the change that commits on " +
                    "background music — if it is being deleted, the deletion needs its own " +
                    "argument, not a green suite.",
                src.contains(sentence),
            )
        }
        assertTrue(
            "and the cost of NOT having the merge must stay recorded beside it, or the gap " +
                "becomes folklore and the next reader treats it as an oversight",
            src.contains("THE COST OF NOT HAVING IT, recorded honestly"),
        )
    }

    private fun kdocFor(constant: String): String {
        val decl = "const val $constant"
        val at = src.indexOf(decl)
        assertTrue("EndpointerTuning.kt no longer declares `$decl`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above `$decl`", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for `$decl` widened past a previous member: lastIndexOf finds the " +
                "NEAREST block above the declaration, so deleting THIS constant's KDoc outright " +
                "silently borrows the previous constant's (or the object's) — and a sentence pin " +
                "could then be satisfied by a derivation written about a different number.",
            block.lineSequence().none {
                val t = it.trimStart()
                t.startsWith("const val") || t.startsWith("object ")
            }
        )
        return block
    }

    /** The trailing `//` note that states the scope ruling: everything after the last constant. */
    private fun commitCadenceNote(): String {
        val last = src.lastIndexOf("const val ")
        assertTrue("EndpointerTuning.kt declares no constants at all", last >= 0)
        val tail = src.substring(last)
        assertTrue(
            "no comment follows the last constant in EndpointerTuning.kt. The scope ruling — that " +
                "the per-tier commit cadence is CommitCadencePolicy's and never this object's — " +
                "is stated there; this assert names its deletion instead of failing three " +
                "sentence pins against the constant's own KDoc.",
            tail.lineSequence().any { it.trimStart().startsWith("//") }
        )
        return tail
    }

    /**
     * KDoc/comment prose as a single normalized line: the leading decoration is stripped and runs
     * of whitespace collapse, so a pin can anchor on a WHOLE SENTENCE without being defeated by
     * wherever the 100-column limit happened to wrap it.
     */
    private fun prose(scope: String): String =
        scope.lineSequence()
            .map { line ->
                var t = line.trim()
                if (t.startsWith("/**")) t = t.removePrefix("/**")
                if (t.startsWith("//")) t = t.removePrefix("//")
                if (t.endsWith("*/")) t = t.removeSuffix("*/")
                if (t.startsWith("*")) t = t.removePrefix("*")
                t.trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
}
