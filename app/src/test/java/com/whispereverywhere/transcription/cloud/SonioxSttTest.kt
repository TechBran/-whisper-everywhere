package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SonioxSttTest {

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    private val fileOk = """{"id":"file-1"}"""
    private val createOk = """{"id":"job-1","status":"queued"}"""
    private val completed = """{"status":"completed"}"""
    private val transcriptOk = """{"tokens":[{"text":"hello "},{"text":"world"}]}"""

    /** A fake that walks the whole happy pipeline, dispatching by URL. */
    private fun happyFake() = FakeHttpTransport { url, _ ->
        when {
            url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
            url.endsWith("/transcript") -> HttpResult.Ok(200, transcriptOk)
            url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
            url.contains("/transcriptions/") -> HttpResult.Ok(200, completed) // poll
            else -> error("unexpected url $url")
        }
    }

    private fun soniox(fake: FakeHttpTransport) = SonioxStt(fake, "soniox-key", pollIntervalMs = 0L)

    @Test fun happy_path_concatenates_tokens_into_the_transcript() = runBlocking {
        assertEquals(SttResult.Text("hello world"), soniox(happyFake()).transcribe(pcm, null))
    }

    @Test fun the_upload_is_a_wav_container_in_the_file_part() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        assertEquals("file", fake.lastFilePart?.fieldName)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun every_call_sends_the_bearer_header_and_the_key_is_never_in_a_url() = runBlocking {
        val fake = happyFake()
        SonioxStt(fake, "soniox-secret", pollIntervalMs = 0L).transcribe(pcm, null)
        assertEquals("Bearer soniox-secret", fake.lastHeaders["Authorization"])
        assertFalse(fake.lastUrl!!.contains("soniox-secret"))
        fake.deletedUrls.forEach { assertFalse(it.contains("soniox-secret")) }
    }

    @Test fun create_sends_the_pinned_model_and_the_uploaded_file_id() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        val body = fake.lastJsonBody!! // last postJson body = the create call
        assertTrue(body.contains("stt-async-v5"))
        assertTrue(body.contains("file-1"))
    }

    @Test fun a_specific_language_becomes_a_hint_and_auto_omits_it() = runBlocking {
        val f1 = happyFake()
        SonioxStt(f1, "k", pollIntervalMs = 0L).transcribe(pcm, "fr")
        assertTrue(f1.lastJsonBody!!.contains("language_hints"))
        assertTrue(f1.lastJsonBody!!.contains("fr"))
        // Multilingual auto-detect is the default: null AND "auto" both omit the hint.
        val f2 = happyFake(); SonioxStt(f2, "k", pollIntervalMs = 0L).transcribe(pcm, null)
        assertFalse(f2.lastJsonBody!!.contains("language_hints"))
        val f3 = happyFake(); SonioxStt(f3, "k", pollIntervalMs = 0L).transcribe(pcm, "auto")
        assertFalse(f3.lastJsonBody!!.contains("language_hints"))
    }

    @Test fun happy_path_deletes_both_the_transcription_and_the_file() = runBlocking {
        val fake = happyFake()
        soniox(fake).transcribe(pcm, null)
        assertTrue(fake.deletedUrls.any { it.endsWith("/transcriptions/job-1") })
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_failed_create_still_deletes_the_uploaded_file() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.HttpError(400, "invalid_request")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertTrue("file must be cleaned up even though no job was created",
            fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_failed_job_returns_transient_and_deletes_both() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") ->
                    HttpResult.Ok(200, """{"status":"error","error_type":"internal_error"}""")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
        assertTrue(fake.deletedUrls.any { it.endsWith("/transcriptions/job-1") })
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_stuck_job_returns_transient_when_the_poll_budget_is_exhausted_and_cleans_up() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, """{"status":"processing"}""")
                else -> error("unexpected $url")
            }
        }
        val stt = SonioxStt(fake, "k", pollIntervalMs = 0L, maxPolls = 2)
        val r = stt.transcribe(pcm, null) as SttResult.Failed
        assertTrue("a stuck job must fall local, never hang", r.error is SttError.Transient)
        assertTrue(fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_cancellation_mid_poll_still_deletes_both_resources_off_the_cancellable_path() = runBlocking {
        // The privacy claim's load-bearing case: the user cancels a segment WHILE it is polling, and
        // the audio Soniox is already storing MUST still be deleted. The cleanup runs under
        // withContext(NonCancellable); `deleteSuspends` makes delete a cancellable point, so both
        // deletes are recorded ONLY because of that wrapper — a plain finally on the cancelled
        // coroutine would see the cancellation at the yield and skip them, leaking the audio.
        val created = CompletableDeferred<Unit>()
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                // Signal readiness on the CREATE: the coroutine then runs synchronously through
                // `transcriptionId = tid` into the poll's delay before the parent gets control, so
                // both resources provably exist when we cancel.
                url.endsWith("/transcriptions") -> { created.complete(Unit); HttpResult.Ok(201, createOk) }
                url.contains("/transcriptions/") -> HttpResult.Ok(200, """{"status":"processing"}""")
                else -> error("unexpected $url")
            }
        }
        fake.deleteSuspends = true
        // Long interval so the coroutine parks in the poll delay; cancellation interrupts it at once.
        val stt = SonioxStt(fake, "k", pollIntervalMs = 10_000L)
        val job = launch { stt.transcribe(pcm, null) }
        created.await()      // upload + create done -> file AND transcription exist server-side
        job.cancelAndJoin()  // cancel while parked mid-poll
        assertTrue("cancelled segment must still delete the transcription",
            fake.deletedUrls.any { it.endsWith("/transcriptions/job-1") })
        assertTrue("cancelled segment must still delete the uploaded file",
            fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_401_on_upload_is_fatal_invalid_key() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(401, "unauthenticated") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_401_with_a_balance_marker_is_out_of_credit_not_a_bad_key() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(401, """{"error_type":"insufficient_balance"}""") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_bad_key_body_that_merely_mentions_balance_is_still_invalid_key_not_out_of_credit() = runBlocking {
        // Marker-tightening (finding #9): the old broad markers ("balance"/"budget"/"exhausted")
        // mislabeled a plain bad-key 401 as an empty wallet whenever the body incidentally mentioned
        // an account "balance". Only the specific insufficient-balance class means no credit.
        val fake = FakeHttpTransport { _, _ ->
            HttpResult.HttpError(401, """{"error":"invalid api key — see your account balance page"}""")
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun the_poll_loop_stops_at_the_wall_clock_deadline_even_with_polls_remaining() = runBlocking {
        // The documented ~40 s bound made real (finding #9): with a huge maxPolls but a now() that
        // jumps past the wall-clock deadline after the first poll, the loop must fall Transient (->
        // local) rather than keep polling, and still clean up the stored file.
        var pollGets = 0
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> { pollGets++; HttpResult.Ok(200, """{"status":"processing"}""") }
                else -> error("unexpected $url")
            }
        }
        // now() calls, in order: deadline-calc (0 -> deadline 40_000), first while-check (10, inside),
        // second while-check (50_000, past the deadline -> exit).
        val times = ArrayDeque(listOf(0L, 10L, 50_000L))
        val stt = SonioxStt(
            fake, "k", pollIntervalMs = 0L, maxPolls = 100_000,
            maxPollWallClockMs = 40_000L, now = { times.removeFirstOrNull() ?: 60_000L },
        )
        val r = stt.transcribe(pcm, null) as SttResult.Failed
        assertTrue("past the deadline must fall Transient -> local", r.error is SttError.Transient)
        assertTrue("the wall clock bounded the loop far under maxPolls (got $pollGets)", pollGets <= 2)
        assertTrue("the stored file must still be cleaned up",
            fake.deletedUrls.any { it.endsWith("/files/file-1") })
    }

    @Test fun a_402_is_out_of_credit() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(402, "balance exhausted") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_404_on_create_is_model_unavailable() = runBlocking {
        // 404 on the CREATE call means the pinned model id is wrong/retired — a permanent config
        // fault, latched, not a per-segment hiccup.
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.HttpError(404, "model not found")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_404_on_poll_is_transient_not_model_unavailable() = runBlocking {
        // Same status code, different step: a missing file/transcription is odd server state, not a
        // model fault. Must NOT latch the provider off.
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.HttpError(404, "not found")
                else -> error("unexpected $url")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_400_is_a_bad_segment() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(400, "invalid_request") }
        assertEquals(SttError.BadSegment, (soniox(fake).transcribe(pcm, null) as SttResult.Failed).error)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(403, "") }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient_but_a_429_with_balance_is_out_of_credit() = runBlocking {
        val plain = FakeHttpTransport { _, _ -> HttpResult.HttpError(429, "limit_exceeded") }
        assertTrue((soniox(plain).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
        val quota = FakeHttpTransport { _, _ -> HttpResult.HttpError(429, "insufficient_balance") }
        val r = soniox(quota).transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_5xx_is_transient() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "upstream") }
        assertTrue((soniox(fake).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
    }

    @Test fun a_network_error_is_offline() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.NetworkError(IOException("no route")) }
        assertEquals(SttError.Offline, (soniox(fake).transcribe(pcm, null) as SttResult.Failed).error)
    }

    @Test fun an_unparseable_transcript_body_is_transient_not_silently_empty() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcript") -> HttpResult.Ok(200, "not json")
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, completed)
                else -> error("unexpected $url")
            }
        }
        assertTrue((soniox(fake).transcribe(pcm, null) as SttResult.Failed).error is SttError.Transient)
    }

    @Test fun an_unparseable_upload_id_falls_transient_and_leaks_the_file_it_cannot_delete() = runBlocking {
        // The most no-key-ship-aligned failure: upload returns 200 but the id is nested/renamed
        // (here blank), so it can NEVER be read — the stored file cannot be deleted. The pipeline
        // must stop right here (no create, no delete attempt) and fall local, not silently "".
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, """{"file_id":"renamed"}""") // id absent -> blank
                else -> error("must not reach $url after an unreadable upload id")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
        assertEquals("only the upload should have fired", 1, fake.callCount)
        assertTrue("no id was read, so nothing can be deleted", fake.deletedUrls.isEmpty())
    }

    @Test fun an_unparseable_create_id_falls_transient_but_still_deletes_the_uploaded_file() = runBlocking {
        // Create returns 20x with no readable id. A transcription created under an unreadable id
        // would leak, but the FILE id IS known — the finally must still clean it up.
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, """{"status":"queued"}""") // id absent -> blank
                else -> error("must not poll after an unreadable create id")
            }
        }
        val r = soniox(fake).transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
        assertTrue("the known file id must still be cleaned up",
            fake.deletedUrls.any { it.endsWith("/files/file-1") })
        assertTrue("no transcription id was read, so none is deleted",
            fake.deletedUrls.none { it.contains("/transcriptions/") })
    }

    @Test fun a_completed_job_with_no_tokens_is_a_legitimate_empty_transcript() = runBlocking {
        val fake = FakeHttpTransport { url, _ ->
            when {
                url.endsWith("/files") -> HttpResult.Ok(200, fileOk)
                url.endsWith("/transcript") -> HttpResult.Ok(200, """{"tokens":[]}""")
                url.endsWith("/transcriptions") -> HttpResult.Ok(201, createOk)
                url.contains("/transcriptions/") -> HttpResult.Ok(200, completed)
                else -> error("unexpected $url")
            }
        }
        assertEquals(SttResult.Text(""), soniox(fake).transcribe(pcm, null))
    }

    @Test fun oversized_audio_fails_locally_without_a_request() = runBlocking {
        val fake = happyFake()
        val huge = ByteArray(26 * 1024 * 1024)
        val r = SonioxStt(fake, "k", pollIntervalMs = 0L).transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals(0, fake.callCount)
        assertTrue(fake.deletedUrls.isEmpty())
    }
}
