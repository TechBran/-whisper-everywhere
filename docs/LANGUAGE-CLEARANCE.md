# Language clearance — the checklist before a production release

**Who fills this in: the owner.** Nobody else may mark a language cleared. A subagent inventing a
clearance is the one unrecoverable error in the 4.5.0 languages build, which is why the record is
pinned by a test that names exactly two cleared languages and why granting a third costs three
deliberate edits in three files.

**What it gates, and what it does not.**

| | internal testing track | production (public) |
|---|---|---|
| all six new languages present in the bundle | **yes, always** | yes |
| fetchable and armable on a device | **yes, always** | yes |
| gated on the clearance record | **no — nothing consults it** | **yes, this document** |

That split is the owner's own ruling of 2026-09-12: *"I still will need to be able to test on
internal testing track before the legal stuff."* The app never asks whether a language is cleared;
only a human promoting a release does. A build-time exclusion of an uncleared pack is **forbidden** —
it would strip the languages out of the artefact the research is being done *for*.

---

## The record, and the one rule

Everything below lives in
`app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackClearance.kt`.

- **`PackClearanceRecord.RECORD`** — one row per language, each carrying a verdict *and* the
  evidence: the licence as declared upstream, the URL it was read at, the date of that read, the
  provenance (how the grant covers the bytes the app downloads), the corpora with their terms, and
  the 40-hex commit the evidence was read at.
- **`PackClearanceRecord.PRODUCTION_CLEARED`** — the switch. The languages authorised for
  publication to the public. Today: `{"en", "fr"}`.

**The rule:** `PRODUCTION_CLEARED` may never name a language whose verdict is not `Cleared`, and a
production promotion requires it to cover **every** language in `StreamingPackCatalog.packs`. Break
either half and `StreamingPackClearanceTest` fails the build. Evidence arriving does not publish
anything — the authorisation is a separate, deliberate act, because "the German author confirmed his
licence" and "German goes in front of paying customers" are two different decisions and only one of
them is his to make on a technical basis.

### Where the record stands today

| language | verdict | who can close it | what is outstanding |
|---|---|---|---|
| `en` English | **CLEARED** | — | nothing. Apache-2.0 over LibriSpeech; in the built product since 4.4.0 |
| `fr` French | **CLEARED** | — | nothing. Apache-2.0 read twice; CommonVoice 12.0 on a LibriSpeech pretrain; GigaSpeech is not in the exported lineage |
| `de` German | outstanding | the upstream author | one email confirming the licence he already declared |
| `ru` Russian | outstanding | **you** | an undisclosed corpus: accept the risk, or refuse the row |
| `id` Indonesian | outstanding | counsel | YODAS2, four questions |
| `ko` Korean | outstanding | counsel | AI-Hub / NIA, two questions |
| `zh` Chinese | outstanding | counsel | WenetSpeech-L's named non-commercial restriction |

`zh` is the row the build brief calls `zh-en`: it is **selected** as Chinese and its language code is
`zh`, and the pack behind it is the bilingual Chinese-English export — the only pack in the catalogue
that puts anything on the strip when an English speaker talks mid-Chinese.

---

## When an answer arrives: the three edits

Say the German author replies and confirms his Apache-2.0. Recording that takes **three** edits, and
the third is friction on purpose — it is what makes an invented clearance show up as a diff.

**1. The verdict**, in `StreamingPackClearance.kt`. Replace that row's
`ClearanceVerdict.Outstanding(...)` with:

```kotlin
verdict = ClearanceVerdict.Cleared(
    grantedBy = "<who said so — a person, or the document, never 'the build'>",
    because = "<the reason, short enough to read at promotion time>",
    grantedOn = "<YYYY-MM-DD>",
),
```

Keep `licence`, `readAt`, `readOn`, `provenance`, `corpora` and `pinnedCommit` as they are unless the
answer changed one of them. If the author re-uploads the repository in the course of answering, the
`pinnedCommit` has changed and **the whole row is void**: the catalogue's byte counts, its four
sha256 digests and this evidence were all read at the old commit. Re-run
`tools/build_asset_packs.py` for that pack and re-read the licence before clearing it.

