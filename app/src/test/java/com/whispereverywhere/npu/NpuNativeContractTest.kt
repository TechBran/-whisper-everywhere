package com.whispereverywhere.npu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the NPU seam: `qnn_asr.cpp`, the FastRPC manifest declarations,
 * and the .gitignore entry that keeps Qualcomm's proprietary headers out of the repo.
 *
 * WHY A TEST THAT READS C++, XML AND .gitignore: `QnnAsrNative` carries
 * `init { System.loadLibrary("qnnasr") }`, so ANY JVM test that touched the object would die with
 * `UnsatisfiedLinkError` — there is no `libqnnasr.so` on the unit-test classpath and there never
 * will be. Native behaviour is device-verified at Q10a and nowhere earlier. That leaves four
 * constraints that each cost a real device round trip (or, for the header entry, would cost a
 * licence violation) with no automated guard at all between now and then. These assertions pin the
 * constructs themselves, so a regression fails here in seconds instead of on-device in a month.
 *
 * Each assertion is anchored to CONTENT, never to a line number, and every positive pin is scoped
 * to LIVE lines: a commented-out `// systemContextFree(...)` satisfies `contains()` exactly as
 * happily as the call, which is the false-green shape already proved twice on
 * `NativeVadSourceContractTest`.
 *
 * `src/main/cpp/qnn_asr.cpp`, `src/main/AndroidManifest.xml` and the root `.gitignore` are declared
 * as explicit inputs of the test task in `app/build.gradle.kts` — without that, an edit confined to
 * any of them leaves `:app:testDebugUnitTest` UP-TO-DATE and these guards pass against stale
 * evidence.
 */
class NpuNativeContractTest {

