package com.whispereverywhere.service

import com.whispereverywhere.audio.Endpointer

/**
 * The 3.7 commit-cadence governor (Workstream D3) — ONE policy object. The multi, npu and
 * npu-turbo rows are MEASURED on the Fold6 at vc77 with the 512 audio_ctx floor and production
 * backends; eco/base carry a PRE-512 bench and extreme/ultra are unmeasured (both below).
 *
 * A real endpointer cuts at real pauses; what it must NOT do is cut faster than the tier can pay
 * for. The arithmetic is `F*N + m*S <= 0.70 * 60 s` per minute of session, where F is the fixed
 * per-commit cost, m the steady rtf and S the speech seconds (conserved — same speech, same
 * tokens, only the number of encoder passes changes).
 *
 * - pro (moved in 4.4): the 0.77-1.0 s that once bought it the FAST row is a GPU figure, and
 *   pro is CPU-ONLY by owner ruling — the GPU path is dead because it was slower. On the CPU it
 *   IS multi (the same whisper-small encoder, 13 KB apart), so it takes multi's row.
 * - multi: F = 2.3 s (CPU) -> N <= ~10.7 commits/min -> a 6 s floor. Predictable ~2.8 s
 *   speech-end-to-text at the paced boundary, and no 15 s walls.
 * - npu (4.0): the same small weights as multi, but the encoder runs on the Hexagon — ~0.4 s
 *   sustained in the spike, so it pays for the FAST row. See [minCommitIntervalMs].
 * - npu-turbo (4.1, retuned 4.4, RULED 2026-09-03): large-v3-turbo on the same Hexagon. The 4.1
 *   row was provisional on published figures; the Fold6 measurement came in at F = 1.89 s fixed,
 *   so turbo left the FAST row. Its own constant, [MIN_COMMIT_INTERVAL_TURBO_MS] = 2 000 ms, is
 *   the ONE row in this table that the 0.70 rule below does not clear, and it says so: the owner
 *   ruled that one sentence per chunk outranks the duty margin on turbo. See that constant.
 *
 * THE ELIGIBILITY RULE FOR THIS TABLE, written down in 4.4 because the hangover retune is what
 * made it load-bearing: a tier keeps a floor only while its full-segment F is MEASURED and
 * `F/floor + m <= 0.70` at saturation. Three rows do not meet it, for three different reasons.
 * npu-turbo is OVERRIDDEN by an owner ruling with the arithmetic stated (see its constant).
 * `pro` failed it outright and
 * moved (see [minCommitIntervalMs]); eco/base keep 1 200 ms on evidence that is thin, and the
 * honest reading is worse than "thin": they have not been re-benched since the audio_ctx floor
 * moved 768 -> 512 on 2026-08-20, and the only slice bench in the repo
 * (docs/measurements/2026-07-28-whisper-stt-bench-fold6.log) predates that move and therefore
 * OVERSTATES today's cost — but THE CHECK FAILS EVEN SO. That bench fits base to F = 1.15 s and
 * eco to F = 0.74 s, i.e. `F/floor + m` of ~1.0 and ~0.83 at the 1 200 ms floor, both over the
 * 0.70 this same rule states. Granting the full audio_ctx discount lands base at ~0.69 — zero
 * margin, which is the condition [MIN_COMMIT_INTERVAL_TURBO_MS] refuses to ship on. So this row
 * is EXEMPTED on an unquantified discount, not cleared, and overstating is only the safe
 * direction when the overstated check PASSES. The re-bench is what settles it. Scoped out of 4.4
 * deliberately: these are retired-not-uninstalled tiers on the weakest devices, the failure mode
 * is queue latency rather than corruption, and a floor moved without a measurement is the same
 * mistake this rule exists to name.
 * - extreme/ultra (539-574 MB): UNMEASURED. 8 s is the conservative placeholder; H2 may revise it.
 * - cloud batch: every commit is one HTTP POST (Semaphore(3) in flight, shed at 24). Same
 *   reasoning that made the 4 s first cap LOCAL-only.
 *
 * Pure and Context-free so every number is JVM-pinned (CommitCadencePolicyTest).
 */