**2. The switch**, a few lines above:

```kotlin
val PRODUCTION_CLEARED: Set<String> = setOf("en", "fr", "de")
```

**3. The pin**, in
`app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackClearanceTest.kt` —
`onlyEnglishAndFrenchAreClearedOnThisBranch`. Add the language to both literals and rename the test
to say what it now pins. Its docblock explains why this edit exists; do not delete the explanation
along with the literal.

Then run the suite. It must be green, and
`PackClearanceRecord.stateOfRecord(StreamingPackCatalog.packs.map { it.language })` will report one
fewer name in its `Withheld` list.

## When the answer is NO: the refusal path

A refusal is **not** a switch — it is the removal of a row, and it is a code change:

1. delete the row from `StreamingPackCatalog` and its entry in `PackClearanceRecord.RECORD`,
2. delete the `preview_<lang>` module, its line in `settings.gradle.kts` and its name in the
   `assetPacks` literal in `app/build.gradle.kts`,
3. delete its rows from `tools/build_asset_packs.py` and from the `verifyPreviewPack` payload table,
4. delete its canary clip from `app/src/main/assets/` and its licence entry from
   `app/src/main/assets/oss_licenses.html`,
5. update the picker copy tests, which assert which languages have packs.

That is a real half-day, and it is deliberately not a flag: a language that may not be published is a
language the product does not have, and a switch that hid it would leave the bytes in the bundle and
the sentence in the picker. The internal track will have had it; production never does.

## The promotion step

Before promoting a release from the internal track to production:

