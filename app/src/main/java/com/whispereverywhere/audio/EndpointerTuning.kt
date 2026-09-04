package com.whispereverywhere.audio

/**
 * Every knob the 3.7 Silero endpointer turns, in ONE object, JVM-pinned by EndpointerTuningTest.
 *
 * Deliberately SEPARATE from the batch VAD filter's tuning (`we_vad_filter`,
 * `whisper_jni.cpp:191-192` — threshold 0.40 / speech_pad 150 ms), which is untouched: the
 * streaming probe decides WHEN to cut an utterance, the batch filter decides WHAT audio inside
 * that commit reaches the encoder. Independent knobs on independent jobs — the batch filter's 0.40
 * buys onset headroom that `suppress_nst` absorbs at the token layer, and endpointing has no token
 * layer.
 *
 * There is deliberately NO smoothing/EMA constant. The reference implementation does not smooth
 * (`whisper_vad_segments_from_probs`, `whisper.cpp:5217-5451`): the Schmitt trigger, the minimum
 * speech duration and the hangover already low-pass the sequence. An EMA would add lag and a second
 * thing to tune.
 */
object EndpointerTuning {

    /** Silero's window: 512 samples of 16 kHz mono is exactly one probe frame (model n_window). */
    const val FRAME_SAMPLES = 512

    /**
     * 512 samples x 2 bytes (PCM16). `vadProbeFrame` returns [NO_VERDICT] for any other size.
     *
     * SINGLE OWNER: this object owns the JVM side of the native frame contract.
     * `VadProbe.FRAME_BYTES` (Task D4) is an alias of this constant, not a second literal —
     * `EndpointerFactory` sizes its direct buffer from one and fills it from the other, so a
     * divergence would be a `BufferOverflowException` on the capture thread rather than a doc
     * inconsistency.
     */
    const val FRAME_BYTES = 1024

    /**
     * 512 / 16 000 s. One mic callback nominally delivers this much audio; `read()` may return
     * short, so callers accumulate to exact [FRAME_BYTES] boundaries.
     */
    const val FRAME_MS = 32L

    /**
     * "No verdict" from the native probe — NEVER "silence". A short frame zero-padded into the
     * model still advances the LSTM and poisons the recurrence, so the native side refuses and the
     * client keeps the previous state.
     *
     * SINGLE OWNER, as for [FRAME_BYTES]: `VadProbe.NO_VERDICT` (Task D4) aliases this. Two
     * independent `-1.0f` literals in one package would let a future edit turn the native sentinel
     * into a legitimate probability on one side of the seam only.
     */
    const val NO_VERDICT = -1.0f

    /**
     * Native default (`whisper_vad_default_params`, whisper.cpp:4454). A frame at or above this
     * opens/holds the gate.
     */
    const val ONSET_THRESHOLD = 0.50f

    /**
     * Schmitt hysteresis, native `neg_threshold = threshold - 0.15f` (whisper.cpp:5258). This is
     * the exact mechanism whose absence causes today's 251-499 RMS dead band (the KNOWN LIMITATION
     * block at `com.whispereverywhere.util.SpeechSegmenter:22-30`: a room whose noise floor sits
     * between the two amplitude thresholds opens a segment that can never close). Widen to 0.30 if
     * mid-word splits appear in A/B.
     */
    const val RELEASE_THRESHOLD = 0.35f

    /**
     * Trailing silence that ends an utterance. NOT the native 100 ms (`whisper.cpp:4456`), which is
     * a file-segmentation value with a 200 ms merge pass behind it (`whisper.cpp:5359`).
     *
     * RETUNED 500 —> 350 on a device measurement, and the derivation changed with the number.
     * 3.7 argued from a SYMMETRIC cost: cutting early bought one extra full encoder pass, so 500
     * bought margin cheaply. 57 shipped-build segments on the Fold6 falsified the premise on the
     * tier the app now ships: the npu/npu-turbo encoder input is a fixed `[1,melBins,3000]`
     * 30-second window (`whisper_jni.cpp:661-673`), so a pass costs the same ~1.78 s whether it
     * carries one second of speech or fifteen, and the pipeline measured IDLE 61-75% of the time
     * with a 15 s wall cap firing in every run because this hangover never elapsed. An extra
     * encoder pass is a DUTY-CYCLE cost, and duty cycle is
     * `CommitCadencePolicy.minCommitIntervalMs`'s job — not this constant's.
     *
     * What this constant still decides, on every tier, is the cost no governor can pay back:
     * inter-clause pauses run 200-500 ms, and a mid-clause boundary is one `no_context = true`
     * makes unrepairable. 350 clears word junctures and stop closures (50-200 ms) with margin and
     * intrudes only on the bottom of the inter-clause band; below ~200 ms it would reach inside
     * words. Also feeds the batch filter's `speech_pad_ms = 150`, which needs trailing audio to
     * expand into. At 350 a commit still carries 352 ms of it. Owner A/B range 350-800.
     */
    const val HANGOVER_MS = 350L

