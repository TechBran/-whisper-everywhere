package com.whispereverywhere.transcription.stream

/**
 * WHO SAID THIS LANGUAGE MAY BE PUBLISHED — the per-language clearance record, the switch that
 * authorises a production release, and the one gate that reads them (4.5.0 Task 5).
 *
 * The owner's decision, verbatim (2026-09-12):
 *
 * > *"Let's set up all 6 languages and before we publish to customers I will do the research and
 * > the email."*
 *
 * And the constraint he restated the same day, which is the reason this file is shaped the way it
 * is rather than the obvious way:
 *
 * > *"I still will need to be able to test on internal testing track before the legal stuff."*
 *
 * ### WHAT THIS IS NOT: a switch that removes a language from the build
 *
 * The obvious implementation — exclude an uncleared pack from `assetPacks` — is FORBIDDEN, and the
 * amendment of 2026-09-12 withdrew the earlier brief's offer of it by name. It would strip the
 * languages out of the exact artefact the owner needs: he is doing the research *because* he will
 * have tested the six on his own devices and will know which are worth a lawyer's time. A build
 * that shipped two languages to the internal track would answer the legal question by making the
 * product question unanswerable.
 *
 * So the clearance state reaches **none** of these, and `StreamingPackClearanceTest` proves the
 * negative by reading the sources rather than by asserting it in prose:
 *
 *  - `assetPacks` in `app/build.gradle.kts` (a flat literal of ten modules, pinned by
 *    `PreviewPackLayoutTest`),
 *  - the seven `preview_*` pack modules,
 *  - `tools/build_asset_packs.py`,
 *  - [StreamingPackCatalog.packs] — which is why the verdict is NOT a field on [StreamingPack],
 *  - `verifyPreviewPack` and the other bundle gates,
 *  - anything the app reads at runtime to decide what it may fetch, install or arm.
 *
 * **The app never asks whether a language is cleared. Only a human promoting a release does.** All
 * six languages are in the bundle, fetchable and armable on the internal track today, with five
 * clearances outstanding.
 *
 * ### THE TWO KEYS, and why one would not do
 *
 * | key | what it is | who moves it | when |
 * |---|---|---|---|
 * | [PackClearance.verdict] | the EVIDENCE — what was read, where, when, and what is still missing | whoever does the research | as answers arrive |
 * | [PRODUCTION_CLEARED] | the AUTHORISATION — "these languages may go to the public" | the owner, explicitly | at promotion time |
 *
 * A single key cannot express the owner's order. Evidence arriving does not publish anything: the
 * German author could confirm his licence tomorrow and it would still not be the owner's decision
 * to put German in front of customers. So the switch is a second, deliberate act — and
 * [state] refuses the reverse mistake, where the switch authorises a language whose evidence is
 * unfinished. That refusal is the whole gate.
 *
 * ### Where this is read
 *
 * Three places, all of them human: `StreamingPackClearanceTest` (which fails the build on a blank
 * verdict and on a switch that outran its evidence), `docs/LANGUAGE-CLEARANCE.md` (the checklist
 * the owner fills in, one section per outstanding language), and the acceptance sheet's promotion
 * gate (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`, §AL). Nothing else, ever.
 */