object CommitCadencePolicy {

    /**
     * eco / base / npu. npu is the strongest of the three and still not a clearance under the
     * object KDoc's rule: its ~405 ms sustained figure is a spike-measured ENCODER pass, not a
     * measured FULL-SEGMENT F, so the derived F ~= 0.5 s (F/floor = 0.42, clears with room) is
     * PROVISIONAL until Q10a — which is what the tier map below already says. eco/base sit here by EXEMPTION, not by that rule — the
     * object KDoc gives the arithmetic and says why the row is not cleared. Do not restate the
     * old "any tier whose F is at or under ~1.2 s" membership test: F/floor = 1.0 is exactly what
     * the 0.70 rule refuses, so the two cannot both govern.
     */
    const val MIN_COMMIT_INTERVAL_FAST_MS = 1_200L

    /**
     * npu-turbo, and turbo alone. **2 000 ms is an OWNER RULING (2026-09-03), not a duty
     * derivation, and this KDoc exists to keep the two from being confused.**
     *
     * THE ARITHMETIC, stated where the number is. Work per commit on turbo is ~2.05 s and 87 % of
     * it is the encode, which is FIXED (the QNN mel window is 30 s whatever the utterance holds).
     * The governor's merge branch (`SileroEndpointer.onProb`) declines any endpoint that arrives
     * within this floor of the last COMMIT, so the visible commit interval is
     * `ceil(floor / T) * T` for a sentence period T (speech + pause), and every chunk holds
     * `ceil(floor / T)` sentences. That is the whole trade:
     *  - at 3 200 (the value the 4.4 retune shipped for one day, and the value the pre-upload
     *    review recommended on the 0.70 rule): saturated duty 62 %, and every sentence period under
     *    3.2 s arrives as a TWO-sentence chunk. For the owner's target — one sentence English,
     *    one sentence Spanish, ~2 s each — that is 100 % bilingual chunks, one sentence of each
     *    pair decoded under the other's language token, and the per-chunk language label the 25 s
     *    finalizer needs as its boundary signal is one token for two languages: the boundary is
     *    LOST before any language logic sees it. A regression against 4.3.0's 500 / 1 200 rows,
     *    which gave one sentence per chunk in the >= 544 ms pause band at a modelled 60-82 % duty
     *    the field ran for two days and called perfect.
     *  - at 2 000: one sentence per chunk for every period >= 2.0 s (and the hangover needs a
     *    384 ms pause to fire at all, so shorter periods barely exist). Saturated duty at the
     *    measured F: 30 commits/min x 1 890 + 1 900 = 58.6 s/min = 98 % — OVER the 0.70 rule, by
     *    ruling, and under 100 %, so the queue is bounded in expectation and drains on any pause
     *    longer than the period. At a throttled F of 2 140 it is 110 %: sustained staccato speech
     *    on a hot phone GROWS the queue. On the measured target content (a multi-language video,
     *    57 segments, mean intervals 5.4-9.6 s) the real duty was 25-39 %; the saturated figure is
     *    a stress case, not the use case.
     *  - at 1 200 (4.3.0's FAST row): 170 % at saturation. Refused; the hangover at 500 used to
     *    protect that row by never producing cuts that fast, and at 350 it no longer does.
     *
     * THE GUARD, until the real one lands: the in-flight strip's "(3+ in queue)" label is the only
     * field signal that this floor is losing; the engine queue never sheds. THE REAL GUARD is a
     * backpressure governor — this floor while the segment queue is <= 1 deep, a slow floor
     * (~3 200-3 900) once it reaches 2 — which makes the duty margin adaptive instead of a static
     * constant that either pairs sentences or admits saturation. It is the next task after 4.3.1
     * ships, and the 25 s finalizer (another ~11 % of duty on turbo) must not land without it.
     *
     * IF F EVER DOES BREACH, THIS FLOOR IS THE NUMBER TO RAISE, not the hangover: the hangover
     * decides whether a boundary exists at a place a listener would agree with, and no duty
     * problem is solved by putting boundaries in worse places. The proportionate raise is 3 200
     * (bounded duty, sentence pairs), recorded above so nobody re-derives it.
     */
    const val MIN_COMMIT_INTERVAL_TURBO_MS = 2_000L

