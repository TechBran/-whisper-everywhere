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
 * ### HE DID THE RESEARCH. The decision of 2026-09-13, and what it is
 *
 * He did it himself, in a separate session, and issued a formal handoff dated **2026-09-13
 * accepting the reviewed licensing basis for all seven preview packs**. [PRODUCTION_CLEARED] now
 * names all seven, and the five verdicts that were outstanding are [ClearanceVerdict.Cleared] with
 * **Brandon Slacum** as their grantor.
 *
 * **What that decision IS: the owner's acceptance of a reviewed basis, per row, dated.** What it is
 * **NOT**, and the record must never be readable as any of these:
 *
 *  - a permission from a model author — the German uploader was never written to and never replied;
 *  - a reply from NIA or AI-Hub — nothing was ever sent to them; the Korean row rests on AI-Hub's
 *    own **published FAQ**, which anyone can read;
 *  - a legal certification — no counsel opinion was sought for `id`, `ko` or `zh`, and his decision
 *    records that one is not a prerequisite for these unchanged packs;
 *  - Google Play approval — Play reviews an app, not a corpus;
 *  - a numeric confidence. His informal figure for his own research is not a number this record
 *    carries, and `StreamingPackClearanceTest.noClearanceClaimsAnApprovalNobodyGave` fails the
 *    build on a percentage in a cleared verdict.
 *
 * **And where an uncertainty remains it is recorded as ACCEPTED, never as resolved.** ALL FIVE
 * rows carry one and name it: German's declaration is unwitnessed by its author, Russian's and
 * Chinese's corpora are undisclosed, Indonesian's YODAS2 attribution chain is unanswered, and
 * Korean's FAQ does not address a party outside Korea either way. An accepted risk is still a risk;
 * the value of this record at promotion time is that it says which is which.
 *
 * **The decision came with a condition, and it is not a caveat.** Apache-2.0 §4 and MIT are trades,
 * and the AI-Hub FAQ grants commercial distribution of derived models *only with attribution*. The
 * notices those licences ask for ship in the base app's own assets
 * (`app/src/main/assets/oss_licenses.html`), and **Korean ships only if its acknowledgement ships
 * with it**.
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
 * seven languages were in the bundle, fetchable and armable on the internal track, throughout the
 * period five clearances were outstanding — which is what made the owner's research possible on
 * packs he had heard working. Nothing about that changed when the switch opened, and nothing about
 * it may change if a clearance is ever withdrawn.
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
 * verdict, on a switch that outran its evidence, on an eighth language inheriting the switch, and
 * on a decision written up as somebody else's approval), `docs/LANGUAGE-CLEARANCE.md` (the
 * checklist the owner fills in, one section per language), and the acceptance sheet's promotion
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