data class PackClearance(
    /**
     * The catalogue row this record is about — [StreamingPack.language], so the two cannot drift.
     * Note `zh`: the row the controller brief calls `zh-en` is SELECTED as Chinese and its language
     * code is `zh` (`StreamingPackCatalog.kt:1095`); the pack behind it is the bilingual export,
     * which is why its corpus question is its own and not a Chinese-only lane's — and, corrected
     * on 2026-09-13, why that question is an UNDISCLOSED corpus rather than WenetSpeech-L's terms.
     * See [PackClearanceRecord.ZH].
     */
    val language: String,
    /** Cleared for publication, or not — and, either way, the thing that makes it actionable. */
    val verdict: ClearanceVerdict,
    /**
     * The grant AS DECLARED upstream, in upstream's own spelling (`apache-2.0`, `mit`). Not a
     * conclusion about whether it is relied upon — that is [verdict]'s job.
     */
    val licence: String,
    /** The URL a reader can re-read the grant at. Held to `huggingface.co` by the test. */
    val readAt: String,
    /** The ISO date [readAt] was last read. A read nobody dated is a read nobody can re-do. */
    val readOn: String,
    /**
     * How the grant read at [readAt] connects to the bytes this app downloads. Three of the seven
     * rows need this said out loud: `ru`'s mirror carries no tag and is discharged
     * cryptographically, `de`'s grant is in a blob the platform cannot parse, and `zh`'s row is the
     * 50 MB V1 export rather than its 198 MB sibling.
     */
    val provenance: String,
    /** Each training corpus and its terms, one string each — or the fact that none is named. */
    val corpora: List<String>,
    /**
     * The 40-hex commit [StreamingPack.baseUrl] downloads from, as it stood when the evidence was
     * read. **A clearance is granted over BYTES, not over a repository name**: re-pin a row and
     * every licence read, corpus claim and grant in this record is about bytes that are no longer
     * shipped. The test holds this equal to the catalogue's own pin, and the repo has one
     * re-upload incident behind it already (2026-09-08, the voice archive) to explain why.
     */
    val pinnedCommit: String,
)

/** Who is able to answer an outstanding clearance question — nobody else can close that row. */
enum class ClearanceAnswerer {
    /**
     * The owner's own risk call. Used where no third party is obliged to answer and no document
     * would settle it: `ru`, whose corpus is not named at all, so the risk can only be accepted or
     * refused rather than sized — and, since the corrections of 2026-09-13, `zh` for exactly the
     * same reason and `ko` for a scope residual its publisher does not address either way.
     */
    OWNER,

    /** The uploader of the weights. One email, asking them to confirm what they already declared. */
    UPSTREAM_AUTHOR,

    /** A written legal opinion. Not a one-email row: `id`. */
    COUNSEL,
}

/** Whether one language may be published to the public, and the evidence or the gap behind it. */
sealed interface ClearanceVerdict {
    /**
     * **Cleared for publication.** Two rows only on this branch, and the controller brief's global
     * constraint says so: *"No pack may be marked cleared on this branch except `en` and `fr` — the
     * rest is the owner's to grant, and a subagent inventing a clearance is the one unrecoverable
     * error in this build."* `StreamingPackClearanceTest.onlyEnglishAndFrenchAreClearedOnThisBranch`
     * is that sentence with teeth.
     *
     * @property grantedBy who granted it. A clearance with no grantor is a clearance nobody gave.
     * @property because the reason, short enough to read at promotion time.
     * @property grantedOn the ISO date of the grant.
     */
    data class Cleared(val grantedBy: String, val because: String, val grantedOn: String) : ClearanceVerdict

    /**
     * **Not cleared** — and saying what is missing, because "not cleared" on its own leaves the
     * owner nothing to do.
     *
     * @property question the thing that is actually unknown, phrased as the question to answer.
     * @property action what closes it, and what it costs.
     * @property answerer who can answer it — see [ClearanceAnswerer].
     */
    data class Outstanding(
        val question: String,
        val action: String,
        val answerer: ClearanceAnswerer,
    ) : ClearanceVerdict
}

/**
 * The answer to *"may this release be promoted to production?"* — four states, in the order
 * [PackClearanceRecord.state] checks them. Two are defects and two are not.
 */
sealed interface PromotionState {
    /**
     * Every shipped language is cleared AND authorised. **Unreachable on this branch**, and that is
     * the point: it becomes reachable one language at a time, as the owner's research lands.
     */
    data object Promotable : PromotionState