    /**
     * The ACOUSTIC floor under [HANGOVER_MS] — the value below which the hangover stops ending
     * utterances and starts cutting inside them.
     *
     * The suite already enforces two other floors and NEITHER of them is this one: the machine
     * floor ([MICRO_PAUSE_MS] = 98, below which the cost governor's merge branch silently stops
     * offering the wall cap a cut point) and the batch-pad floor (a commit's trailing silence must
     * outlast `speech_pad_ms = 150`). Both are satisfied at 250 ms, and 250 ms reaches into
     * inter-word junctures in fast connected speech (100-200 ms) and stop closures (50-150 ms).
     * `no_context = true` (`whisper_jni.cpp:846`) plus `commit()`'s buffer reset makes a split
     * there unrepairable in both directions, so the failure is a permanently mangled word rather
     * than a latency regression.
     *
     * Enforced by `EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover` and nowhere
     * else. Without a NAMED floor an A/B downward fails as four unrelated-looking micro-pause
     * fixtures instead of with the reason attached.
     */
    const val HANGOVER_MIN_MS = 300L

    // THE MERGE PASS IS NOT HERE, AND THAT IS A DECISION (4.4 review, 2026-09-02).
    //
    // A draft of this retune added REOPEN_GAP_MS = HANGOVER_MS * 2 plus a field in
    // SileroEndpointer so a burst discarded under MIN_SPEECH_MS was remembered and the next onset
    // re-opened the utterance from it — sold as a port of whisper.cpp's merge pass
    // (`:5607-5640`). Adversarial review killed it, and the reason is worth keeping so nobody
    // re-derives it:
    //
    //  1. IT MADE MIN_SPEECH_MS UNENFORCEABLE. A re-open is reachable only after a discard, and a
    //     discard only past HANGOVER_MS of dip, so the merged run's `speechMs` spans the GAP and
    //     is > HANGOVER_MS >= MIN_SPEECH_MS by construction. The discard branch became
    //     unreachable after a merge. Measured on the draft: two single 32 ms frames 416 ms apart
    //     committed one segment out of 64 ms of actual speech.
    //  2. IT INVERTED THE ONE BEHAVIOUR THE OWNER PROTECTS. A percussive bed (16 x a 288 ms hit
    //     over a 416 ms gap) committed 3 times in 11.3 s where the shipped machine commits 0 in
    //     15.9 s. The owner's rule is that background music should ride the 15 s wall cap —
    //     "that means there's more background noise and the app just doesn't want to miss the
    //     audio. That's perfect."
    //  3. THE PORT CLAIM WAS FALSE. Native drops a sub-min_speech burst BEFORE it can enter
    //     `speeches` (`whisper.cpp:5590`), so its merge can only glue segments that already
    //     passed the floor; it never resurrects a discarded one. Native's merge gap is a
    //     hardcoded 200 ms against a 250 ms min_speech, preserving `gap < min_speech` — the
    //     invariant that stops a silence gap clearing the floor by itself. `HANGOVER_MS * 2`
    //     against MIN_SPEECH_MS inverts it, and while HANGOVER_MS > MIN_SPEECH_MS no value of
    //     the gap can restore it.
    //
    // THE COST OF NOT HAVING IT, recorded honestly: emphatic word-by-word delivery ("It. Is. Not.
    // That. Simple.") still commits nothing until the wall cap, because each word is discarded and
    // `closeGate()` re-anchors. That is the SHIPPED 3.7 behaviour, not a regression this retune
    // introduces — but it is the one thing a smaller hangover does not fix, and it is the question
    // to take to a device session. A correct fix would make MIN_SPEECH_MS a floor on ACCUMULATED
    // speech rather than on wall-clock span, which needs its own decision about whether two
    // 288 ms drum hits should merge when two 288 ms words should.


    /**
     * Shortest run of speech that may be committed. The native filter already drops <250 ms before
     * `whisper_full`; 300 keeps client and native agreeing instead of fighting.
     * (`min_speech_duration_ms = 250`, `whisper.cpp:4455`.)
     *
     * It is a floor on EACH RUN of speech, measured from the onset that opened the gate — there is
     * no merge across a dip (see the block above [HANGOVER_MS]), so a burst shorter than this is
     * discarded outright and its audio waits for the wall cap.
     */
    const val MIN_SPEECH_MS = 300L