    /**
     * Reads a repo file from the test's working directory — the locator `SegmentTimingTest`,
     * `NativeVadSourceContractTest` and `CaptureThreadPolicyTest` share. Line endings are
     * normalized at this single read site (the 3.7 N1 lesson: `readText()` does not normalize, and
     * a CRLF checkout silently defeats anything anchored on a newline — `qnn_asr.cpp` and the
     * manifest are both CRLF here).
     */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "cannot locate $relative from ${System.getProperty("user.dir")}"
        )
    }

    /**
     * Character offsets of every LIVE (non-comment) line of [scope] containing [needle]. Ordering
     * assertions must never be built on `indexOf`: it measures a commented-out mention exactly as
     * happily as the code, so a comment that drifts above the line it describes silently satisfies
     * "X comes first".
     */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /** One free function's body: every function in `qnn_asr.cpp` closes at column 0. */
    private fun functionBody(cpp: String, anchor: String): String {
        val start = cpp.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from qnn_asr.cpp. indexOf() returns -1 when the anchor " +
                "is absent, so substring(start) would silently rebase the scope to the top of the " +
                "file and every claim below would be answered by unrelated code instead of failing.",
            start >= 0
        )
        val body = cpp.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\". substringBefore() returns its RECEIVER " +
                "when the delimiter is absent, so a re-indented closing brace would silently widen " +
                "the scope into the FOLLOWING function and the assertions would pass on a " +
                "neighbour's code.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    private val cpp: String by lazy { source("src/main/cpp/qnn_asr.cpp") }

    /**
     * Lesson 3 — the one that cost a device round trip on the spike's run 6. `QNN_TENSOR_VERSION_1`
     * and `QNN_TENSOR_VERSION_2` are ENUM CONSTANTS, not macros, so `#ifdef QNN_TENSOR_VERSION_2`
     * is ALWAYS false: the whole v2 branch compiles out while the error text still claims the
     * reader knows 1 and 2. The asset's tensors are v2, so the spike rejected a version it
     * advertised as supported — and it did so at load time, on device, with no build-time signal
     * whatsoever.
     */
    @Test
    fun tensorVersionKnownReadsTheEnumeratorsDirectlyNeverThroughIfdef() {
        val body = functionBody(cpp, "bool tensorVersionKnown(")
        assertTrue(
            "tensorVersionKnown must accept QNN_TENSOR_VERSION_2 on a LIVE line — the shipped " +
                "asset's tensors ARE v2 (both context binaries report tensor v2), so a reader that " +
                "silently drops the v2 branch rejects every tensor in the model.",
            liveOffsets(body, "QNN_TENSOR_VERSION_2").isNotEmpty()
        )
        assertTrue(
            "tensorVersionKnown must accept QNN_TENSOR_VERSION_1 on a LIVE line too.",
            liveOffsets(body, "QNN_TENSOR_VERSION_1").isNotEmpty()
        )
        assertTrue(
            "tensorVersionKnown must contain NO preprocessor conditional at all. The v2 branch was " +
                "wrapped in `#ifdef QNN_TENSOR_VERSION_2` on the spike; because those names are " +
                "enumerators the guard was always false and the branch was silently compiled out. " +
                "Found: " + body.lineSequence().filter { it.trimStart().startsWith("#") }.toList(),
            body.lineSequence().none { it.trimStart().startsWith("#") }
        )
        // LIVE lines only, and this one is the exception that proves the rule. Everywhere else in
        // this file a NEGATIVE assertion uses plain contains(), because that is the stricter
        // reading: even a comment mentioning the forbidden construct is usually a claim with no
        // business being there. Here it is the opposite — the ported lesson comment above
        // tensorVersionKnown QUOTES `#ifdef QNN_TENSOR_VERSION_2` in order to explain why it must
        // never be written, and that prose is the most valuable line in the helper block. Demanding
        // the file never contain those characters would force the explanation to be deleted to
        // satisfy a guard about the thing it explains. Verified, not assumed: the whole-file form
        // failed on exactly that comment the first time this test ran green-path.
        val forbidden = Regex("""#\s*(ifdef|ifndef|if\s+!?defined)\s*\(?\s*QNN_TENSOR_VERSION""")
        val offenders = cpp.lineSequence().filter { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && forbidden.containsMatchIn(line)
        }.toList()
        assertTrue(
            "no LIVE #ifdef / #ifndef / #if defined may probe a QNN_TENSOR_VERSION_* enumerator " +
                "ANYWHERE in qnn_asr.cpp — the accessors below tensorVersionKnown (tensorRank, " +
                "tensorDims, tensorName, tensorDataType, tensorRepoint, tensorSetClientBuf) each " +
                "carry the same v1/v2 fork and would fail exactly the same silent way. Found: " +
                offenders,
            offenders.isEmpty()
        )
    }

    /**
     * Lesson 2 — the run-6 crash itself. `Qnn_Tensor_t` is not self-contained: `name` and
     * `dimensions` are POINTERS into storage owned by the system context. The spike shallow-copied
     * the descriptors, called `systemContextFree()` immediately, and then dereferenced
     * `dimensions` while binding buffers: a use-after-free, right after `graphRetrieve`.
     *
     * Two independent belts, and this pins both: every kept pointer is repointed at storage WE own
     * (`tensorRepoint`), AND the system context outlives the copy — the only `systemContextFree`
     * in the file sits in teardown, textually after the repoint.
     */
    @Test
    fun theSystemContextIsFreedOnlyAfterEveryDescriptorHasBeenDeepCopied() {
        val repoint = liveOffsets(cpp, "tensorRepoint(")
        assertTrue(
            "qnn_asr.cpp must both DEFINE and CALL tensorRepoint on live lines (found " +
                "${repoint.size} live mentions, expected at least 2: the definition plus at least " +
                "one call site). Without a call, nothing owns the names and dimension arrays the " +
                "descriptors point at, and the pointers dangle into the system context's storage " +
                "the moment it is freed.",
            repoint.size >= 2
        )
        val free = liveOffsets(cpp, "systemContextFree(")
        assertTrue(
            "qnn_asr.cpp must call systemContextFree on exactly one LIVE line — teardown. Found " +
                "${free.size}. A second call site is how the run-6 use-after-free came back: an " +
                "early free on an error path looks harmless and frees the storage every retained " +
                "descriptor points into.",
            free.size == 1
        )
        assertTrue(
            "systemContextFree must appear AFTER the last tensorRepoint site (free at " +
                "${free.first()}, last repoint at ${repoint.last()}). Both offsets come from LIVE " +
                "lines, never indexOf: the file's own prose names systemContextFree several times " +
                "while explaining why it must not run early, and a raw search would measure the " +
                "comment and report the ordering of code that does not exist.",
            free.first() > repoint.last()
        )
    }

    /**
     * C7 — the cross-KV alias guard, and the bind that consumes its proof.
     *
     * The whole zero-copy design rests on one claim: the encoder's 24 cross-KV output buffers can
     * be handed to the decoder as its cross-KV inputs untouched, because the two sides describe the
     * same tensor. If a future asset re-export shifts one side's scale, the decoder reads them
     * under the wrong affine transform — not a crash, not garbage, **plausible wrong text**, which
     * is the worst failure a dictation app has and the one a single-sentence acceptance run is
     * least likely to catch.
     *
     * MEASURED, NOT ASSUMED: at Q3 a mutant that deleted the whole `aliasGuardLocked()` call from
     * `nativeInit` compiled green and left the suite at 128/1379/0. This test is what makes that
     * mutation fail, and it asserts THREE things because each defeats a different edit:
     *  - **presence** — an ordering-only pin is vacuously satisfied when site A is deleted;
     *  - **order** — the guard must run before the bind, not after the first transcript;
     *  - **the population check** — the loop verifies the 24 pairs it knows about and can say
     *    nothing about a 25th, so both sides are counted and the count must equal the number
     *    verified. Deleting that ratified check would leave the guard silently covering less than
     *    it appears to, while presence and order still hold.
     */
    @Test
    fun theAliasGuardRunsBeforeTheCrossKvBindAndStillCountsTheWholePopulation() {
        val init = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(")
        val guard = liveOffsets(init, "aliasGuardLocked()")
        assertTrue(
            "nativeInit must CALL aliasGuardLocked() on a live line. Presence is asserted " +
                "separately from ordering because \"A precedes B\" is trivially true when there is " +
                "no A — deleting the call is exactly the mutation measured green at Q3.",
            guard.isNotEmpty()
        )
        val bind = liveOffsets(init, "bindDecoderLocked()")
        assertTrue(
            "nativeInit must CALL bindDecoderLocked() on a live line — that is where the 24 " +
                "cross-KV inputs are pointed at the encoder's output buffers.",
            bind.isNotEmpty()
        )
        assertTrue(
            "aliasGuardLocked() must run BEFORE bindDecoderLocked() (guard at ${guard.first()}, " +
                "bind at ${bind.first()}). Verifying the alias after aliasing on it is not a " +
                "guard; and running it before the buffers are even allocated is why a refusal " +
                "costs nothing rather than 27 MiB.",
            guard.first() < bind.first()
        )

        val body = functionBody(cpp, "std::string aliasGuardLocked()")
        assertTrue(
            "aliasGuardLocked must compare the 24 pairs field by field via aliasCompare() on a " +
                "live line",
            liveOffsets(body, "aliasCompare(").isNotEmpty()
        )
        assertTrue(
            "aliasGuardLocked must still COUNT the cross-KV population — the pair loop verifies " +
                "the 24 tensors this seam knows about and is silent about a 25th, which a whisper " +
                "variant with more than 12 layers would carry and which bindDecoderLocked's " +
                "bind-by-name pass would happily alias unchecked. Expected a live countCross " +
                "definition scanning for the shared name fragment.",
            liveOffsets(body, "countCross = [](").isNotEmpty() &&
                liveOffsets(body, "_cache_cross_").isNotEmpty()
        )
        assertTrue(
            "countCross must be applied to BOTH sides — the encoder's outputs and the decoder's " +
                "inputs. Counting one side cannot detect an extra tensor on the other. Found " +
                "${liveOffsets(body, "countCross(").size} live call sites.",
            liveOffsets(body, "countCross(").size >= 2
        )
        assertTrue(
            "the counted population must be ASSERTED equal to the number of pairs verified, on a " +
                "live line. Counting without comparing is decoration. Live lines mentioning " +
                "encCross: " + liveLines(body, "encCross"),
            liveOffsets(body, "encCross != checked || decCross != checked").isNotEmpty()
        )
    }

    /**
     * C2 — the suppression mask is applied to the logits, and only THEN is the argmax scanned.
     *
     * Whisper's suppression is by construction a pre-argmax mask. Reverse these two and the code
     * still compiles, still runs, and still returns a token every step — it just returns the
     * suppressed one, because a scan that has already picked a winner has thrown the runner-up
     * away. Re-running the step is deterministic, so a caller holding only the argmax can neither
     * fix it nor detect it: the loop emits the suppressed token or hangs. That is the entire reason
     * the decode loop lives on the native side of the JNI boundary at all, and this is the pin that
     * keeps the two halves from drifting apart into a "helpful" per-step API.
     */
    @Test
    fun theSuppressionMaskIsAppliedToTheLogitsBeforeTheArgmaxIsScanned() {
        val body = functionBody(cpp, "int32_t suppressThenArgmax(")
        val mask = liveOffsets(body, "logits[id] = kLogitFloor;")
        assertTrue(
            "suppressThenArgmax must WRITE the mask into the logits buffer on a live line — both " +
                "for the always-on list and for the begin-suppress list. Found ${mask.size}, " +
                "expected at least 2.",
            mask.size >= 2
        )
        val scan = liveOffsets(body, "if (logits[i] > bestVal)")
        assertTrue(
            "suppressThenArgmax must scan the logits for the argmax on a live line",
            scan.isNotEmpty()
        )
        assertTrue(
            "the LAST mask write (${mask.last()}) must come before the FIRST scan comparison " +
                "(${scan.first()}). Both offsets are live lines, never indexOf: this file's prose " +
                "names the ordering several times while explaining why it matters, and a raw " +
                "search would measure the comment.",
            mask.last() < scan.first()
        )
        assertTrue(
            "the begin-suppress list must be applied CONDITIONALLY inside the same function — it " +
                "belongs to the first generated step only, and hoisting it into the always-on " +
                "list would mask EOT at every step and leave the loop with no terminator short of " +
                "the position cap.",
            liveOffsets(body, "if (applyBegin)").isNotEmpty()
        )

        // AND THE CALL SITE, which is the same mistake one level up. Everything above scopes to
        // suppressThenArgmax's own body; none of it says nativeDecodeSegment ever CALLS it.
        // Replacing that one call with `argmaxInRange(logits, 0, g.vocab)` compiles, reintroduces
        // C2 in full, and leaves every assertion above satisfied — exactly the shape of Q3's N1,
        // where the guard was present and the call was gone.
        val loop = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "nativeDecodeSegment must CALL suppressThenArgmax on a live line. The mask and the " +
                "scan being correctly ordered inside a function nobody calls is not a defence.",
            liveOffsets(loop, "suppressThenArgmax(").isNotEmpty()
        )
        assertTrue(
            "the begin-suppress predicate must be `position == promptLen - 1` on a live line in " +
                "nativeDecodeSegment. This is the brief's own named defect: BEGIN_SUPPRESS applies " +
                "at the FIRST GENERATED step, which is promptLen - 1, and NOT at position == 0 " +
                "(where the model is still being fed the prompt and its argmax is discarded). " +
                "`position == 0` compiles and mutes 220/EOT at a step whose output is thrown away, " +
                "leaving the real first token unguarded.",
            liveOffsets(loop, "position == promptLen - 1").isNotEmpty()
        )

        // The decode loop needs EOT for itself: the contract's argument list is fixed and a
        // terminator smuggled in through a data array is a terminator nobody can see. That makes
        // kEotToken a SECOND reading of an asset fact whose first reading is WhisperTokens.EOT,
        // and two readings that can drift are one reading with extra steps.
        val eot = liveLines(cpp, "constexpr int32_t kEotToken")
        assertTrue(
            "qnn_asr.cpp must define kEotToken on exactly one live line; found: $eot",
            eot.size == 1
        )
        assertTrue(
            "native's kEotToken and Kotlin's WhisperTokens.EOT (${WhisperTokens.EOT}) are two " +
                "readings of the same asset fact and must agree. If they ever disagree, the " +
                "decode loop terminates on an id the tokenizer does not call end-of-text and the " +
                "transcript runs to the position cap. Found: ${eot.first()}",
            eot.first().contains("= ${WhisperTokens.EOT};")
        )
    }

    /**
     * The decode loop's four single-point invariants — each one line, each with no host-visible
     * signal, none of them defended before this pin existed.
     *
     * `position` is the single counter and every one of these edits compiles, runs, and produces a
     * plausible transcript:
     *  - `lastPosition = maskLen - 2` → `- 1` lets position 199 execute, one past the 199-slot
     *    self-KV. That is the brief's named overrun;
     *  - deleting the prompt-prefill swap makes every prompt step read set 0 and write set 1, so
     *    the prefill cache is thrown away and only the last prompt token's state survives;
     *  - deleting `next = tok` feeds the same token forever — the model repeats one word to the
     *    position cap;
     *  - deleting either terminator (`EOT`, the budget) removes the loop's ability to stop early.
     *
     * None of these is a defect in the shipped code. The pin exists because all four are a single
     * line, and Q10a is the first execution.
     */
    @Test
    fun theDecodeLoopsSinglePointInvariantsArePinned() {
        val loop = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "the last EXECUTING position must be `g.maskLen - 2` on a live line — 198 for this " +
                "asset. `- 1` runs position 199, one past the 199-slot self-KV, with the graph " +
                "doing the indexing and nothing on either side reporting it. Live lines " +
                "mentioning lastPosition: " + liveLines(loop, "lastPosition"),
            liveOffsets(loop, "const uint32_t lastPosition = g.maskLen - 2;").isNotEmpty()
        )
        val swaps = liveOffsets(loop, "bindSelfKvLocked(1 - g.selfInSet);")
        assertTrue(
            "the loop must swap the self-KV ping-pong on BOTH paths — after a discarded " +
                "prompt-prefill step and after an emitted token. Found ${swaps.size} live sites, " +
                "expected 2. Dropping the prefill swap makes every prompt step read set 0 and " +
                "write set 1, so three quarters of the prompt's cache is overwritten before the " +
                "first generated token ever sees it.",
            swaps.size >= 2
        )
        assertTrue(
            "the loop must feed the emitted token back as the next input (`next = tok;`) on a " +
                "live line. Without it `next` stays at prompt[0] and the model is asked to " +
                "continue from <|startoftranscript|> at every position.",
            liveOffsets(loop, "next = tok;").isNotEmpty()
        )
        assertTrue(
            "the loop must terminate on EOT on a live line — it is the only terminator short of " +
                "the position cap.",
            liveOffsets(loop, "if (tok == kEotToken)").isNotEmpty()
        )
        assertTrue(
            "the loop must terminate on the token budget on a live line, AFTER writing the token " +
                "that filled it.",
            liveOffsets(loop, "if (count >= maxTokens) break;").isNotEmpty()
        )
    }

    /**
     * The decoder's load-time guards on the asset's own numbers — step 1's doctrine applied to the
     * four facts the loop rests on besides the cross-KV alias, plus the encode-validity flag.
     *
     * Each of these is a guard whose deletion is a one-line edit with no host-visible signal, and
     * each defends a failure that presents as **fluent wrong text** rather than as an error:
     *  - **dtype equality, not byte width.** `elementSize()` collapses signed/unsigned/float, so an
     *    `SFIXED_POINT_16` `logits` re-export passes every size check and inverts the raw-code
     *    ordering the whole argmax argument rests on;
     *  - **the mask codes**, checked against `attention_mask`'s own quantisation. If a re-export
     *    flips its offset, 65535 becomes the *blocked* code and the decoder attends to nothing;
     *  - **the self-KV depth**, checked against the mask width. `lastPosition` is derived from one
     *    tensor and the "exact fit" claim is about another, and nothing else compares them;
     *  - **the encode-validity flag.** The decoder reads the cross-KV in place, so a decode with no
     *    preceding encode transcribes the *previous segment* — the worst failure shape in the tier
     *    and its most likely integration mistake.
     */
    @Test
    fun theDecodersLoadTimeGuardsOnTheAssetsOwnNumbersCannotBeDeletedSilently() {
        val bind = functionBody(cpp, "std::string bindDecoderLocked()")
        assertTrue(
            "bindDecoderLocked must assert the exact Qnn data TYPE of input_ids and position_ids " +
                "(QNN_DATATYPE_INT_32), not merely their 4-byte width — elementSize() maps " +
                "INT_32, UINT_32, SFIXED_32, UFIXED_32 and FLOAT_32 all to 4.",
            liveOffsets(bind, "QNN_DATATYPE_INT_32").size >= 2
        )
        assertTrue(
            "bindDecoderLocked must assert QNN_DATATYPE_UFIXED_POINT_16 for BOTH attention_mask " +
                "and logits. UNSIGNED is the load-bearing half: dequantisation is scale x (q - zp) " +
                "with scale > 0, so an argmax over the raw codes is exact — read as signed two's " +
                "complement the ordering inverts and every token this file picks is wrong, with " +
                "every size, bind and buffer still correct. Found " +
                "${liveOffsets(bind, "QNN_DATATYPE_UFIXED_POINT_16").size} live mentions, " +
                "expected 2.",
            liveOffsets(bind, "QNN_DATATYPE_UFIXED_POINT_16").size >= 2
        )
        assertTrue(
            "bindDecoderLocked must CALL checkMaskCodesLocked() on a live line — the two mask " +
                "codes are written 199 times per segment and were, until this guard, a comment.",
            liveOffsets(bind, "checkMaskCodesLocked()").isNotEmpty()
        )
        val mask = functionBody(cpp, "std::string checkMaskCodesLocked()")
        assertTrue(
            "checkMaskCodesLocked must actually DEQUANTISE both codes through the tensor's own " +
                "scale and offset and compare the results. A guard that reads the quant params " +
                "and asserts nothing about them is decoration.",
            liveOffsets(mask, "kMaskAttend) + offset) * scale").isNotEmpty() &&
                liveOffsets(mask, "kMaskBlocked) + offset) * scale").isNotEmpty() &&
                liveOffsets(mask, "attend > -0.01 && attend < 0.01").isNotEmpty() &&
                liveOffsets(mask, "blocked <= -1.0").isNotEmpty()
        )
        assertTrue(
            "bindDecoderLocked must compare the self-KV cache DEPTH against the mask width on a " +
                "live line. lastPosition comes from attention_mask; the 'exact fit' is a claim " +
                "about the self-KV; nothing else in the file relates the two.",
            liveOffsets(bind, "depth != g.maskLen - 1").isNotEmpty()
        )

        val enc = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeEncode(")
        assertTrue(
            "nativeEncode must SET the encode-validity flag on a live line, and only after a " +
                "successful graphExecute.",
            liveOffsets(enc, "g.encoded = true;").isNotEmpty()
        )
        assertTrue(
            "nativeEncode must CLEAR the flag on entry on a live line — a graphExecute that " +
                "failed part way may have written some of the 24 cross-KV buffers, and half a " +
                "segment decodes as fluently as a whole one.",
            liveOffsets(enc, "g.encoded = false;").isNotEmpty()
        )
        val decode = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(")
        assertTrue(
            "nativeDecodeSegment must refuse a decode with no encoded segment, on a live line. " +
                "Without it the decoder reads the previous segment's cross-KV in place and " +
                "transcribes it — no crash, no error, no way for the caller to tell.",
            liveOffsets(decode, "if (!g.encoded)").isNotEmpty()
        )
        val detect = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeDetectLanguage(")
        assertTrue(
            "nativeDetectLanguage must refuse the same way — detecting off the previous segment's " +
                "encoder state picks the wrong prompt language for this one.",
            liveOffsets(detect, "if (!g.encoded)").isNotEmpty()
        )
        val release = functionBody(cpp, "void releaseLocked()")
        assertTrue(
            "releaseLocked must clear the flag on a live line, so a torn-down session cannot be " +
                "decoded against.",
            liveOffsets(release, "g.encoded = false;").isNotEmpty()
        )
    }

    /**
     * THE SUSTAINED VOTE, exactly as measured — and the highest-consequence invariant in this tier
     * that nothing else can see.
     *
     * An unvoted or mis-voted session is ~2.5x slower and is **indistinguishable from slow
     * silicon** from outside: spike run 7 measured a 1007 ms median with an 838–1275 ms spread and
     * cost a device round trip to explain. Every needle below was a one-line "improvement" that
     * compiles green and can only be caught on a device:
     *  - `dcvsEnable = 0` pins the clock (the burst recipe) and burns battery for +9.6% — which is
     *    the accepted trade, in the other direction, for a config held for minutes;
     *  - `MAX_VOLTAGE_CORNER` instead of `TURBO` asks for a corner a phone cannot hold;
     *  - an `RPC_POLLING_TIME` entry spins to dodge interrupt latency and burns power
     *    continuously, process-wide. It is a burst trick and it is not here;
     *  - deleting the `vote:` log line is what made run 7 unreadable in the first place.
     *
     * Plus the ordering: the vote is armed at the END of `nativeInit`, after the contexts are
     * loaded. The spike measured its 525 ms cold load **unvoted**, so hoisting the arm above
     * `loadGraphSlot` would make our cold load faster than the figure the plan quotes and quietly
     * invalidate it.
     */
    @Test
    fun theSustainedVoteIsTheMeasuredRecipeAndItsOutcomeIsAlwaysLogged() {
        val vote = functionBody(cpp, "std::string applySustainedVoteLocked(")
        assertTrue(
            "the vote must leave DCVS ENABLED (`d.dcvsEnable = 1`) on a live line. dcvsEnable = 0 " +
                "is the burst recipe: it pins the clock, which is exactly what must not happen to " +
                "a config held for the length of a dictation session. Live dcvs lines: " +
                liveLines(vote, "dcvsEnable"),
            liveOffsets(vote, "d.dcvsEnable = 1;").isNotEmpty()
        )
        assertTrue(
            "the vote must request PERFORMANCE_MODE on a live line — DCVS still governs but ramps " +
                "eagerly, so a segment arriving after idle does not spend its first inference " +
                "climbing.",
            liveOffsets(vote, "POWERMODE_PERFORMANCE_MODE").isNotEmpty()
        )
        assertTrue(
            "the vote must target the TURBO corner (DCVS_VOLTAGE_VCORNER_TURBO) on live lines — " +
                "bus and core, target and max, four of them.",
            liveOffsets(vote, "DCVS_VOLTAGE_VCORNER_TURBO").size >= 4
        )
        assertTrue(
            "MAX_VOLTAGE_CORNER must appear NOWHERE in applySustainedVoteLocked. The top corner " +
                "is a burst affordance; TURBO is the highest corner a phone can actually hold, " +
                "and the +9.6% against burst is the accepted trade rather than a defect to tune " +
                "out.",
            !vote.contains("MAX_VOLTAGE_CORNER")
        )
        assertTrue(
            "RPC_POLLING_TIME must appear NOWHERE in applySustainedVoteLocked. Polling spins to " +
                "avoid interrupt latency and burns power continuously, process-wide.",
            !vote.contains("RPC_POLLING_TIME")
        )

        val arm = functionBody(cpp, "void armSustainedVoteLocked()")
        assertTrue(
            "armSustainedVoteLocked must emit the `vote:` result line on a live line, " +
                "unconditionally. Without it an unvoted Hexagon and slow silicon are the same " +
                "observation, which is what cost the spike run 7.",
            liveOffsets(arm, "LOGI(\"vote: %s\"").isNotEmpty()
        )

        val init = functionBody(cpp, "Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(")
        val loads = liveOffsets(init, "loadGraphSlot(")
        val armed = liveOffsets(init, "armSustainedVoteLocked()")
        assertTrue("nativeInit must load both graph slots on live lines", loads.size >= 2)
        assertTrue("nativeInit must arm the vote on a live line", armed.isNotEmpty())
        assertTrue(
            "armSustainedVoteLocked() (${armed.first()}) must come AFTER the last loadGraphSlot " +
                "(${loads.last()}). The spike acquired the perf infrastructure before " +
                "contextCreateFromBinary but did not VOTE until after it, so its 525 ms cold-load " +
                "figure is an unvoted one — arming earlier would make our cold load faster than " +
                "the number the plan quotes and silently invalidate it. A failed init also never " +
                "leaves a vote armed.",
            armed.first() > loads.last()
        )
    }

    /**
     * The vote is released FIRST in teardown, before the backend that issued it is freed.
     *
     * `destroyPowerConfigId` hands a handle back to the HTP perf infrastructure obtained from the
     * device/backend. Relocating that line below `backendFree` is a use-after-free of the power
     * config against a dead backend — a one-line move, no compiler signal, and a crash that only
     * ever reproduces on teardown of a session that actually got a vote.
     */
    @Test
    fun theVoteIsReleasedBeforeTheBackendThatIssuedItIsFreed() {
        val body = functionBody(cpp, "void releaseLocked()")
        val vote = liveOffsets(body, "releaseSustainedVoteLocked();")
        assertTrue(
            "releaseLocked must CALL releaseSustainedVoteLocked() on a live line. A session that " +
                "armed a vote and never released it holds a governor setting for the life of the " +
                "process, long after the tier it was for has been torn down.",
            vote.isNotEmpty()
        )
        val backend = liveOffsets(body, "backendFree(")
        assertTrue(
            "releaseLocked must free the backend on a live line",
            backend.isNotEmpty()
        )
        assertTrue(
            "releaseSustainedVoteLocked() (${vote.first()}) must precede backendFree() " +
                "(${backend.first()}). The vote is a session-scoped request held against that " +
                "backend; handing the config id back afterwards is handing it to a dead one.",
            vote.first() < backend.first()
        )
    }

    /**
     * Lesson 1. Without these two declarations, Android 12+ refuses to let the app's linker
     * namespace resolve `libcdsprpc.so` / `libadsprpc.so` from the vendor namespace, the HTP
     * backend's FastRPC transport never comes up, and the failure is SILENT — the NPU simply never
     * appears, which is indistinguishable from unsupported silicon.
     *
     * `required="false"` on both, deliberately: this APK installs on every device, and a required
     * native library that the vendor does not ship makes the app uninstallable there.
     */
    @Test
    fun manifestDeclaresFastRpcLibraries() {
        val manifest = source("src/main/AndroidManifest.xml")
        val appOpen = manifest.indexOf("<application")
        val appClose = manifest.indexOf("</application>")
        assertTrue(
            "AndroidManifest.xml must have an <application> element for the declarations to live in",
            appOpen in 0 until appClose
        )
        val elements = Regex("""<uses-native-library\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .toList()
        listOf("libcdsprpc.so", "libadsprpc.so").forEach { lib ->
            val element = elements.firstOrNull {
                Regex("""android:name\s*=\s*"${Regex.escape(lib)}"""").containsMatchIn(it.value)
            }
            assertTrue(
                "<uses-native-library android:name=\"$lib\" .../> is missing from " +
                    "AndroidManifest.xml. Android 12+ (targetSdk 31+) will not resolve a vendor " +
                    "native library the manifest does not name, so libQnnHtp.so's FastRPC " +
                    "transport fails to load and the HTP never comes up — with no exception, no " +
                    "crash and no log the app can see. It cost a device round trip on the spike. " +
                    "Declared uses-native-library entries found: " +
                    elements.map { it.value.replace("\n", " ") },
                element != null
            )
            assertTrue(
                "the $lib declaration must be inside <application>; a uses-native-library element " +
                    "placed at manifest top level is silently ignored by the platform.",
                element!!.range.first in appOpen until appClose
            )
            assertTrue(
                "$lib must be declared android:required=\"false\". This APK installs on every " +
                    "device — Tensor, Exynos, MediaTek, and every Snapdragon without an exposed " +
                    "cDSP — and required=\"true\" makes Play refuse the install outright on all of " +
                    "them to protect a tier they were never going to run. Found: " +
                    element.value.replace("\n", " "),
                Regex("""android:required\s*=\s*"false"""").containsMatchIn(element.value)
            )
        }
    }

    /**
     * Every header under `app/src/main/cpp/include/QNN` carries *"Confidential and Proprietary —
     * Qualcomm Technologies, Inc."*. Compiling against them is what they are shipped for;
     * redistributing them is not permitted, and this repo has a public remote. They are fetched by
     * `tools/fetch_qnn_headers.py` on demand and never committed — this is the entry that makes
     * "never" mechanical rather than a matter of remembering.
     */
    @Test
    fun gitignoreExcludesTheProprietaryQnnHeaderTree() {
        val ignore = source(".gitignore")
        val entry = "app/src/main/cpp/include/QNN/"
        assertTrue(
            "the root .gitignore must contain the line \"$entry\" (uncommented). Without it, the " +
                "first `git add -A` after a header fetch stages ~230 Qualcomm-proprietary headers " +
                "into a repo with a public remote — a licence violation that is trivial to commit " +
                "and impossible to un-publish. The trailing slash is deliberate: it matches the " +
                "directory and everything under it, and never a same-named file.",
            ignore.lineSequence().any { it.trim() == entry }
        )
    }
}
