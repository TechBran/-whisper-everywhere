package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 4.2.0 build still carrying versionCode 80 is rejected only AFTER the upload,
 * and a 4.2.0 build still NAMED "4.1.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * **versionCode 82 — the plain successor to 81.** 81 was consumed by the first internal-track
 * upload (the fleet-onboarding build the owner installed and validated on 2026-08-30), so it is
 * spent: Play will refuse a second upload at the same code. 4.3 takes the next integer, and its
 * NAME moves with it — the trunk now carries the one-tier-per-device lineup, and a build named for
 * the release before the one it contains is the exact silent mismatch this test exists to catch.
 *
 * **versionCode 83 — the plain successor to 82.** 82 went to PRODUCTION on 2026-08-30 as 4.3.0,
 * so it is spent twice over: Play refuses a second upload at the same code, and every installed
 * phone already carries it. 4.3.1 is a patch (three field reports, no new surface), so the NAME
 * moves by one in the last place and the code by one integer; 83 > 82 is what lets the track
 * install replace the production build on the owner's own phone.
 *
 * **versionCode 84 — the plain successor to 83.** 83 went to the INTERNAL TRACK on 2026-09-03
 * (the owner installed it and confirmed the 350 ms hangover "is doing a better job") and was never
 * promoted, so it is spent on the track exactly as 81 was: Play refuses a second upload at the
 * same code. 84 supersedes it there carrying the flatline cut. The NAME stays 4.3.1: a name is
 * spent by a RELEASE, and 4.3.1 has not been released — 83 and 84 are two candidates for the same
 * one. 84 > 83 is what lets the track install replace 83 on the owner's phone. Every bump re-arms
 * GpuPolicy's canary latches (below); still by design, still inert with the GPU toggle off.
 *
 * **versionCode 85 — the plain successor to 84.** 84 went to PRODUCTION on 2026-09-03 (the flatline
 * cut; E8 passed on the owner's phone), so it is spent twice over, as 82 was. 85 carries the
 * backpressure governor, the detect-margin line and the Auto copy. The NAME stays 4.3.1 for one
 * more candidate: nothing in 85 changes what a user sees except the onboarding sentence, and the
 * governor is the guard the 4.3.1 cadence ruling promised — the same release, made safe. If the
 * silence-hallucination fix that 85's margin line measures for lands next, THAT is 4.3.2.
 *
 * **versionCode 86 = 4.3.2 — the plain successor to 85, and the NAME moves.** 85 went to the
 * internal track on 2026-09-04 (spent there, as 81 and 83 were). 86 carries the silence fix —
 * speech evidence gates the encode, the stock-phrase blocklist — which changes what a user SEES
 * (no more "Thank you for watching" out of a quiet room), so the last place moves by one, exactly
 * as the previous paragraph promised. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 87 = 4.3.3 — the plain successor to 86.** 86 went to PRODUCTION on 2026-09-04 (the
 * silence fix). 87 makes the accessibility service optional: a user whose device forbids it (the
 * Galaxy XR's policy) or whose install Android restricts (Restricted Settings) can finish onboarding
 * and dictate to the clipboard instead of being locked out. What a user sees changes, so the name
 * moves. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 88 = 4.3.4 — the plain successor to 87.** 87 was built and uploaded as 4.3.3. 88 adds
 * a live Gemini provider (a settings surface and a BYOK mode a user can see), fixes the cloud-language
 * leak, and carries the sherpa-onnx runtime bump — a new surface, so the last place moves. The
 * streaming local tier, when it lands, is 4.4.0 (the owner's naming since 4.3.0). Every bump still
 * re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 89 — the plain successor to 88.** 88 went to the INTERNAL TRACK on 2026-09-10 and
 * the owner confirmed Gemini Live working in real time on it; it is spent on the track exactly as
 * 81, 83 and 85 were. 89 supersedes it there with three things 88 lacks: live by default (owner
 * ruling), the corrected turbo card, and the voice-archive gate fix. The NAME stays 4.3.4 — a name
 * is spent by a release, and 88 was not promoted. If 88 IS promoted before 89 uploads, 89 becomes
 * 4.3.5 and this paragraph is wrong: change both.
 *
 * **versionCode 90 = 4.4.0 — the MINOR moves, and this is the first time since 4.3.0.** 89 went to
 * the internal track as 4.3.4 and is spent there. 90 is not a patch: the app gains a second local
 * engine (the streaming previewer), two more asset packs (four in one bundle), and a rebuilt
 * start-up path. "4.4" has meant streaming in this project's own notes since 4.3.0 shipped, so the
 * name it was promised is the name it takes. Every bump still re-arms GpuPolicy's canary latches
 * (below) — and on this one the previewer's load-time canary is a SECOND, unrelated canary: it
 * guards the Zipformer against the FEAT_SME defect and is not persisted at all.
 *
 * **versionCode 91 = 4.4.1 — a PATCH, and the name says what it is.** 90 went to the internal
 * track as 4.4.0 and is spent there. 91 adds no engine, no pack and no payload: it makes the
 * previewer 90 already shipped reachable, by fetching a language's pack when that language is
 * picked and by telling the truth on the surfaces where 90 either said nothing or offered English
 * to someone who could never use it. One new input drives it — the selected language — and three
 * behaviours that were nearly true in 90 are literal in 91: Auto gets no live words, a "no" is
 * remembered per language, and nothing is loaded for a session that cannot arm it. Every bump
 * still re-arms GpuPolicy's canary latches (below); the previewer's own load-time canary is a
 * SECOND, unrelated canary and is not persisted at all.
 *
 * **versionCode 92 = 4.5.0 — the MINOR moves, because the previewer stops being English.** 91 went
 * to the internal track as 4.4.1 and is spent there. 92 adds six languages as on-demand packs and,
 * more to the point, the seam that makes a language a catalogue row instead of an architecture
 * change: per-pack model family, per-pack derived pad, per-pack canary and disabled latch, a strip
 * built from tokens, and one per-language observable both screens read. The previewer's canary is
 * now PER PACK, so a failed canary in one language can no longer refuse a load in another — the
 * process-wide latch this KDoc described at 90 is gone. Every bump still re-arms GpuPolicy's own
 * latches (below), unchanged.
 *
 * **versionCode 93 = 4.5.1 — a PATCH, and both halves came from one device session.** 92 went to
 * the internal track as 4.5.0 and is spent there. 93 changes no model, no pack and no payload: it
 * makes a freshly installed language arm on the FIRST tap instead of the second, by enumerating
 * the six moments that can change which pack should be resident instead of trusting the two that
 * happened to exist; and it hands the bubble's two text colours and its background opacity to the
 * user, with the live strip defaulting to red. Every bump still re-arms GpuPolicy's canary latches
 * (below), unchanged.
 *
 * **versionCode 94 = 4.5.2 — a PATCH that changes no code path a user can feel, and is the first
 * build any of the seven languages may be PROMOTED from.** 93 went to the internal track as 4.5.1
 * and is spent there. 94 carries the owner's licensing decision of 2026-09-13 into the clearance
 * record, corrects two claims that record asserted as fact, and ships the notices the Apache-2.0
 * and MIT grants and the AI-Hub FAQ ask for in exchange. **Korean's clearance is CONDITIONAL on
 * its attribution shipping**, which a test now holds: drop `KsponSpeech` or `aihub.or.kr` from the
 * notices and the suite goes red. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 95 = 4.6.0 — the MINOR moves, because the chooser changes shape.** 94 went to
 * production as 4.5.2. 95 retires the last English-only rung, moves the default to the multilingual
 * small model, and adds six INSTRUMENT rungs the owner will measure on six devices. **It is NOT
 * production-promotable as built**: `TierThroughput` withholds promotion while any selectable rung
 * carries no measured verdict, and every instrument does. That gate is the point — the previewer
 * hides a finalizer that falls behind, so a rung must earn production with a number. Every bump
 * still re-arms GpuPolicy's canary latches (below), unchanged.
 *
 * **versionCode 96 = 4.7.0 — the MINOR moves, because the ladder changes from instruments to a
 * ruling.** 95 was never uploaded to any Play track: it was SIDELOADED to the Tab S10+ over 94 on
 * 2026-09-16 as 4.6.0 (`adb install -r`), and is spent there. On
 * 2026-09-17 five of its rungs were timed on the owner's Tab S10+
 * (`docs/measurements/2026-09-17-tab-cpu-ladder.md`) and the owner ruled the same day: Q8 for
 * everything, every Q5 rung off the table. 96 is what a user sees change: the chooser is three Q8
 * rungs, the default moves from the 190 MB Q5_1 small to the 264 MB Q8_0 small (the same weights,
 * 2.2x faster per commit on that tablet), medium Q8_0 is recommended above a provisional RAM
 * threshold, and large-v3-turbo Q8_0 stays an instrument — it kept up with no margin. **It is
 * still NOT production-promotable**: `TierThroughputRecord.PRODUCTION_PROMOTABLE` is EMPTY, on the
 * owner's word that the accuracy pass on small and medium comes first, so 96 goes to the internal
 * track and the sideloaded tablet only. Every bump still re-arms GpuPolicy's canary latches
 * (below), unchanged.
 *
 * **versionCode 97 = 4.8.0 — the MINOR moves, because what a fresh install SEES and is OFFERED
 * changes on four counts.** 96 was 4.7.0, sideloaded to the Tab S10+ only and never on a Play
 * track, so it is spent there as 95 was. On 2026-09-17 the owner looked at it and ruled four
 * things: the transcript window's scrollbars are visible (persistent, drawn, both views); "Keep
 * bubble always on screen" is OFF by default for new installs, with a one-time backfill so an
 * existing install keeps what it had; the opacity ladder runs down to 20% with the legibility
 * guarantee kept honest at 85% and above; and first-run model choice is gated at his 4.5 GB —
 * under it the smallest Q8 rung, over it medium and turbo (medium steered, a controller ruling
 * on the throughput measurements), NPU-capable devices untouched. None of those is a patch to
 * 4.7's ladder; two of them change the default behaviour a new user meets. 97 goes to the
 * internal track. **It is still NOT production-promotable**: `PRODUCTION_PROMOTABLE` stays
 * empty on the owner's word that the accuracy pass on small and medium comes first. Every bump
 * still re-arms GpuPolicy's canary latches (below), unchanged.
 *
 * **versionCode 98 = 4.8.1 — a PATCH, and the name says what it is.** 97 was 4.8.0, sideloaded to
 * the Tab S10+ only and never on a Play track, so it is spent there as 95 and 96 were. 98 adds no
 * surface, no model and no pack: it closes the first-session miss the owner met on a fresh
 * 4.8.0/97 (2026-09-17 — after onboarding and the tap that installs the live-words pack, the
 * FIRST dictation showed no live words and the second did; *"users will just think it's
 * broken"*). The session gate arms on the POSTED warm now instead of the landed one, the card
 * retires only when a session opened warm, and `warm_now=` joins the gate line as the proof.
 * Which pack warms and when, the busy refusal and the release rules are untouched; onboarding
 * still does not start the service. The owner's ruling is that this build goes to PRODUCTION so
 * every install gets a smooth first session — the promotable set is his call at promotion time
 * and this patch does not touch it. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 98 = 4.9.0 — the NAME moves and the CODE does not, and this paragraph is why that
 * is not the silent mismatch this test exists to catch.** 98 was named 4.8.1 earlier on 2026-09-17
 * by the first-session live-words round and never left this machine: not uploaded to any Play
 * track, not sideloaded. A versionCode is spent by an UPLOAD (Play refuses a second one at the same
 * code) or by an install the next build must replace; 98 is neither, so the same integer carries the
 * new name. And the name is a MINOR, not a patch, because what a user sees and is offered changes on
 * three counts the owner ruled the same day, after his own dictation on all three rungs: the three
 * cards are labelled as a ladder in his words (fastest / balanced / highest accuracy, "slightly"
 * amended by controller ruling on the measurement); the first-run lineup is CUMULATIVE by RAM — small
 * always, medium and turbo at their floors, all three at or over 4.5 GB with medium steered; and the
 * ladder is AUTHORISED for production — `TierThroughputRecord.PRODUCTION_PROMOTABLE` names all
 * three, `ultra-q8` clearing on his ruling recorded beside its unchanged KEPT_UP_WITHOUT_MARGIN row,
 * so the gate reports `Promotable` for the first time since it was built. 4.8.1's first-session fix
 * rides along, and a session armed over a previewer that cannot open is no longer blank for its
 * whole length: the tee reports it, and the service returns that session to the ordinary in-flight
 * label (on CPU and NPU alike — the NPU tier has no whisper deltas to fall back on). And the
 * canary latches: GpuPolicy keys its crash sentinels and validated flags on
 * `BuildConfig.VERSION_CODE` (GpuPolicy.kt:101, :275) and re-trials once on any code it has not
 * seen, so what matters is not whether 98 is "a bump" relative to the never-shipped 4.8.1/98 (it is
 * not — nothing changes there) but that 98 has never been INSTALLED anywhere: every device that
 * receives it — the tablet on 97, production on 86 — sees a new code and re-arms on first launch,
 * exactly as on every bump (below).
 *
 * **versionCode 99 = 4.9.1 — a PATCH, and the name says what it is.** 98 went out as 4.9.0 on
 * 2026-09-17, uploaded by the owner, so it is spent: Play refuses a second upload at the same code.
 * 99 changes no model, no pack and no payload; it is the transcript window, on the owner's report
 * from his tablet the same day. The panel is its chosen height whether or not the text fills it
 * (the resize that "wants to drag the window around" was a height applied as a ceiling on a
 * wrap_content view, so a short panel got the window's y-compensation without the growth), a
 * resize drag locks to its axis, the handle is red and opaque, and the scrollbar beside each
 * transcript view can be grabbed and slid. What a user sees changes, so the last place moves by
 * one. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 105 = 4.11.3 — the panel follows the bottom again, and stops cutting text off.**
 * 104 is spent: it was built, sideloaded onto the Tab S10+ and downloaded by the owner, so a
 * higher code replaces it, and the name takes a PATCH because both halves are fixes to
 * behaviour 4.11.2 already had.
 *
 * **The follow.** The panel answered "is the reader at the bottom?" fresh on every repaint — a
 * `scrollY` read taken BEFORE `setText` and applied in a deferred `post`. A fixed-size
 * `TextView` rebuilds its layout synchronously inside `setText`, and `_preview` is a StateFlow
 * with four triggers collected by `collectLatest`, which cancels the coroutine body but not a
 * queued post; so a second repaint read the OLD offset against the NEW layout, decided the
 * reader had left, and the panel stopped following for the session. `PanelFollowLatch` is one
 * boolean only a finger may write, armed once per session, so nothing is derived across a text
 * change. The scrubber reports landings through a new `onTargetScrolled` because
 * `setOnScrollChangeListener` is a single-slot setter it already owns on both transcript views;
 * the service fences its own scroll out of that report. A review caught that the first cut had
 * dropped the old clamp, stranding a reader past the end of shrunken content with no bar to
 * drag back — `targetScrollY` now rescues a stranded view and still writes nothing to a reader
 * who is in range.
 *
 * **The cap.** `TranscriptSink.PREVIEW_CAP_CHARS` is `SpeakerLabels.NO_CAP`: the panel shows the
 * whole session. It was 4,000, then 20,000, and each raise only moved the session length at
 * which "the earlier parts are disappearing" comes back. The cost is measured rather than
 * assumed — a `panel:` diag reports the character count and BOTH O(session) passes per commit.
 *
 * **versionCode 104 = 4.11.2 — a session may hold SIXTEEN speakers, not eight.** 103 is spent:
 * it was built, sideloaded onto the Tab S10+ at 02:38 and downloaded by the owner, so a higher
 * code is what replaces it, and the name takes a PATCH because sixteen speakers is the same
 * capability at a different number.
 *
 * 8 was the spec's cap and had never been tested against the owner's own material. His material
 * has ten voices in it: testing 102 on both devices on 2026-09-20 he ran multi-speaker podcasts
 * deliberately — *"certain podcasts will have, like, almost ten people. And I did that
 * intentionally, and that part did work pretty well."* 102 answered those sessions correctly only
 * BECAUSE nothing capped the retrospective pass's answer, so **103 shipped a regression against
 * 102 on exactly that material**: making the two halves agree at 8 would have merged the ninth
 * and tenth people into whoever they most resembled. 104 raises
 * `SpeakerTracker.MAX_SPEAKERS` to 16 — one number that reaches the reclusterer (the assigner
 * hands it over), the online opening guard, and therefore the trim 103 added, which podcast
 * material now never reaches. 16 rather than 32 because cost is not what sets the number: a
 * phantom speaker needs `MIN_CLUSTER_SECONDS` of misattributed speech to earn a label, and a
 * higher cap leaves more room for one on music or crowd noise. Both 103 fixes stand.
 *
 * **versionCode 103 = 4.11.1 — the speaker cap binds the ANSWER, not only the seeds.** 102 is
 * spent: it was sideloaded onto the Tab S10+ at 2026-09-20 00:55 and the five sessions that
 * found this defect were run on it, so a higher code is what lets the next install replace it,
 * and the name takes a PATCH because a cap that binds is a fix to 4.11.0 rather than something
 * 4.11.0 could not do. `SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS` bounds the pass's SEEDS
 * and says so in its own KDoc; nothing bounded its ANSWER, and session 3 of that evening logged
 * `speaker-recluster: n=505 clusters=11 confirmed=11` against a cap of 8. `SpeakerAssigner`
 * reseeds the tracker with one LIVE voice per cluster and the online path opens a new speaker
 * only while `liveCount < maxSpeakers`, so over the cap that guard is dead for the rest of the
 * session — every later unheard voice goes to the closest voice already known and teaches it
 * nothing — and `endChunk`'s corrective merge is inert at the same moment, because it skips
 * CONFIRMED voices and a reseed marks every voice confirmed. 103 makes the cap a parameter of
 * `recluster`, defaulted to `SpeakerTracker.MAX_SPEAKERS` and passed by the assigner as the
 * tracker's OWN `maxSpeakers`, so the two halves cannot disagree. Over the cap the
 * longest-speaking survive and the rest fall into the absorption loop every sub-bar cluster
 * already goes through, so no window loses its label.
 *
 * **What that earns is narrower than it looks, and the source says so where the trim is.** AT
 * the cap the opening guard is false too, so capping to 8 does NOT hand the tracker back the
 * ability to open a ninth voice. It earns the bound `SpeakerTracker.speakerCount` documents
 * about itself, a label space that stops drifting upward, and displacement: every pass
 * re-decides WHICH speakers survive, so a person who out-speaks the weakest survivor takes that
 * slot at the next pass. It costs a real ninth speaker on material the cap is too small for,
 * and `SpeakerTracker.MAX_SPEAKERS` is the one place to change that.
 *
 * **A second defect is fixed in the same breath**, because the cap turned it from rare into
 * systematic: `Cluster.longest` is the seed set `reseed` rebuilds a voice from and it was read
 * AFTER absorption, yet absorption merges two things the pass just proved are NOT one voice
 * (step 3 already merged every pair reaching `RECLUSTER_SIM`). A trimmed cluster clears the mass
 * bar, so its long windows would win the duration sort and become the survivor's identity —
 * after which the tracker answers ~1.0 to the wrong person and `credit` evicts the survivor from
 * its own id. Identity is now snapshotted from a cluster's OWN pre-absorption windows.
 *
 * The tier that 102 changed passed its regression row on the way: one voice stayed one speaker,
 * and the cost stayed a fifth of the finalize fence
 * (`docs/measurements/2026-09-20-411-timing-layer-field.md`).
 *
 * **versionCode 102 = 4.11.0 — the MINOR moves, because the tier that labelled a CHUNK now labels
 * a SENTENCE.** 101 is spent: the owner installed 4.10.1 on his Z Fold6 and ran the controlled
 * 3 min 20 s session of 2026-09-19 20:37-20:41 on it
 * (`docs/measurements/2026-09-18-speaker-spike.md` §Session 7; the plan's Task 5 header records it
 * on the internal track), so a higher code is what lets the next install replace it. 102 is the
 * plain next integer, and the NAME is not a patch to 4.10.1 — it retires the ceiling 4.10.1 wrote
 * down as its own. That session is why: on hard-cut media the standalone VAD returned ONE 9-15 s
 * segment per chunk, so `windows=1`, so the whole 15 s took one label and *"after about two
 * minutes they just stopped, and everything just becomes one speaker"*. The tracker was never
 * wrong — ids 2 and 3 alternated to the end — the granularity at which text could carry a label
 * was.
 *
 * **What 102 changes, in two places that never meet.** The CPU tiers ask whisper.cpp for
 * `params.token_timestamps` and export a `[t0cs, t1cs, byteStart, byteEnd]` quad per token beside
 * the geometry they already export, so a fingerprint window may now end at a WORD: a stretch still
 * spanning `2 * LONG_SEGMENT_SECONDS` (4.0 s) is bisected at the nearest token edge, recursively.
 * The NPU tier stops prompting `<|notimestamps|>` and stops masking the timestamp range, so its
 * QNN decoder emits the timestamp tokens it always had in its vocabulary; `NpuSentences` parses
 * them into sentence bounds and the VAD route makes ONE WINDOW PER SENTENCE instead of one per
 * chunk. Session 7's `segs=1 windows=1` becomes `segs=1 windows=4`, which is the line that says
 * the fix is live. Everything downstream — `SpeakerTracker`, `SpeakerReclusterer`, `SpeakerRuns`,
 * `SpeakerLabels`, `TranscriptSink` — is untouched, because all of it keys on window indices.
 *
 * **The text guarantee is kept on the CPU tiers and knowingly TRADED on the NPU one, and the
 * asymmetry is the whole of what a reviewer needs to know.** On CPU, timing is additive:
 * `token_timestamps` changes no logit and no segmentation, the JNI keeps `result += seg` as the
 * sole author of the returned bytes and drops a segment's quads rather than let a token walk
 * rebuild them, and `SegmentGeometryPinTest` fails the build if that ever stops being true. On
 * NPU there is no such version: dropping `<|notimestamps|>` from the prompt RE-CONDITIONS the
 * decode — the model is answering a different question — and each emitted timestamp spends one of
 * the 197 budget positions, so a long chunk can truncate at a different token than it did at
 * 4.10.1. The spec asks for it anyway and wins, because one label for a 15 s chunk is a worse
 * transcript than a differently-truncated long one. §AO's rows are scoped to match: unchanged text
 * is asked of the CPU tiers, sane and complete text of the NPU one.
 *
 * **And one guard had to be re-based rather than left alone.** `qnn_asr.cpp`'s 4.3.1 repetition
 * cut histogrammed the last 32 generated ids; timestamps are ever-increasing singletons, so a
 * `<|t|><|t|> Thank you.` loop would put ~13 distinct ones in that window and push both `distinct`
 * past `CYCLE_MAX_DISTINCT` and the entropy past `ENTROPY_THOLD` — disarming the trip exactly when
 * a runaway needed it. The window now holds 32 TEXT ids and the last-rung cut drops to that
 * window's own start. On a stream with no timestamps it is byte-for-byte the pre-4.11 guard.
 *
 * **`dtw_token_timestamps` is still never set, and that is a shipping constraint rather than a
 * preference.** whisper.cpp gates the new-segment callback on `!dtw_token_timestamps`
 * (`whisper_jni.cpp` records the landmine at the callback), so enabling DTW would silently kill
 * the live words strip with no error anywhere. The heuristic token path does not. A pin asserts
 * the flag is absent from the whole translation unit, and §AO asks a device to prove the strip
 * still fills. Every bump still re-arms GpuPolicy's canary latches (below), unchanged.
 *
 * **versionCode 101 = 4.10.1 — a PATCH, and the tier it fixes is the one the owner tests on.**
 * 100 went to the internal track on 2026-09-19 and is spent there. On his Z Fold6 — NPU-capable,
 * so the 4.3 one-tier rule offers `npu-turbo` alone — 4.10.0 produced *"no speaker changes at
 * all"*. Not a defect in the tier: every speaker window came from the geometry `transcribeRaw`
 * exports, and `NpuWhisperBackend.lastGeometry` answers null while that arm is live, exactly as
 * its own KDoc says, because the HTP path never runs the whisper.cpp VAD. 101 gives that tier a
 * VAD of its own — `WhisperNative.vadSegmentsOf` on the speaker thread, one segmenter in the JNI
 * with two callers — and one id per chunk: the window holding the most SPEECH, ties to the
 * earliest. Coarser and honest about it: with no token timestamps on that decoder a chunk's text
 * cannot be split between two voices, so a change lands on a 6-8 s chunk boundary. The CPU tiers
 * are untouched, gated on what the backend CAN publish rather than on what one read returned.
 *
 * **versionCode 100 = 4.10.0 — the MINOR moves, because the app gains a capability.** 99 went to
 * PRODUCTION as 4.9.1 on 2026-09-17, uploaded by the owner, so it is spent twice over, as 82 and
 * 84 were: Play refuses a second upload at the same code, and every installed phone already
 * carries it. 100 is the plain next integer. **And the NAME is a minor, not a patch**, because
 * what 4.10.0 adds is not a fix to anything 4.9 did: committed text is split into paragraphs at
 * every change of speaker and labelled `Speaker N:` in the transcript panel once a second voice
 * is confirmed, with the first paragraph relabelled from the session's start at the moment of
 * confirmation. The whole of it rests on a bundled 40.3 MB speaker-embedding model (NVIDIA NeMo
 * TitaNet-small) that no previous build shipped, so the base module grows by that much.
 *
 * **The one-voice case is the one that makes this safe to ship, and it is a NON-change:** with a
 * single speaker all session the output is 4.9's byte for byte — no label, no extra break —
 * because the panel shows nothing until a second voice is CONFIRMED. So is a cloud session, and
 * so is the live preview strip. Two settings decide the rest: *"Detect speakers"* defaults **on**
 * (off restores 4.9 everywhere and never loads the model), and *"Speaker labels in copied and
 * saved text"* defaults **off** — the clipboard and saved transcripts always get the paragraph
 * breaks and get the labels only when it is flipped, applied at export time so transcripts
 * already on disk follow the switch.
 *
 * **Two things a promotion has to read before it happens, and neither is a build gate.** The
 * model is **CC-BY-4.0**: the attribution that licence asks for is PAID in `oss_licenses.html`
 * (the adapter's own pin test reads that page and fails the build if it leaves), but its row in
 * `docs/LANGUAGE-CLEARANCE.md` was **signed off by Brandon Slacum on 2026-09-19** — he was asked
 * whether he accepted the basis and answered "I agree and approve it"; the seven packs set
 * that order: notice first, decision after. And the spike that chose the model wrote
 * fingerprints and, behind a flag file, SPEECH AUDIO; `SpeakerSpike.SPEAKER_SPIKE` is `false`
 * in this build, which compiles the writers away, and the purge is unconditional so a device
 * that ran a spike build is cleaned at its first launch on 100. Every bump still re-arms GpuPolicy's canary latches
 * (below), unchanged.
 *
 * **What 82 buys, stated precisely.** It buys an upgrade over the 81 build now sitting on the
 * track AND on the owner's phone: 82 > 81, so the next track install replaces it — which is a real
 * change from 4.2's position, where u4 (uninstall before the track install) was MANDATORY because
 * the local build was 81-signed and Play offers no update path at equal versionCode. A local
 * bundletool `install-apks` set is still not a Play-managed install, so u4 remains mandatory
 * whenever a LOCAL 82 build has been installed by hand. If this KDoc and
 * docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md ever disagree, the sheet is the
 * instrument and the sheet is right.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 81 -> 82 clears
 * every recorded GPU verdict on every device — the same side effect 78 -> 80 and 80 -> 81 had, and it
 * recurs on every bump by design. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters
 * (docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md).
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_4_11_3_at_version_code_105() {
        assertEquals(
            "versionName must be 4.11.3 for this release (app/build.gradle.kts defaultConfig)",
            "4.11.3",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 105 for this release (app/build.gradle.kts defaultConfig). " +
                "104 = 4.11.2 is spent — built, sideloaded onto the Tab S10+ and downloaded by " +
                "the owner, so only a higher code replaces it",
            105,
            BuildConfig.VERSION_CODE,
        )
    }
}