    /**
     * **The normal state, and NOT a failure.** Production promotion is not authorised for the
     * languages named — either their evidence is outstanding or the owner has not put them in the
     * switch. The internal track is entirely unaffected: every one of these languages is in the
     * bundle, fetchable and armable.
     *
     * @property missing the shipped languages the switch does not authorise, in catalogue order.
     */
    data class Withheld(val missing: List<String>) : PromotionState

    /**
     * **A defect.** The switch authorises a language whose record does not clear it — or names a
     * language that is not in the catalogue at all, which is a stale authorisation left behind by a
     * removed row. This is the state the gate exists to produce: it is what "promoting to
     * production with the legal work unfinished" looks like from inside the build.
     *
     * @property languages the switch entries with no Cleared record behind them, in switch order.
     */
    data class Overreached(val languages: List<String>) : PromotionState

    /**
     * **A worse defect, and it outranks the other three.** A language the app can fetch has no
     * clearance record at all, so the switch cannot be checked against evidence that does not
     * exist. Reported ahead of [Withheld] so an incomplete record can never read as a reassuring
     * "not yet authorised".
     *
     * @property languages the shipped languages with no record, in catalogue order.
     */
    data class Unrecorded(val languages: List<String>) : PromotionState
}

/**
 * THE RECORD AND THE SWITCH. Every string below was read from source on **2026-09-12** — the
 * licence of all seven repositories re-read live through `api/models/<repo>` that day, and `de`'s
 * and `ko`'s and `fr`'s README front matter fetched at the pinned commit — because a licence typed
 * from a document is a licence nobody checked.
 *
 * The corpus terms are the qualification table's
 * (`docs/superpowers/research/2026-09-11-language-qualification-table.md`), which is the authority
 * for them: its corpus reads include the AI-Hub 데이터 이용정책 clause by clause in Korean,
 * YODAS2's shard list and hours table, and nine Mandarin corpus licences from their own
 * publishers. They are quoted here, not re-derived.
 *
 * **WITH ONE EXCEPTION, and it is recorded rather than quietly applied.** On 2026-09-13 the
 * Chinese row's corpus was CORRECTED against that table: the table asserts WenetSpeech-L as this
 * checkpoint's training corpus, and that is not established for the bytes this catalogue
 * downloads (see [ZH]). Where this record and the table disagree about `zh`'s corpus, **this
 * record is the authority and the table carries the correction in its own errata**. Nothing else
 * in the table was disturbed.
 *
 * One corroboration worth recording, because it is what makes [PackClearance.pinnedCommit]
 * checkable rather than decorative: on 2026-09-12 the HF API's `sha` — the current `main` — still
 * equalled the pinned commit for **all seven** repositories, so nothing has been re-uploaded under
 * any of these grants since the table read them.
 */
object PackClearanceRecord {

    /**
     * **THE SWITCH.** The languages the owner has authorised for publication to the public. One
     * explicit, committed set; a production promotion requires it to cover every shipped language.
     *
     * Flipping a language in here is a DECISION, not a consequence: [state] refuses any entry whose
     * record is not [ClearanceVerdict.Cleared], so the switch can never outrun the evidence, and
     * `docs/LANGUAGE-CLEARANCE.md` names the three edits a real clearance takes.
     *
     * **This set does not reach the build, the catalogue or the app.** It has exactly two readers:
     * the test suite and the two committed documents. Nothing about the internal track consults it.
     */
    val PRODUCTION_CLEARED: Set<String> = setOf("en", "fr")

    /** English — Apache-2.0, in the built product since 4.4.0. */
    val EN = PackClearance(
        language = "en",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "the owner, by shipping it — preview_en has been in the built product " +
                "since 4.4.0 — and restated as cleared in the controller brief of 2026-09-12",
            because = "apache-2.0 on the export, read in cardData.license and the " +
                "license:apache-2.0 tag; a LibriSpeech (CC BY 4.0) corpus with no third-party " +
                "agreement anywhere in the lineage",
            grantedOn = "2026-09-12",
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
        readOn = "2026-09-12",
        provenance = "the repository the four files are downloaded from carries the grant itself; " +
            "re-read live on 2026-09-12 (cardData.license = apache-2.0, tag license:apache-2.0, " +
            "the top-level license key absent as it is on five of these seven repos)",
        corpora = listOf(
            "LibriSpeech (CC BY 4.0) — the icefall egs/librispeech recipe this export is named " +
                "for. No agreement, no non-commercial term, no attribution obligation beyond the " +
                "notice this app already carries.",
        ),
        pinnedCommit = "672fbf1b30579d6585301139bb363f42a0ad4a24",
    )