    /**
     * THE SPEECH-EVIDENCE FLOOR (4.3.2): the least Silero evidence a pending buffer must carry for
     * the local engine to ENCODE it. `SileroEndpointer` counts the frames the probe scored at or
     * above [ONSET_THRESHOLD] since the buffer was last committed; the commit funnel hands that
     * total to `LocalWhisperEngine.commit`, which resolves a buffer under this floor
     * `EmptyExpected` without touching the backend. 256 ms is eight 32 ms frames of p >= 0.50.
     *
     * Below native's 250 ms batch floor IN SPIRIT, not in kind: `we_vad_filter`'s
     * `min_speech_duration_ms = 250` (`whisper_jni.cpp` ~:815, `whisper.cpp:4455`) is a floor on
     * one RUN of speech at threshold 0.40; this is a floor on the TOTAL of onset frames at 0.50
     * over the whole buffer, dips included. Chosen so a lone quiet "yes" (~300 ms of speech, nine
     * or ten onset frames) passes and a 15 s bed where Silero flickered over ONSET for six frames
     * does not. It is at most [MIN_SPEECH_MS] (pinned): a VAD cut that passed the 300 ms span
     * floor carries the onset frames that opened and held the gate, so in the normal case a real
     * utterance is never skippable on evidence — the 9-frame-at-0.9 burst that commits is also
     * encoded (`SileroEndpointerEvidenceTest`).
     *
     * HONEST LIMIT: this gates the ENCODE on what the endpointer HEARD. A music bed that Silero
     * scores as SPEECH for seconds carries evidence and is not caught here; the no-speech gate and
     * the stock-phrase blocklist (`NpuDecodePolicy`, `HallucinationPolicy`) remain the defence
     * there. What this catches is silence, breath, room tone, a fan, a paused video — the owner's
     * report. And it is a floor on the ENCODE alone: no cut decision reads it. The merge memory the
     * 4.4 review rejected was a bank that fed the CUT (see the block above [HANGOVER_MS]); this
     * number is read at the commit funnel and nowhere inside the state machine.
     *
     * Consumers: `LocalWhisperEngine.commit` (the only decision), `SileroEndpointer` (the count)
     * and the simulator's `Tuning.min_evidence_ms` (`tools/vadsim`, `--min-evidence-ms`).
     */
    const val MIN_SPEECH_EVIDENCE_MS = 256L

    /**
     * A dip below [RELEASE_THRESHOLD] lasting longer than this is remembered as a cut point for the
     * wall-cap path (native `min_silence_samples_at_max_speech`, whisper.cpp:5255, compared
     * strictly at `whisper.cpp:5328`). At the 32 ms frame cadence, with the dip clock started at
     * the FIRST sub-[RELEASE_THRESHOLD] frame (as native starts temp_end at that frame's
     * curr_sample), the first qualifying frame is the 5th of the dip: 128 ms > 98 ms, while the 4th
     * is only 96 ms old.
     *
     * CLOCK DOMAIN: this floor is WALL-CLOCK milliseconds because the endpointer's dip clock is
     * `nowMs`, while native counts SAMPLES (`sample_rate * 98 / 1000` = 1568 at 16 kHz) — the same
     * floor in different units, never comparable without converting.
     */
    const val MICRO_PAUSE_MS = 98L

    /**
     * THE FLATLINE CUT's amplitude floor (4.4): a capture chunk whose RMS is AT OR BELOW this is
     * "flat". In `AudioMath.amplitude`'s own 0..32767 units (`AudioMath.kt:21-36`; the
     * truncate-and-clamp is `:36`), measured once per 32 ms capture chunk on both paths
     * (`StreamingAudioRecorder.kt:87`, `PlaybackAudioCapturer.kt:81` — on the DECIMATED 16 kHz
     * buffer, so the scale is the microphone's).
     *
     * Ten is digital zero plus decoder/dither noise, an order of magnitude under room tone: a
     * natural pause in a natural room measures 50-300 RMS and never reaches zero, so a run of flat
     * chunks can only come from an editor's gate or a muted stream — the thing this trigger exists
     * to see. Only digital zero is scale-invariant between a PC wav and post-mixer device capture
     * (device audio is the platform's loopback mix with the media volume applied and, on YouTube,
     * per-video loudness normalisation on top), which is why the floor sits just above zero rather
     * than anywhere inside the room-tone band: a value that depends on the scale of NON-zero audio
     * does not transfer from the desk to the phone. Reference twin: `tools/vadsim`'s
     * `Tuning.flatline_rms` (`machine.py`, the `flatline_rms` field and its docstring), swept at
     * {10, 20, 40, 80, 160} with 10/20/40 the only values under the whole room-tone band.
     *
     * COMPARISON: this is a MAX — `amp <= FLATLINE_RMS_MAX` is flat — where the simulator's
     * predicate is a strict `rms < flatline_rms`. The two agree on every fixture trace (0, 100 and
     * 3000 RMS); at the single value 10 itself this constant is the simulator's `--flatline-rms 11`.
     */
    const val FLATLINE_RMS_MAX = 10