    /** multi: derived from F = 2.3 s at a 0.70 duty ceiling. */
    const val MIN_COMMIT_INTERVAL_MULTI_MS = 6_000L

    /**
     * extreme / ultra, and any tier this build does not recognise: assume the expensive end.
     *
     * This row stays live configuration after Workstream H retires those tiers from the CHOOSER:
     * retirement hides a tier from fresh installs and changes nothing for the users who already
     * have one, and those are exactly the users this number paces.
     *
     * It is also the value [Endpointer.onSessionStart]'s KDoc obliges an endpointer to assume
     * BEFORE it has been given a session — `SileroEndpointer` spells that default as a literal,
     * because Workstream C compiles without this package. The two are joined behaviourally by
     * `CommitCadencePolicyTest.theEndpointersPreSessionFloorIsThisObjectsLargeInterval`, which
     * drives a never-started endpointer to its merge/commit boundary and reads THIS constant as
     * the expected value. Changing this number without changing that literal fails it.
     */
    const val MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L

    /**
     * cloud batch: the provider-request floor, orthogonal to the local tier.
     *
     * 4.4 NOTE — this number did not change and its MEANING did. It paces BILLABLE requests, and
     * until the hangover retune it had never bound: endpoints arrived 5-10 s apart, far outside
     * 3 s. At 350 ms it binds, so the same audio can produce meaningfully more POSTs per minute
     * (and reach `CloudTranscriptionEngine`'s shed at `maxBacklog` = 24 sooner on a degraded
     * link, where each shed segment resolves into the local rescue engine at THIS floor rather
     * than at its own tier's). It is left at 3 000 because it is a request-rate decision the owner
     * owns, not a duty-cycle one — but it is now a live one.
     */
    const val MIN_COMMIT_INTERVAL_CLOUD_MS = 3_000L

    /**
     * The oldest micro-pause the wall cap will still cut at. An offer older than this is not the
     * boundary near where the cap fired — taking it would defer most of the window into the next
     * one and push the effective wall bound from 15 s to ~28 s. Owner-tunable knob.
     */
    const val CAP_CUT_MAX_RETAIN_MS = 3_000L