/**
 * Who is able to answer an outstanding clearance question — nobody else can close that row.
 *
 * **No row is outstanding since 2026-09-13**, so nothing in [PackClearanceRecord] carries one of
 * these today. The enum stays because a clearance can be WITHDRAWN and an eighth language can
 * arrive, and because the checklist's refusal and withdrawal paths are written in its terms: a
 * withdrawn row is an outstanding row, and an outstanding row that does not say who can close it
 * leaves the owner nothing to do.
 */
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
     * **Cleared for publication.** All seven rows since 2026-09-13 — `en` and `fr` on the
     * controller's reading of the qualification table the day before, and the other five by the
     * owner's own decision.
     *
     * The rule that made this expensive to forge has not moved: a clearance costs three deliberate
     * edits, the third of them in
     * `StreamingPackClearanceTest.allSevenAreClearedAndFiveAreTheOwnersDecisionOf20260913`, under a
     * docblock explaining why a subagent must not write it. **An eighth language does not arrive
     * cleared because this list is full.**
     *
     * @property grantedBy who granted it, by name, and for an owner decision the date as well. A
     *   clearance with no grantor is a clearance nobody gave, and "the owner" is not a name. It may
     *   not describe itself as an approval, a permission, a certification or a store decision —
     *   `noClearanceClaimsAnApprovalNobodyGave` fails the build on the vocabulary of one.
     * @property because the reason, short enough to read at promotion time, per row, and naming
     *   the licence that row actually declares. Where an uncertainty remains, this is where it is
     *   recorded as **accepted** rather than resolved.
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
     * Every shipped language is cleared AND authorised. **Reached on 2026-09-13**, when the
     * owner's research landed for the last five at once. It was unreachable for the whole life of
     * the branch before that, and it becomes unreachable again the moment an eighth language ships
     * without a record — which is the property that makes it worth reporting at all.
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
 *
 * **2026-09-13 changed the VERDICTS and, on two rows, the FACTS. It changed no artefact.** The
 * owner's decision cleared `de`, `ru`, `id`, `ko` and `zh`; the same day the Chinese corpus claim
 * and the Korean licensing position were corrected (see [ZH] and [KO]). Every `licence`, `readAt`,
 * `readOn`, `provenance` and `pinnedCommit` in this record is the read of 2026-09-12, unchanged —
 * **a decision is granted over the bytes the evidence was read at, and those bytes did not move.**
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
     *
     * **It names all seven since 2026-09-13** — `en` and `fr` from the day before, and `de`, `ru`,
     * `id`, `ko` and `zh` by the owner's decision of that date. An EIGHTH language does not inherit
     * it: `StreamingPackClearanceTest` fails the build on a shipped language with no record even
     * when this set names it, which is the one thing an "everything" set has to keep being able to
     * say no to.
     */
    val PRODUCTION_CLEARED: Set<String> = setOf("en", "fr", "de", "ru", "id", "ko", "zh")

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

    /**
     * German — the grant is real text in the repo that the platform cannot read, and the owner
     * accepted reliance on it rather than waiting for the one email nobody was obliged to answer.
     */
    val DE = PackClearance(
        language = "de",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "Brandon Slacum, decision owner, 2026-09-13 — his own licensing research, " +
                "issued as a formal handoff and recorded here as HIS acceptance of a reviewed " +
                "basis. Not a grant from the uploader: daniel-dona was never written to and never " +
                "wrote back, and the earlier one-email hold is retired rather than satisfied",
            because = "the README at the pinned commit declares license: apache-2.0 and datasets: " +
                "mozilla-foundation/common_voice_17_0, and that declaration is the entire file — " +
                "180 bytes, re-fetched independently by the controller at sha256 " +
                "39b7a8b94cf14be24b5a62271fa033d6493f1c1e423a09f8fa18ec26c60dc15d. A grant the " +
                "platform cannot parse is still a grant, and a null cardData is a broken renderer " +
                "rather than absent permission. What the owner ACCEPTED with it: that the " +
                "declaration is unwitnessed by its author, and that a corpus named in one line of " +
                "front matter is a thin training disclosure. Both remain recorded, accepted, not " +
                "closed",
            grantedOn = "2026-09-13",
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

    /**
     * Russian — the strongest licence chain of the five the owner decided, and no corpus named at
     * all. The row that was outstanding because nobody had decided it; now decided.
     */
    val RU = PackClearance(
        language = "ru",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "Brandon Slacum, decision owner, 2026-09-13 — the row this record always " +
                "said was his alone to settle, since no third party is obliged to answer it and " +
                "no document would arrive on its own",
            because = "the apache-2.0 grant on the tagged upstream is what a licensee relies on, " +
                "and this row's four files are byte-identical to that upstream — three LFS oids " +
                "plus a sha256 computed on tokens.txt — so the untagged mirror the bytes come " +
                "from is discharged cryptographically rather than by trust. The corpus is still " +
                "UNDISCLOSED: the card says only \"trained with k2-fsa/icefall on Russian data\", " +
                "so there is nothing whose terms could be read. The owner ACCEPTED that unsized " +
                "risk, which is the only thing that can be done with a risk nobody can size; the " +
                "optional email to alphacep was not sent and is not a condition",
            grantedOn = "2026-09-13",
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
                "There is therefore nothing whose terms could be read — which was the whole of " +
                "the question the owner then decided, by accepting the unsized risk rather than " +
                "by closing it.",
        ),
        pinnedCommit = "31fa603e4f31279c6e1f7600fed13dc4312663ab",
    )

    /**
     * Indonesian — MIT weights over a YouTube-derived corpus, accepted by the owner without the
     * four-question counsel read the qualification table costed at ~0.5 d.
     */
    val ID = PackClearance(
        language = "id",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "Brandon Slacum, decision owner, 2026-09-13 — taken WITHOUT a counsel " +
                "read, which his decision records as not a prerequisite for these unchanged " +
                "weights. No opinion was sought and none exists",
            because = "MIT on the repository the four files are downloaded from, read in its own " +
                "902-byte front matter and platform-surfaced, with the uploader also being the " +
                "exporter; and the declared corpora are commercially permissive as a CLASS — " +
                "YODAS2 is CC BY 3.0, CommonVoice 17.0 is CC0, FLEURS is CC BY 4.0, and none of " +
                "them carries a non-commercial term or a share-alike. What the owner ACCEPTED is " +
                "the attribution chain two hops downstream, unanswered rather than answered: " +
                "YODAS2 deliberately removed the per-author handle, so a collection-level credit " +
                "is the most attribution the dataset makes possible, and that credit is on the " +
                "licences screen. librivox-indonesia's CC version is still unstated upstream",
            grantedOn = "2026-09-13",
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
                "licence that is is still unstated upstream, and the verdict records it as " +
                "accepted rather than answered.",
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
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "Brandon Slacum, decision owner, 2026-09-13 — on AI-Hub's own published " +
                "FAQ, read by him and re-read independently by the controller. Not a reply from " +
                "NIA, who were never written to; not a licence negotiated with anybody; and not " +
                "the two counsel questions this row used to carry, which were built on clauses " +
                "that govern access to the data",
            because = "apache-2.0 on the weights — the cleanest licence cell in the survey, read " +
                "three ways — and AI-Hub's published FAQ states that secondary works such as AI " +
                "models developed by using AI-Hub data FOR TRAINING may be freely used, or sold " +
                "and distributed, for commercial and non-commercial purposes, provided the " +
                "dataset's official name and AI Hub (aihub.or.kr) are cited as the source; what " +
                "it prohibits is providing or distributing the ORIGINAL DATA to a third party, " +
                "which this app never possesses or ships. So the attribution is a CONDITION of " +
                "the grant rather than a courtesy, and it is paid on the licences screen — " +
                "Korean ships only if that credit ships. The FAQ does not address a party outside " +
                "Korea either way, and the owner ACCEPTED that scope residual rather than closing " +
                "it; a Korean-language clarification request is optional extra evidence, not a " +
                "condition. Unchanged and not to be undone: this language's canary clip is FLEURS " +
                "and NOT the k2-fsa mirror's test_wavs, which are AI-Hub audio",
            grantedOn = "2026-09-13",
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
     * DESIGNATED it a counsel row — a designation withdrawn with the claim, because nothing was
     * ever sent and no opinion was sought. And **it was not established**: the shipped mirror's own
     * environment dump reads `training_subset: 'mix'`, the official sherpa-onnx documentation
     * describes an internal corpus, and the `12k_hour` string is on the FORK PARENT's card. The
     * parent's disclosure is lineage, not a disclosure about these bytes, so the corpus reads
     * UNDISCLOSED and the non-commercial claim is withdrawn. `StreamingPackClearanceTest` holds
     * both halves, including that no row converts an evaluation set into a training set.
     */
    val ZH = PackClearance(
        language = "zh",
        verdict = ClearanceVerdict.Cleared(
            grantedBy = "Brandon Slacum, decision owner, 2026-09-13 — and the row he accepted is " +
                "not the row this record used to describe: its corpus claim was corrected the " +
                "same day, and the counsel DESIGNATION that claim earned went with it. Nothing " +
                "was ever sent, no opinion was sought and none exists",
            because = "apache-2.0 read in the front matter and platform-surfaced on every link of " +
                "the chain, over a checkpoint whose training corpus is UNDISCLOSED — the card's " +
                "own environment dump reads training_subset: 'mix', the official sherpa-onnx " +
                "documentation describes an internal corpus, and the 12k_hour string belongs to " +
                "the FORK PARENT, which makes it lineage rather than this row's corpus. There is " +
                "therefore no corpus term on these bytes to weigh at all. The owner ACCEPTED an " +
                "undisclosed corpus under a declared grant, on exactly the terms he accepted " +
                "Russian's — an unsized risk, taken rather than left open",
            grantedOn = "2026-09-13",
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
     * makes, and the one the acceptance sheet's promotion gate quotes. Over the seven shipped
     * languages it answered `Withheld([de, ru, id, ko, zh])` until 2026-09-13 and answers
     * [PromotionState.Promotable] since.
     */
    fun stateOfRecord(languages: List<String>): PromotionState =
        state(PRODUCTION_CLEARED, languages, RECORD)
}