    /**
     * THE FLATLINE CUT's hold, as a COUNT of consecutive flat chunks (4.4). The trigger fires ON
     * the fifth flat chunk — the run's ages are 0, 32, 64, 96, 128 ms, so five chunks is the
     * simulator's `flatline_hold_ms = 128` measured the way the hangover is measured (`machine.py`
     * DECISION 5), and 160 ms of flat audio has passed through the endpointer when it fires.
     *
     * A COUNT and not a wall-clock hold, deliberately: the phone stamps every frame of a chunk
     * with one `System.currentTimeMillis()` at delivery, which is bursty, so `nowMs - runStart >=
     * 128` with 128 an exact multiple of 32 sits on a band edge and would fire on the 4th or the
     * 6th flat chunk as often as on the 5th. A count is deterministic on the device in a way a
     * wall-clock hold is not (`machine.py`, `Tuning.flatline_fire_chunks` and DECISION 5).
     *
     * Why five. A stop closure inside a word — the plosive's 50-150 ms of near-silence, which
     * Silero keeps calling speech — yields at most FOUR fully-flat 32 ms chunks, so five cannot cut
     * a word at a /p/ or a /k/. An editor's gap of digital silence supplies five WHOLE flat chunks
     * only when it is long enough at the phase it happens to land on the chunk grid: 160 ms only
     * when chunk-aligned (a 16 ms shift leaves four — one millisecond of 3000-RMS speech in a chunk
     * already reads over 500), while every gap of 192 ms and up is caught at EVERY phase
     * (`machine.py`, `Tuning.flatline_gap_aligned_ms` / `flatline_gap_any_ms`, and
     * `tests/test_flatline_verify.py`'s alignment section). Against the "100-300 ms an editor
     * leaves", five is certain for the upper half of that band and catches the lower half by phase.
     */
    const val FLATLINE_CHUNKS = 5

    /**
     * A probe frame slower than this is an overrun: the probe's own cost budget inside the 32 ms
     * frame period, not the frame period itself. Nothing measures against THIS spelling — the
     * comparison is made in microseconds, against [PROBE_BUDGET_US] below.
     *
     * Generous against the 0.2-1.5 ms the probe is expected to cost, so an overrun means something
     * is really wrong rather than that the estimate was tight.
     */
    const val PROBE_BUDGET_MS = 8L

    /**
     * [PROBE_BUDGET_MS] in the unit the endpointer actually measures. **ONE conversion for the
     * whole seam**, and that is the entire reason it exists rather than being spelled
     * `PROBE_BUDGET_MS * 1_000L` at each site.
     *
     * There are three consumers and they must agree exactly: `SileroEndpointer`'s own cutout latch,
     * the `ProbeStats` it constructs by default, and the `ProbeStats` `EndpointerFactory` (Task D8)
     * passes in. Task C10 retuned the latch from truncated milliseconds to microseconds precisely so
     * that the latch and the `probe:` line could not hold two opinions about what an overrun is; a
     * second conversion site is how that agreement would be lost again, silently, in a commit that
     * looked like a tidy-up. `ProbeStats` keeps its budget PRIVATE, so no `require` can catch a
     * mismatch at runtime — this constant is the only enforcement there is.
     */
    const val PROBE_BUDGET_US = PROBE_BUDGET_MS * 1_000L

    /** Consecutive overruns that latch the probe off for the rest of the session. */
    const val PROBE_CUTOUT_FRAMES = 16

    // NO COMMIT-INTERVAL CONSTANTS LIVE HERE. The per-tier cost governor
    // (1200 eco+base+npu / 2000 npu-turbo by owner ruling / 6000 pro+multi / 8000 extreme+ultra /
    // 3000 cloud batch) is owned solely by
    // com.whispereverywhere.service.CommitCadencePolicy, and reaches the endpointer per SESSION via
    // Endpointer.onSessionStart(nowMs, minCommitIntervalMs) — it depends on the installed tier AND
    // on whether every commit becomes a provider request, neither of which is an acoustic knob.
    // Since build 85 the same call carries the SLOW row as its third argument, slowCommitIntervalMs
    // — THE BACKPRESSURE GOVERNOR's floor once the segment queue reaches two (3200 npu-turbo; equal
    // to the fast floor on every other tier, so inert there) — and the two depth thresholds it steps
    // on are that object's BACKPRESSURE_ENTER_DEPTH / BACKPRESSURE_LEAVE_DEPTH, re-exported from
    // BackpressureRule in this package. None of those is an acoustic knob either.
}