1. `./gradlew.bat :app:testDebugUnitTest` — green, including `StreamingPackClearanceTest`.
2. Read `PackClearanceRecord.PRODUCTION_CLEARED`. **It must name every language in
   `StreamingPackCatalog.packs`.** If it does not, the promotion does not happen — the acceptance
   sheet's §AL promotion gate says the same thing in the place the decision is actually made
   (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`).
3. Run the §AL acceptance rows on device for every language being published.

---

# The outstanding questions, one section per language

Every corpus term quoted below was read from its own publisher by the qualification table,
`docs/superpowers/research/2026-09-11-language-qualification-table.md` — that document is the
authority for them and carries the full reads. Every **licence** was additionally re-read live on
2026-09-12 and is recorded in the row's own `provenance` field.

## German — `de`

**The pack:** `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` at commit
`322557b0f88fc5a9823bc71027d4160f0c7612cc`, 70,938,534 B.

**The exact question, to the uploader:**

> Your README's front matter declares `license: apache-2.0` for
> `icefall-asr-commonvoice-zipformer-streaming-de`. Can you confirm that the Apache-2.0 grant covers
> the four ONNX files in `exp/epoch-30/` and `lang_bpe_500/tokens.txt` at commit `322557b0`, for
> redistribution inside a paid Android application? Hugging Face does not surface the licence for
> this repository (the API reports it as unset, because the README is stored as an Xet blob), so
> there is nothing public for a licensee to rely on. Would you consider adding a plain `LICENSE`
> file, or a plain-text README, so the platform reads it?

**Where the evidence lives.** The grant is real text in the repository: a 180-byte `README.md` at the
pinned commit whose front matter reads `license: apache-2.0` and
`datasets: mozilla-foundation/common_voice_17_0`. Fetched and byte-counted on 2026-09-12. Read the
other way the same day, the Hugging Face API returns no `license` and `cardData: null`, and an empty
tag list. **That asymmetry is the whole of the outstanding item** — a confirmation of a grant that is
already declared, not a request to create one. The corpus, CommonVoice 17.0, carries no third-party
agreement, which is why this is one email and not counsel.

**Cheapest row in the survey to close.** If the author does not reply, the decision is yours on the
same terms as Russian below: the declared grant is real, it is simply unwitnessed.

## Russian — `ru`

**The pack:** `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` at
`31fa603e4f31279c6e1f7600fed13dc4312663ab`, 28,572,945 B — the cheapest pack in the catalogue.

**This one is your own risk call, and no document will settle it.** The card says only that the model
was *"trained with k2-fsa/icefall on Russian data"*. **No corpus is named anywhere**, so the risk
cannot be sized — only accepted or refused.

**Where the evidence lives.** The licence position here is the *strongest* of the five, stronger than
German's: the upstream `alphacep/vosk-model-small-streaming-ru` is platform-surfaced `apache-2.0`
(re-read 2026-09-12), and the untagged mirror this row downloads is discharged **cryptographically**
rather than by trust — all four pack files are byte-identical to that upstream (three LFS oids plus a
sha256 computed on `tokens.txt`). What is outstanding is only what the weights were trained on.

**Optional, and nobody is obliged to answer it:** one email to `alphacep` asking which corpora went
into `vosk-model-small-streaming-ru` v0.54. The qualification table files this as nice-to-have rather
than gating (its ruling O4), on the reasoning that an Apache-2.0 grant on the artefact is what a
licensee relies on.

**This row is outstanding because nobody has decided it, not because anybody is working on it.** If
you are comfortable, record yourself as the grantor with that reasoning in `because`.

## Indonesian — `id`

**The pack:** `spacewave/sherpa-onnx-streaming-zipformer2-id` at
`4e5a13cbe3e9cd4e3775447d86178ef51759096f`, 70,908,694 B. Weights are **MIT**, re-read
platform-surfaced on 2026-09-12.

**Four questions for counsel** (qualification table C3), all about the corpus and none about the
licence class — CC BY 3.0 is commercially permissive, with no non-commercial term and no share-alike:

1. Does training on a CC BY 3.0 corpus create an attribution obligation on the **weights**?
2. If it does, can a **collection-level** credit discharge it, given that YODAS2 removed the
   per-author handle — its `video_id` is deliberately **not** YouTube's, so per-author attribution is
   structurally unsatisfiable?
3. Does the uploader's MIT grant reach the **85.6% of Indonesian label hours that are auto-captions**
   (8,463.61 of 9,883.70 h)? Chain of title rests on a search flag the dataset says *"should
   (mostly)"* hold, extrapolated per channel, backed by a takedown mailbox.
4. Which CC licence is `indonesian-nlp/librivox-indonesia`? It is tagged only `cc`, with no version.

**Where the evidence lives.** The card declares four datasets and all four were re-read in the API's
dataset tags on 2026-09-12: `espnet/yodas2`, `mozilla-foundation/common_voice_17_0` (CC0),
`google/fleurs` (CC BY 4.0) and `indonesian-nlp/librivox-indonesia`. The YODAS2 hours table and its
verbatim licence sentences are in the qualification table.

**What changes on a yes:** the three edits above, **plus one credits line** — counsel's answer to
question 2 decides its wording, and it goes in `app/src/main/assets/oss_licenses.html` beside the
FLEURS attribution the canary clips already carry.

## Korean — `ko`

**The pack:** `kangkyu/icefall-asr-ko-streaming-zipformer-72m` at
`db24b58d22736349eaeb34cc181ad0f3debf9903`, 72,969,700 B. The **cleanest licence cell in the survey**
— Apache-2.0 in a plain (parseable) README front matter, in the README's own License section, and
platform-surfaced in `cardData` and the tags. All three re-read on 2026-09-12.

**Two questions for counsel** (qualification table C2):

1. Could the uploader grant Apache-2.0 over weights whose corpus rights vest in **NIA**
   (한국지능정보사회진흥원) and whose commercial exploitation is conditioned on a separate agreement?
   Apache-2.0 §7 disclaims any warranty of title, **so we carry that risk.**
2. Does the mandatory NIA attribution reach this app **through the weights**?

**Where the evidence lives.** The README's own data table names *"KsponSpeech 1,000h (AIHub dataset
123)"* and links `aihub.or.kr/aihubdata/data/view.do?dataSetSn=123` — both re-read at the pinned
commit on 2026-09-12. The AI-Hub 데이터 이용정책 was read clause by clause by the qualification
table: 「※ 내국인만 데이터 신청이 가능합니다」; a party **outside Korea** needs a separate agreement;
**export** needs a separate agreement; use is *"only for training AI learning models"*; no transfer
and no sale; and attribution to NIA is **mandatory and extends to derivative works**.

**Already done, and not to be undone regardless of the answer:**

- the **NIA acknowledgement** is on the licences screen (`app/src/main/assets/oss_licenses.html`) —
  the table costs it at 0.1 d with no downside, and question 2 is exactly what it pre-empts;
- the Korean canary clip is **FLEURS** and deliberately **not** the k2-fsa mirror's `test_wavs`,
  which are AI-Hub audio.

## Chinese — `zh` (the bilingual Chinese-English pack)

**The pack:** `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t` at
`e2382758de9a0219b4efe682b95af30b399db3b8`, 49,752,335 B. Apache-2.0, read in the front matter and
platform-surfaced on every link of the chain (re-read 2026-09-12). This is the **50 MB V1 export**,
not its 198 MB sibling.

**The question for counsel** (qualification table C1) — *"the heaviest corpus question in the
document"*, and it wants a **written opinion**, not an email:

> May weights derived from **WenetSpeech-L** (12,000 h) — a corpus whose publisher states it is
> *"available to download for non-commercial purposes"* and that *"WenetSpeech doesn't own the
> copyright of the audios"*, and whose access is a Google-Form-mailed password — ship inside a
> **paid** app, under the uploader's Apache-2.0 grant over the exported weights?

**Where the evidence lives.** The corpus is named by the **author**, not inferred: his card publishes
`training_subset: '12k_hour'`, which is WenetSpeech's own name for its L subset. (The *"internal
multilingual dataset"* phrase an earlier survey quoted appears nowhere in the repository — it is the
sherpa-onnx documentation's description.) A **named** restriction is a worse position than an unnamed
unknown, because it converts *"we did not know"* into *"it was on the card"*.

**Read this before deciding it row by row.** It is a **Chinese-lane** ruling, not a per-row one:
every Chinese-**only** alternative carries WenetSpeech **plus** AISHELL-2 **plus** KeSpeech — three
agreements where this bilingual row carries one, and one of those (KeSpeech) bars *"Adaptations"* and
is revocable on the licensor's notice, while another (`aidatatang_200zh`) is delisted so its licence
cannot even be read. Refusing this row does not open a cheaper door. The table's own instruction:
**do not spend the measurement day until counsel answers**, because no accuracy number changes it.

---

## English and French, for completeness

Neither needs anything, and both are recorded with their evidence in `PackClearanceRecord` so the
record is a complete census rather than a list of problems.

**English** — Apache-2.0 (`cardData.license` plus the `license:apache-2.0` tag, re-read 2026-09-12),
LibriSpeech (CC BY 4.0), no agreement anywhere in the lineage. It has been in the built product since
4.4.0; the grantor of record is you, by shipping it.

**French** — Apache-2.0 read **twice**: the repository's own 204-byte non-LFS README front matter, and
platform-surfaced through `cardData.license` and the `license:apache-2.0` tag. Note that the API's
*top-level* `license` key is absent, so a sweep reading `model["license"]` reports "Not specified"
for a perfectly readable grant; five of these seven repositories have that shape. CommonVoice 12.0 fr
(CC0) on a LibriSpeech (CC BY 4.0) pretrain.

**One number about French must not be carried forward, and it is a clearance fact rather than an
accuracy one.** Its WER is **10.57**, not the **9.95** that icefall's `RESULTS.md` advertises. 9.95
belongs to the epoch-30 avg-9 checkpoint, which was trained **with GigaSpeech** — whose gated terms
are non-commercial-only. This export is epoch 29 avg 9 (the repository's own
`export-onnx-stateless7-streaming.sh` runs `--epoch 29 --avg 1 --use-averaged-model 0`). Citing 9.95
anywhere — a listing, a store description, a reply to counsel — would import an encumbrance these
bytes do not carry. The catalogue row's comment says so too, because 9.95 is the number the next
reader will find first.

**The clearance of French is the controller's reading of the qualification table, not a lawyer's
opinion**, and it is yours to withdraw. If you would rather it waited for the same research the other
five are getting, remove `"fr"` from `PRODUCTION_CLEARED` and set its verdict back to
`Outstanding` — the suite will stay green, and production will simply be withheld on six languages
instead of five.