    /**
     * The minimum interval between endpoint-driven commits for this session.
     *
     * **Cloud batch wins outright — a FLAT 3 000 for every tier**, exactly as the spec's tuning
     * table lists it. In a cloud-batch session the cloud engine is the primary transcriber and the
     * local mirror only runs on a rescue, so pacing the whole session at the local tier's floor
     * would slow the engine doing the work in order to protect one that usually does none; the
     * cost of the failure path is bounded by the drain reserve, not by this interval. (Owner
     * acceptance watches the other side of that trade: the multi-tier cloud sessions'
     * `finalize-timing: local-drain` in the Task S3 sheet is the evidence that would reopen it.)
     *
     * [tierId] is `WhisperModel.id`; null/unrecognised assumes the expensive end. The app cannot
     * reach a recording session without an installed model, so that branch is defensive only.
     */
    fun minCommitIntervalMs(tierId: String?, isCloudBatch: Boolean): Long {
        if (isCloudBatch) return MIN_COMMIT_INTERVAL_CLOUD_MS
        return when (tierId) {
            // npu rides the FAST row, not multi's 6 s, even though it is the same whisper-small
            // weights: the work moves to the Hexagon, where the spike measured the encoder at
            // ~405 ms sustained (1007 ms unvoted — a power-saver floor, not slow silicon) against
            // multi's 2.3 s fixed cost, and the decode is bounded at 196 tokens. Pacing a 0.4 s
            // encoder at a 6 s floor would discard the entire reason the tier exists. Provisional
            // on ONE spike-measured encoder pass; Q10a is the first full-tier device measurement.
            //
            // npu-turbo joined this row in 4.1 on published figures and LEFT it in 4.4 on the
            // device measurement that row's own comment demanded. It has its own constant now,
            // and that constant is an OWNER RULING over the 0.70 rule, with the arithmetic in its
            // KDoc: 2 000 ms buys one sentence per chunk at ~98 % saturated duty; 3 200 would have
            // bought 62 % duty at the price of two-sentence (bilingual) chunks. Widening THIS row
            // to 2 000 would wrongly slow eco/base/npu. See [MIN_COMMIT_INTERVAL_TURBO_MS].
            "eco", "base", "npu" -> MIN_COMMIT_INTERVAL_FAST_MS
            "npu-turbo" -> MIN_COMMIT_INTERVAL_TURBO_MS
            // `pro` LEFT the FAST row in 4.4, on evidence this repo already contained. It is
            // ggml-small.en-q5_1 (190 098 681 bytes); `multi` is ggml-small-q5_1 (190 085 487) —
            // the same whisper-small encoder, 13 KB apart, differing only in the vocab head. The
            // 0.77-1.0 s that bought pro the FAST row is the GPU figure; the MEASURED cost of
            // those same weights WITHOUT the GPU is multi's F = 2.3 s, whose duty-safe floor is
            // this 6 s. And the GPU is not most devices': `GpuPolicy.ALLOWED_RENDERERS` is
            // `Adreno (TM) (7dd|8dd|Xd)`, so every Mali, PowerVR, Xclipse and pre-7xx Adreno runs
            // pro on `n_threads = 4` — and the CHOOSER cannot see any of that (pro's eligibility
            // is RAM-only), so pro is offered to, and installable on, the whole fleet, including
            // exactly the devices the NPU gate declines.
            //
            // At HANGOVER_MS = 500 this was inert: cuts arrived 6-11/min against pro-on-CPU's
            // 26/min service rate, so the floor was unreachable and its wrongness never surfaced.
            // The 4.4 retune is what makes it reachable, which is why it is corrected here rather
            // than left for the field.
            //
            // THIS ROW IS UNCONDITIONAL, AND THAT IS AN OWNER RULING (2026-09-02), not a
            // conservative default. `pro` is CPU-ONLY BY DESIGN: "that model does not run on GPU.
            // It's supposed to only run on CPU because GPU was much slower. So the GPU path is
            // essentially dead." So there is no Adreno-7xx/8xx user losing cadence here — there is
            // no GPU pro path to lose it on. Do NOT "refine" this by routing on
            // `GpuPolicy.decideUseGpuForLoad` (`TranscriptionEngine.kt:257`): that would add an
            // accessor, a second row and a test matrix to distinguish a case the product does not
            // have. `pro` IS `multi` for cost purposes, and the two share this row for the reason
            // the byte counts above give.
            "pro", "multi" -> MIN_COMMIT_INTERVAL_MULTI_MS
            "extreme", "ultra" -> MIN_COMMIT_INTERVAL_LARGE_MS
            else -> MIN_COMMIT_INTERVAL_LARGE_MS
        }
    }

    /**
     * How many trailing milliseconds the wall-cap commit should RETAIN, given the endpointer's
     * remembered micro-pause [cutPointMs] (wall clock) at cap time [nowMs].
     *
     * Returns 0 — i.e. "commit everything, exactly as 3.6.0 did" — for no offer, a
     * future/equal offer, and a stale offer. That zero is what makes the cap path byte-identical
     * whenever the endpointer never fired, which is the untouchable this function must not break.
     */
    fun capCutRetainMs(nowMs: Long, cutPointMs: Long): Long {
        if (cutPointMs <= Endpointer.NO_CUT_POINT) return 0L
        val retain = nowMs - cutPointMs
        if (retain <= 0L) return 0L
        return if (retain > CAP_CUT_MAX_RETAIN_MS) 0L else retain
    }
}