    /** French — Apache-2.0, read twice, and the one row whose WER must not be copied forward. */
    val FR = PackClearance(
        language = "fr",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "the controller brief of 2026-09-12, on the qualification table's two " +
                "independent reads of the grant",
            because = "apache-2.0 read in the repo's own non-LFS front matter AND " +
                "platform-surfaced; CommonVoice 12.0 (CC0) on a LibriSpeech (CC BY 4.0) " +
                "pretrain; and GigaSpeech — the one encumbered corpus in this lineage's " +
                "neighbourhood — is NOT in the exported checkpoint",
            grantedOn = "2026-09-12",
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14",
        readOn = "2026-09-12",
        provenance = "read both ways, because either alone is a trap: the repo's 204-byte " +
            "non-LFS README front matter says license: apache-2.0 (fetched at the pinned commit " +
            "on 2026-09-12), and the platform surfaces it through cardData.license and a " +
            "license:apache-2.0 tag — while the API's TOP-LEVEL license key is absent, so a sweep " +
            "reading model[\"license\"] reports \"Not specified\" for a perfectly readable grant",
        corpora = listOf(
            "CommonVoice 12.0 fr (CC0) — the fine-tuning corpus.",
            "LibriSpeech (CC BY 4.0) — the pretrain.",
            "GigaSpeech is NOT in the exported lineage, and that is a clearance fact rather than " +
                "an accuracy one: 9.95 WER belongs to the epoch-30 avg-9 checkpoint trained WITH " +
                "GigaSpeech, whose gated terms are non-commercial-only. This export is epoch 29 " +
                "avg 9 (the repo's own export-onnx-stateless7-streaming.sh runs --epoch 29 " +
                "--avg 1 --use-averaged-model 0) and its WER is 10.57. Citing 9.95 would import " +
                "an encumbrance these bytes do not carry.",
        ),
        pinnedCommit = "3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73",
    )

    /** German — the grant is real text in the repo that the platform cannot read. ONE EMAIL. */
    val DE = PackClearance(
        language = "de",
        verdict = ClearanceVerdict.Outstanding(
            question = "Does the uploader confirm the apache-2.0 he declared? The grant is real " +
                "text in the repository and the platform cannot read it — the API returns " +
                "license: null AND cardData: null, because that README is an Xet/LFS blob — so " +
                "there is nothing platform-surfaced for a licensee to rely on.",
            action = "ONE EMAIL to the uploader (daniel-dona) asking him to confirm the " +
                "apache-2.0 in his README, and ideally to add a plain README or a LICENSE file so " +
                "the platform surfaces it. The outstanding item is a CONFIRMATION of a grant that " +
                "is already declared, not a request to create one.",
            answerer = ClearanceAnswerer.UPSTREAM_AUTHOR,
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de",
        readOn = "2026-09-12",
        provenance = "read directly at the pinned commit on 2026-09-12: a 180-byte README whose " +
            "front matter says license: apache-2.0 and datasets: " +
            "mozilla-foundation/common_voice_17_0 (fetched and counted here, 180 B exactly). Read " +
            "the other way the same day: the API reports license absent AND cardData: null, and " +
            "the tag list is EMPTY. That asymmetry IS the outstanding item.",
        corpora = listOf(
            "CommonVoice 17.0 de — declared in the same unparsed front matter. CommonVoice " +
                "carries no third-party agreement, which is why this row is one email rather than " +
                "counsel.",
        ),
        pinnedCommit = "322557b0f88fc5a9823bc71027d4160f0c7612cc",
    )

    /** Russian — the strongest licence chain of the five, and no corpus named at all. */
    val RU = PackClearance(
        language = "ru",
        verdict = ClearanceVerdict.Outstanding(
            question = "What was it trained on? No corpus is named anywhere — the card says only " +
                "\"trained with k2-fsa/icefall on Russian data\" — so the risk cannot be SIZED, " +
                "only accepted or refused.",
            action = "The OWNER's own risk call. The apache-2.0 grant on the artefact is what a " +
                "licensee relies on, and the qualification table files this as nice-to-have " +
                "rather than gating (its ruling O4). One optional email to alphacep could name " +
                "the corpus; nobody is obliged to answer it, and no document will arrive on its " +
                "own. This row is outstanding because nobody has decided it, not because anybody " +
                "is working on it.",
            answerer = ClearanceAnswerer.OWNER,
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/alphacep/vosk-model-small-streaming-ru",
        readOn = "2026-09-12",
        provenance = "the mirror this row downloads (csukuangfj/…-small-ru-vosk-int8-2025-08-16 " +
            "at 31fa603e) carries NO licence of its own — API license absent, cardData null, " +
            "re-read 2026-09-12 — and the grant above is on the UPSTREAM it mirrors, which the " +
            "platform surfaces (cardData.license = apache-2.0, tag license:apache-2.0, also " +
            "re-read that day). The gap is discharged cryptographically rather than by trust: all " +
            "four pack files are byte-identical to the tagged upstream (three LFS oids plus a " +
            "sha256 computed on tokens.txt). That is a STRONGER position than German's, whose " +
            "grant the platform cannot read at all.",
        corpora = listOf(
            "UNDISCLOSED. No corpus is named on the mirror, on the upstream, or in the card. " +
                "There is therefore nothing whose terms could be read — which is the whole of the " +
                "outstanding question.",
        ),
        pinnedCommit = "31fa603e4f31279c6e1f7600fed13dc4312663ab",
    )

    /** Indonesian — MIT weights over a YouTube-derived corpus. COUNSEL, four questions. */
    val ID = PackClearance(
        language = "id",
        verdict = ClearanceVerdict.Outstanding(
            question = "Four, all about YODAS2 (qualification table C3): does training on a CC " +
                "BY 3.0 corpus create an attribution obligation on the WEIGHTS; can a " +
                "collection-level credit discharge it when the dataset removed the per-author " +
                "handle; does the uploader's grant reach the 85.6% of label hours that are " +
                "auto-captions; and which CC licence is librivox-indonesia, which is tagged only " +
                "\"cc\" with no version?",
            action = "COUNSEL — four questions, costed at ~0.5 d plus one credits line. The " +
                "licence CLASS is commercially fine (CC BY 3.0: no non-commercial term, no " +
                "share-alike); what needs an opinion is the attribution chain two hops " +
                "downstream.",
            answerer = ClearanceAnswerer.COUNSEL,
        ),
        licence = "mit",
        readAt = "https://huggingface.co/spacewave/sherpa-onnx-streaming-zipformer2-id",
        readOn = "2026-09-12",
        provenance = "MIT read in the repo's 902-byte non-LFS front matter and platform-surfaced " +
            "via cardData.license (re-read live 2026-09-12: cardData.license = mit, tag " +
            "license:mit) — note the TOP-LEVEL license key is absent, so a shallow read reports " +
            "\"Not specified\": the German trap in a second costume, by key naming rather than " +
            "Xet storage. The uploader is the exporter and the grant is on the repository the " +
            "four files come from.",
        corpora = listOf(
            "espnet/yodas2 (CC BY 3.0, YouTube-derived) — declared on the card and re-read in " +
                "the dataset tags on 2026-09-12. 85.6% of the Indonesian label hours are " +
                "AUTO-captions (8,463.61 of 9,883.70 h), and the dataset's video_id is " +
                "deliberately NOT YouTube's, so per-author attribution is structurally " +
                "unsatisfiable. Chain of title is a search flag that \"should (mostly)\" hold, " +
                "extrapolated per channel, backed by a takedown mailbox.",
            "indonesian-nlp/librivox-indonesia — tagged only \"cc\", with no version. Which " +
                "licence that is, is question four.",
            "mozilla-foundation/common_voice_17_0 (CC0) and google/fleurs (CC BY 4.0) — both " +
                "declared on the card, and neither is a problem.",
        ),
        pinnedCommit = "4e5a13cbe3e9cd4e3775447d86178ef51759096f",
    )

    /**
     * Korean — the cleanest licence cell in the survey, over a corpus whose rights vest in a state
     * agency that has published what may be done with a model trained on it.
     *
     * **CORRECTED 2026-09-13.** This row used to stand on the AI-Hub 데이터 이용정책 — nationals
     * only may apply, a separate agreement for a party outside Korea, a separate agreement for
     * export — and concluded two counsel questions plus *"we carry that risk"*. **Every one of
     * those clauses governs ACCESS TO THE DATA**, which this project never sought, never obtained
     * and does not hold. The clause that governs a distributor of someone else's weights is in
     * AI-Hub's published FAQ, and it is a grant with a price: commercial distribution of secondary
     * works, on condition that the dataset's official name and AI-Hub are cited as the source. So
     * the licences-page credit is not a 0.1 d courtesy on this row — **it is the condition, and
     * Korean ships only if it ships.**
     */
    val KO = PackClearance(
        language = "ko",
        verdict = ClearanceVerdict.Outstanding(
            question = "One, and it is a residual rather than a question anybody is obliged to " +
                "answer: AI-Hub's published FAQ grants commercial use, sale and distribution of " +
                "secondary works such as AI models trained on AI-Hub data, with attribution, and " +
                "prohibits redistributing the ORIGINAL DATA — which this app never possesses. " +
                "What the page does not address, either way, is whether that grant reaches a " +
                "party OUTSIDE Korea. There is nothing further to read; the residual can only be " +
                "accepted or refused.",
            action = "The OWNER's own risk call on that scope residual. CORRECTED 2026-09-13: the " +
                "earlier framing — two counsel questions built on the 데이터 이용정책's " +
                "nationals-only, overseas-agreement and export-agreement clauses — is WITHDRAWN, " +
                "because those clauses govern access to the DATA and we never applied for it. " +
                "The FAQ's attribution condition is NOT outstanding and is not optional: the " +
                "KsponSpeech / AI-Hub (aihub.or.kr) / NIA credit is on the licences screen " +
                "(app/src/main/assets/oss_licenses.html) and is what the grant is traded for. " +
                "Also recorded there, and not to be undone: the canary clip for this language is " +
                "FLEURS and NOT the k2-fsa mirror's test_wavs, which are AI-Hub audio.",
            answerer = ClearanceAnswerer.OWNER,
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-72m",
        readOn = "2026-09-12",
        provenance = "the cleanest licence cell in the survey, read twice and re-read here on " +
            "2026-09-12: a 7,926-byte PLAIN-blob README whose front matter says license: " +
            "apache-2.0 and whose own License section says \"Apache 2.0.\", and the platform " +
            "surfaces the same through cardData.license and the license:apache-2.0 tag. The " +
            "bytes are corroborated twice over — the repo ships its own SHA256SUMS, and all four " +
            "digests agree with the tree API and with a local hash.",
        corpora = listOf(
            "KsponSpeech ~1,000 h = AI-Hub dataset 123 (NIA / 한국지능정보사회진흥원). Named in " +
                "the README's own data table and linked as aihub.or.kr dataSetSn=123 — both " +
                "re-read at the pinned commit on 2026-09-12. That official name, and AI-Hub " +
                "(aihub.or.kr) as its source, are the attribution the grant below is traded for, " +
                "and they are on the licences screen.",
            "THE GRANT WE ACTUALLY RELY ON — AI-Hub's published FAQ " +
                "(https://aihub.or.kr/aihubnews/faq/list.do): secondary works such as AI models, " +
                "services and research outputs developed by using AI-Hub data FOR TRAINING may be " +
                "freely used, or sold and distributed, for commercial and non-commercial " +
                "purposes — provided the dataset's official name and AI Hub (aihub.or.kr) are " +
                "cited as the source. And what it prohibits is providing or distributing the " +
                "ORIGINAL DATA itself to a third party: we never possess or ship KsponSpeech, " +
                "only weights derived from it by somebody who did.",
            "RESIDUAL — OVERSEAS SCOPE. The FAQ does not address a party outside Korea either " +
                "way: it neither extends the secondary-works grant to one nor withholds it. " +
                "Nothing further is published, so this can be accepted or refused but not " +
                "researched, and whichever it is must be recorded as that rather than as closed. " +
                "A Korean-language clarification request to AI-Hub is optional extra evidence, " +
                "not a prerequisite.",
            "Corrected 2026-09-13: this record previously stood on the AI-Hub 데이터 이용정책 — " +
                "\"※ 내국인만 데이터 신청이 가능합니다\", a separate agreement for a party OUTSIDE " +
                "Korea, a separate agreement for EXPORT, use \"only for training AI learning " +
                "models\", no transfer and no sale. Those clauses govern ACCESS TO THE DATA, which " +
                "this project never applied for, never received and does not hold. They are kept " +
                "here as the access policy they are, and they are not terms on these weights.",
        ),
        pinnedCommit = "db24b58d22736349eaeb34cc181ad0f3debf9903",
    )

    /**
     * Chinese — the bilingual `zh-en` export, selected as `zh`. The only row in the catalogue that
     * puts anything on the strip when an English speaker talks mid-Chinese.
     *
     * **CORRECTED 2026-09-13, and this is the one row in the record whose FACTS changed rather
     * than its verdict.** It used to say the checkpoint was trained on WenetSpeech-L, 12,000 h, and
     * that the corpus publisher's non-commercial term made this *"a NAMED restriction … a worse
     * position than an unnamed unknown"*. That framing made `zh` the weakest row in the survey and
     * sent it to counsel, and **it was not established**: the shipped mirror's own environment dump
     * reads `training_subset: 'mix'`, the official sherpa-onnx documentation describes an internal
     * corpus, and the `12k_hour` string is on the FORK PARENT's card. The parent's disclosure is
     * lineage, not a disclosure about these bytes, so the corpus reads UNDISCLOSED and the
     * non-commercial claim is withdrawn. `StreamingPackClearanceTest` holds both halves, including
     * that no row converts an evaluation set into a training set.
     */
    val ZH = PackClearance(
        language = "zh",
        verdict = ClearanceVerdict.Outstanding(
            question = "What was it trained on? Nothing in this repository names a corpus: the " +
                "card's own environment dump reads training_subset: 'mix' and the official " +
                "sherpa-onnx documentation describes an internal corpus. So this is an " +
                "apache-2.0 grant over an undisclosed corpus — the same shape as Russian's, and " +
                "the risk can only be accepted or refused rather than sized.",
            action = "The OWNER's own risk call, on the same terms as Russian. CORRECTED " +
                "2026-09-13: the earlier framing — a WenetSpeech-L corpus with a non-commercial " +
                "term on the card, which made this the weakest row in the survey and sent it to " +
                "counsel as qualification-table C1 — is WITHDRAWN as unestablished, because the " +
                "12k_hour string belongs to the fork parent. What is left is an undisclosed " +
                "corpus, which no written opinion can size either.",
            answerer = ClearanceAnswerer.OWNER,
        ),
        licence = "apache-2.0",
        readAt = "https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t",
        readOn = "2026-09-12",
        provenance = "apache-2.0 read in the front matter AND platform-surfaced on every link of " +
            "the chain (re-read live 2026-09-12: cardData.license = apache-2.0, tag " +
            "license:apache-2.0, top-level license key absent). The row is the 50 MB V1 export " +
            "and NOT its 198 MB sibling — byte-identical tokens.txt, a different encoder — so the " +
            "grant and the corpus below are read on the repository this catalogue actually " +
            "downloads from.",
        corpora = listOf(
            "UNDISCLOSED. Nothing in the repository these four files are downloaded from names a " +
                "training corpus. Its own 4,115-byte card carries an environment dump reading " +
                "training_subset: 'mix', and the official sherpa-onnx documentation describes an " +
                "internal corpus. Corrected 2026-09-13: this line previously asserted " +
                "WenetSpeech-L, 12,000 h, and that was never established for these bytes.",
            "LINEAGE, not corpus. The card says \"Forked from " +
                "https://huggingface.co/pfluo/k2fsa-zipformer-chinese-english-mixed\", and it is " +
                "the FORK PARENT's card — not this one — that publishes " +
                "training_subset: '12k_hour'. That is recorded here as lineage because a parent's " +
                "disclosure is not a disclosure about the bytes this catalogue downloads, the " +
                "same distinction Russian's row makes about its untagged mirror. The withdrawn " +
                "claim was that a corpus term travelled with it.",
        ),
        pinnedCommit = "e2382758de9a0219b4efe682b95af30b399db3b8",
    )

    /**
     * The record — one row per catalogue row, in the catalogue's own order. The test holds this
     * one-to-one against [StreamingPackCatalog.packs] in BOTH directions: a seventh language with
     * no verdict fails the build, and so does a verdict for a language that no longer ships.
     */
    val RECORD: List<PackClearance> = listOf(EN, FR, DE, RU, ID, KO, ZH)

    /** The record for one language, or null — which [state] reports as [PromotionState.Unrecorded]. */
    fun forLanguage(code: String): PackClearance? = RECORD.firstOrNull { it.language == code }

    /**
     * **THE GATE.** Whether a release carrying [languages] may be promoted to production, given
     * the [authorised] switch and the [record].
     *
     * Pure, total, and taking all three inputs as parameters rather than reading the committed ones
     * — so the suite can exercise every state, including the [PromotionState.Promotable] cell that
     * this branch's own values can never reach. A gate whose only tested value is `false` is not a
     * gate that has been tested.
     *
     * The order of the checks is the order of severity, and it matters: an incomplete record is
     * reported ahead of an unauthorised language, so a missing verdict can never read as a
     * reassuring "not yet authorised".
     */
    fun state(
        authorised: Set<String>,
        languages: List<String>,
        record: List<PackClearance> = RECORD,
    ): PromotionState {
        val unrecorded = languages.filter { language -> record.none { it.language == language } }
        if (unrecorded.isNotEmpty()) return PromotionState.Unrecorded(unrecorded)

        val cleared = record
            .filter { it.verdict is ClearanceVerdict.Cleared }
            .map { it.language }
            .toSet()
        // In the switch's own order, because the message names entries the reader has to go delete.
        val overreached = authorised.filter { it !in cleared || it !in languages }
        if (overreached.isNotEmpty()) return PromotionState.Overreached(overreached)

        // In the catalogue's order, because the message is a census of what is still outstanding.
        val missing = languages.filterNot { it in authorised }
        return if (missing.isEmpty()) PromotionState.Promotable else PromotionState.Withheld(missing)
    }

    /**
     * [state] over the committed switch and the committed record — the one call a release decision
     * makes, and the one the acceptance sheet's promotion gate quotes. Today, over the seven
     * shipped languages, it answers `Withheld([de, ru, id, ko, zh])`.
     */
    fun stateOfRecord(languages: List<String>): PromotionState =
        state(PRODUCTION_CLEARED, languages, RECORD)
}
