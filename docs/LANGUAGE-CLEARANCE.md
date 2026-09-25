# Language clearance — the checklist before a production release

> ## ✅ ALL SEVEN ARE CLEARED — the owner's decision of 2026-09-13
>
> Brandon Slacum did the licensing research himself and issued a formal handoff dated **2026-09-13
> accepting the reviewed licensing basis for all seven preview packs**. `PRODUCTION_CLEARED` names
> all seven and `PackClearanceRecord.stateOfRecord(...)` reports **`Promotable`**.
>
> **What that decision is:** *his* acceptance of a reviewed basis, per row, dated. **What it is
> not**, and what nothing in this repository may be read as saying:
>
> - a permission from a model author — **the German uploader was never written to and never
>   replied**, and that hold is retired rather than satisfied;
> - a reply from NIA or AI-Hub — **nothing was ever sent to them**; Korean rests on AI-Hub's own
>   **published FAQ**, which anybody can read;
> - a legal certification — **no counsel opinion was sought or obtained** for Indonesian, Korean or
>   Chinese, and his decision records that one is not a prerequisite for these unchanged packs;
> - Google Play approval — Play reviews an app, not a corpus;
> - a number. His informal confidence in his own research is **not a figure this record carries**,
>   and the suite fails the build on a percentage inside a cleared verdict.
>
> **Where an uncertainty remains, it is recorded as ACCEPTED — never as resolved.** All five rows
> carry one and name it: German's declaration is unwitnessed by its author; Russian's and Chinese's
> corpora are undisclosed; Indonesian's YODAS2 attribution chain is unanswered; Korean's FAQ does
> not address a party outside Korea either way. An accepted risk is still a risk.
>
> **And the decision came with a CONDITION, not a caveat.** Apache-2.0 §4 and MIT are trades, and
> the AI-Hub FAQ grants commercial distribution of a derived model *only with attribution*. The
> notices ship in the base app's own assets (`app/src/main/assets/oss_licenses.html`). **Korean
> ships only if its acknowledgement ships with it.**
>
> The rest of this document is unchanged and still live: it is what a **withdrawal**, a **refusal**
> or an **eighth language** is worked from, and the per-language evidence sections below are the
> record of what each row was cleared *on*.

**Who fills this in: the owner.** Nobody else may mark a language cleared. A subagent inventing a
clearance is the one unrecoverable error in the 4.5.0 languages build, which is why the record is
pinned by a test that names exactly which languages are cleared, which of them the owner decided,
and on what date — and why granting an **eighth** still costs three deliberate edits, the third of
them in a test whose docblock says why a subagent must not write it.

**The edit lists below were walked against the suite rather than reasoned about — and this is
exactly which walks were run**, because "every list was measured" is the kind of claim that goes
stale the moment a list is added. All of it on this tree, on 2026-09-12:

| transition | applied to | result |
|---|---|---|
| **a YES** — the three edits under *"When an answer arrives"* and nothing else | each of the five outstanding rows in turn: `de`, `ru`, `id`, `ko`, `zh` | **green, all five** |
| **a WITHDRAWAL** — the three edits at the end of the French section | both cleared rows: `fr`, and `en` by the same list | **green, both** |
| **a REFUSAL** — every step of the refusal path a test can see | `zh` | **15 failures in 5 classes**, which is where step 6's table of classes comes from: the earlier inspected list named three of the five |

Where a list was short, the list was fixed, not the claim — that has now happened three times, and
each time it was the transition the previous fix had *not* re-walked. Two steps of the refusal path
are marked *inspected* rather than measured, and say so where they are written.

**If you follow one of these lists and the suite is red, the document is wrong** — say so, and do
not start editing clearance assertions to make it green.

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
  publication to the public. Today: `{"en", "fr", "de", "ru", "id", "ko", "zh"}` — all seven.

**The rule:** `PRODUCTION_CLEARED` may never name a language whose verdict is not `Cleared`, and a
production promotion requires it to cover **every** language in `StreamingPackCatalog.packs`. Break
either half and `StreamingPackClearanceTest` fails the build. Evidence arriving does not publish
anything — the authorisation is a separate, deliberate act, because "the German author confirmed his
licence" and "German goes in front of paying customers" are two different decisions and only one of
them is his to make on a technical basis.

**Neither half was loosened to let the seven through.** What satisfies the rule is that an owner
decision IS a real verdict. And the set being full does not make it permissive: a shipped language
with **no record** is reported ahead of everything else, so an **eighth** language added to the
catalogue and to this set in one motion fails the build rather than inheriting a set that already
means "everything". That cell is asserted.

### Where the record stands today

| language | verdict | granted by | what was ACCEPTED with it |
|---|---|---|---|
| `en` English | **CLEARED** | you, by shipping it since 4.4.0; restated 2026-09-12 | nothing outstanding. Apache-2.0 over LibriSpeech (CC BY 4.0), no agreement in the lineage |
| `fr` French | **CLEARED** | the controller's read of the qualification table, 2026-09-12 | nothing outstanding. Apache-2.0 read twice; CommonVoice 12.0 (CC0) on a LibriSpeech pretrain; GigaSpeech is not in the exported lineage. **Yours to withdraw** |
| `de` German | **CLEARED** | **Brandon Slacum, 2026-09-13** | that the `apache-2.0` declaration is **unwitnessed by its author** — 180 bytes of front matter the platform cannot parse, re-fetched at a matching sha256. The email was never sent |
| `ru` Russian | **CLEARED** | **Brandon Slacum, 2026-09-13** | an **undisclosed corpus** — an unsized risk, taken. The licence chain is the strongest of the five and the mirror is discharged cryptographically |
| `id` Indonesian | **CLEARED** | **Brandon Slacum, 2026-09-13** | the **YODAS2 attribution chain two hops downstream**, unanswered. No counsel opinion was sought; the collection-level credit is on the licences screen |
| `ko` Korean | **CLEARED** | **Brandon Slacum, 2026-09-13** | the **overseas scope** the AI-Hub FAQ does not address either way. The FAQ's attribution is **not** a residual — it is the **condition**, and it is paid |
| `zh` Chinese | **CLEARED** | **Brandon Slacum, 2026-09-13** | an **undisclosed corpus** — corpus claim **corrected 2026-09-13**; Russian's shape exactly |

**A `CLEARED` row with an accepted residual is not the same thing as a closed question**, and the
column above is the whole reason this table has four columns instead of three. If you ever need to
know what was *actually* decided on a row, its section below is the evidence and its `because`
string in `StreamingPackClearance.kt` is the reason of record.

`zh` is the row the build brief calls `zh-en`: it is **selected** as Chinese and its language code is
`zh`, and the pack behind it is the bilingual Chinese-English export — the only pack in the catalogue
that puts anything on the strip when an English speaker talks mid-Chinese.

### The bundled SPEAKER model — the one row here that is not a language pack (4.10.0)

4.10.0 bundles a **speaker-embedding** model in the base module so the app can tell one voice from
another and label the paragraphs. It is not a preview pack: it is not in
`StreamingPackCatalog.packs`, it is not downloaded, and **it deliberately has no row in
`PackClearanceRecord`** — that record and its `PRODUCTION_CLEARED` switch are per LANGUAGE, the
suite holds a join between them and the catalogue, and an eighth entry naming something that is not
a language would break the one assertion that stops a language shipping uncleared. So it is
recorded **here**, in the same four columns, and the gate on it is this document and the owner
reading it — not a test.

| language | verdict | granted by | what was ACCEPTED with it |
|---|---|---|---|
| **the speaker model** (not a language) — NVIDIA NeMo **TitaNet-small**, `nemo_en_titanet_small.onnx`, bundled as `app/src/main/assets/speaker_titanet_small_16k.onnx`, 40,257,283 B, `sha256 ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e`. Licence as published: **CC-BY-4.0** | **CLEARED for production** — the attribution the licence asks for is **PAID and shipping**, which is the condition; this is the decision | **Brandon Slacum, 2026-09-19** — asked whether he accepted the basis and answered *“Yes. And, yeah, I agree and approve it.”*. The condition was already discharged: The condition is discharged: `app/src/main/assets/oss_licenses.html` carries a **Speaker labels** section naming NVIDIA, NVIDIA NeMo, CC BY 4.0, the model card and the licence text, held by `SpeakerEmbedderPinTest` | that the grant is read off **NVIDIA's own published model card and the ONNX graph's own metadata**, not off a reply from NVIDIA — **nobody was written to**, and nothing needs to be: CC-BY-4.0 is a public licence whose only term we can fail is attribution. What is unwitnessed is that the card's declaration is the card's own. And that the **corpus** NVIDIA trained it on is **undisclosed** on that card — `ru`'s and `zh`'s shape exactly, and acceptable on the same basis or on none |

**What this row gates is what AL0 gates: a production promotion, never the internal track.** The
model is in every build and always has been, exactly as the six languages were in every build while
five clearances were outstanding — that is what lets it be heard before it is decided. Nothing the
app runs consults this row.

`[x] signed off — promotion may proceed` — Brandon Slacum, 2026-09-19
`[ ] not signed off — INTERNAL TRACK ONLY`

---

## When an answer arrives: the three edits, and one line nothing enforces

**This list was walked five times on 2026-09-13 and it is what recorded the owner's decision** — and
it is still live, because the next thing it records is an **eighth** language, or a row cleared again
after a withdrawal. Say a new pack's licence question is answered. Recording that takes **three**
edits that the suite enforces — the third is friction on purpose, it is what makes an invented
clearance show up as a diff — **plus one sentence on the acceptance sheet that no test can see**
(step 4). The list is exhaustive, and measured that way: applied as written to German — and to each
of the other four rows in turn, because a list that is only ever walked for one language is a list
that works for one language — the suite ends green every time.

**One thing the decision of 2026-09-13 added to step 3: a fourth literal.** The pin test now also
holds **which rows are that decision**, keyed to the grant date, so a row cannot drift between the
owner's grant and the controller's 2026-09-12 read without a deliberate diff. It is priced below.

**1. The verdict**, in `StreamingPackClearance.kt`. Replace that row's
`ClearanceVerdict.Outstanding(...)` with:

```kotlin
verdict = ClearanceVerdict.Cleared(
    grantedBy = "<who said so, BY NAME — a person, or the document, never 'the build' and never
                 'the owner'. If it is your own decision, name yourself and the date>",
    because = "<the reason, short enough to read at promotion time, naming this row's own
                declared licence and — where an uncertainty remains — recording it as ACCEPTED>",
    grantedOn = "<YYYY-MM-DD>",
),
```

**Four things the suite will refuse in those two strings**, and all four exist because a real
decision is as easy to mis-record as a fake one is to invent:

- **the word "approved", or any other borrowed permission.** Your decision *accepts* a reviewed
  basis. Writing it as an approval, a permission from an author, a confirmation from anybody, a
  certification or a Google Play decision claims something that does not exist. `approv`,
  `permission from`, `confirmed by`, `certif`, `legal opinion`, `legally cleared` and `google play`
  are all refused in a row dated as your decision;
- **a percentage.** A number beside a grant reads as a measured probability and nothing here
  measured one;
- **"resolved" where the truth is "accepted".** An accepted risk is still a risk; say which it is;
- **a reason under 120 characters**, or one that does not name the row's own `licence`. A basis that
  could be pasted onto any row is not this row's basis.

Keep `licence`, `readAt`, `readOn`, `provenance`, `corpora` and `pinnedCommit` as they are unless the
answer changed one of them. If the author re-uploads the repository in the course of answering, the
`pinnedCommit` has changed and **the whole row is void**: the catalogue's byte counts, its four
sha256 digests and this evidence were all read at the old commit. Re-run
`tools/build_asset_packs.py` for that pack and re-read the licence before clearing it.

**2. The switch**, a few lines above — add the language to the set:

```kotlin
val PRODUCTION_CLEARED: Set<String> = setOf("en", "fr", "de", "ru", "id", "ko", "zh")
```

**3. The pin**, in
`app/src/test/java/com/whispereverywhere/transcription/stream/StreamingPackClearanceTest.kt` —
`allSevenAreClearedAndFiveAreTheOwnersDecisionOf20260913`. That is **the only test in the file that
retypes the state of the record**, and it holds **four** literals. A clearance moves them all:

- the **cleared list** — add the language, in the order `PackClearanceRecord.RECORD` uses
  (`en, fr, de, ru, id, ko, zh`), **not** alphabetically;
- the **switch** — the same set as edit 2 above;
- the **outstanding census**, the `"de" to ClearanceAnswerer.…` pairs — **delete the row you have
  just cleared from it.** This is the line that makes "one fewer question" visible in a diff. It is
  **empty** since 2026-09-13, and it is still the line an eighth language's open question is typed
  into;
- the **owner-decision census** — `listOf("de", "ru", "id", "ko", "zh")`, the rows granted on
  `2026-09-13`. Add the language here **only if you are recording it as your own decision on that
  same date**; a row cleared on a later date, or by somebody else, belongs in the cleared list and
  the switch and not in this one.

Then rename the test to say what it now pins, and keep the docblock: it explains why the edit exists,
and the explanation is the part that makes a forged clearance visible.

**Leave the language's section in this document exactly where it is.** A clearance does not retire a
section — only a refusal deletes one (the refusal path, step 5). The suite asks every *cleared* row
for its section too, because a clearance can be withdrawn and the section has to exist before that
day rather than be written on it; the row's evidence, its pinned commit and the answerer it would go
back to are all in there. Editing the section to record *when and by whom* it was cleared is welcome.
Deleting it is a red suite.

Then run the suite. It must be green:

```
./gradlew.bat :app:testDebugUnitTest
```

and `PackClearanceRecord.stateOfRecord(StreamingPackCatalog.packs.map { it.language })` will report
one fewer name in its `Withheld` list — or `Promotable`, which is what it reported when the fifth of
those names went on 2026-09-13. **Nothing else in that file moves** — every other test in it
derives what it expects from the record, on purpose. (It was not always so. The first draft of this
document priced a yes at "both literals" in one test; applied to a German yes, the suite came back
red in **three** tests, one of them the positive control that stops the switch outrunning the
evidence — which is the last assertion anyone should be editing on the strength of a document that
said the suite would be green. The derivations were the fix; this list is the measurement.)

**4. One line nothing enforces**, worth thirty seconds because it is the line a promotion is actually
read from: §AL0 of `docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md` quotes what the
switch names and what the gate reports. No test pins that sentence — pinning it would add an edit to
every clearance and protect nothing, since the record is the authority and the sheet only quotes it —
so it goes stale silently. Update it, or read it against `PackClearanceRecord` on the day. It was
updated on 2026-09-13 to say all seven and `Promotable`, which is exactly the drift this step exists
to catch.

## When the answer is NO: the refusal path

A refusal is **not** a switch — it is the removal of a row, and it is a code change:

1. delete the row from `StreamingPackCatalog` and its entry in `PackClearanceRecord.RECORD`,
2. delete the `preview_<lang>` module, its line in `settings.gradle.kts` and its name in the
   `assetPacks` literal in `app/build.gradle.kts`,
3. delete its rows from `tools/build_asset_packs.py` and from the `verifyPreviewPack` payload table,
4. delete its canary clip from `app/src/main/assets/` and its licence entry from
   `app/src/main/assets/oss_licenses.html`,
5. delete its section from this document, and mark its §AL row on the acceptance sheet **REMOVED**
   with the date and the reason — that sheet's rule is that a row which once passed is rewritten in
   place and never deleted (see AF2, which is the worked example),
6. update the tests that hold a language **by name**. This list is MEASURED, not inspected: steps
   1-5 above were applied for a refusal of `zh` on 2026-09-12 (bar the two marked *inspected*
   below) and the full suite came back **15 failures in 5 classes** — three of which the earlier,
   inspected version of this list did not name.

   | class | red | what it holds |
   |---|---|---|
   | `PreviewCanaryClipsTest` | **8** | its `clips` table, held one-to-one against the rows that carry a canary (`:196`), plus the measured-strip, unit, runaway-ceiling and corruption-shape gates that all read that table. **Deleting the clip file in step 4 is not enough — its row in this table goes with it.** |
   | `PreviewPackLayoutTest` | 2 | the seven-row module census (`:96`) and the `assetPacks` expression held as exact text (`:212`). Both are deliberate friction: they are the pins that make "all six ship" true |
   | `StreamingPackLanguagesTest` | 3 | the per-language flag rows (`zh`'s at `:366-415`) |
   | `StreamingPackCatalogTest` | 1 | `forLanguage("zh")` resolving to the row, asserted by symbol (`:145`) |
   | `StreamingPackCopyTest` | 1 | the picker's seven language words, in the catalogue's order (`:644`) |

   — plus, in `StreamingPackClearanceTest`, the literals in
   `allSevenAreClearedAndFiveAreTheOwnersDecisionOf20260913`. With every row now cleared, a refusal
   removes the row from the **cleared list** and from the **switch**, and from the
   **owner-decision census** if that is where its grant came from; the **outstanding census** takes
   the refused row only if it was still open, which since 2026-09-13 it is not until somebody
   reopens it. Three of the row's four literal appearances, where before the decision it was one.

   **And one consequence that is not a test failure at all.** Those test files name the row by
   SYMBOL (`StreamingPackCatalog.ZH`, in `StreamingPackCopyTest`, `StreamingPackLanguagesTest` and
   `StreamingPackCatalogTest`), so deleting the `val` itself does not redden a test — it stops the
   test source set COMPILING, which a reader hunting red tests will not recognise as this list.
   Delete the references first, then the row.

   **Nothing else in the clearance suite names a language, and that half is measured too:** with
   the census line removed, all 11 of `StreamingPackClearanceTest`'s tests stayed green. The
   record-to-catalogue one-to-one, the promotion state, the pack-module scan and the licence-page
   check all derive from the catalogue, so deleting a row is silent in all four.

   **Two exceptions added on 2026-09-13, and they name a language on purpose**, because they are
   about a corrected FACT rather than about a verdict:
   `theChineseRowsCorpusIsUndisclosedAndTheForkParentIsRecordedAsLineage` and
   `theKoreanRowRestsOnTheFaqGrantAndItsAttributionCondition`. A refusal of `zh` or `ko` reddens its
   own one with an `assertNotNull` naming the missing row, which is the right failure — the
   correction has to be deleted deliberately, not silently inherited by a row that no longer exists.
   Delete the test with the row.

   **Two steps were INSPECTED rather than measured, and for stated reasons:** the `preview_<lang>`
   directory and its gitignored payload were left on disk (the payload is yours, not the suite's,
   and `PreviewPackLayoutTest` reads the payload directory — deleting it would redden that class
   for a reason the refusal did not cause), and the acceptance sheet's §AL row was left alone
   (marking it REMOVED is prose no test reads). Expect those two to cost what step 2 and step 5
   say, and nothing in the suite to notice either way.

That is a real half-day, and it is deliberately not a flag: a language that may not be published is a
language the product does not have, and a switch that hid it would leave the bytes in the bundle and
the sentence in the picker. The internal track will have had it; production never does.

## The promotion step

Before promoting a release from the internal track to production:

1. `./gradlew.bat :app:testDebugUnitTest` — green, including `StreamingPackClearanceTest`.
2. Read `PackClearanceRecord.PRODUCTION_CLEARED`. **It must name every language in
   `StreamingPackCatalog.packs`.** If it does not, the promotion does not happen — the acceptance
   sheet's §AL promotion gate says the same thing in the place the decision is actually made
   (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md`). Since 2026-09-13 it does, and
   the gate reports `Promotable`.
3. **Check the notices are still there.** The grants these packs ship under are trades: Apache-2.0
   §4 and MIT ask for their notices to travel with what they cover, and AI-Hub's FAQ grants
   commercial distribution of a derived model *only with attribution*. `oss_licenses.html` is where
   that is paid, and the suite fails the build if a pack's evidence URL, its licence's full name, or
   the KsponSpeech / AI-Hub / NIA credit goes missing from it. **A pack whose notice is not there is
   not cleared to ship, whatever this document says.**

   **What that page carries since 4.5.2, and the test that holds each piece.** Every row is a
   *presence* check on the committed file, not a check on a built artefact — see the caveat under
   the table.

   | on the page | held by |
   |---|---|
   | the **full text** of Apache-2.0 (byte-identical to the ASF's own `LICENSE-2.0.txt`, 11,358 B, `sha256 cfc7749b…`) and of MIT (byte-identical to the `LICENSE` of the vendored whisper.cpp source, so it carries a real copyright line) | `OssNoticeTest.theTwoLicenceTextsAreIncludedInFullRatherThanLinked` — whole-document digests, because a licence asserted phrase by phrase passes with a clause missing from the middle |
   | one attribution row per pack at `id="pack-<lang>"`, carrying the **40-character revision the bytes are downloaded at**, the repository they come from (the untagged mirror, for `ru`) and the declaration the upstream makes (`license: apache-2.0` / `license: mit`) | `OssNoticeTest.everyPackTheAppCanFetchHasAnAttributionRowWithItsPinnedRevision` — the loop is `StreamingPackCatalog.packs`, so **an eighth language cannot arrive uncredited** |
   | the **NOTICE finding**: none of the seven repositories carries a `NOTICE`, `LICENSE` or `COPYING` file, and **six of the seven** declare a bare front-matter identifier with no copyright line; **the seventh is the Russian mirror**, which declares none of its own at all, and the grant relied on there is the tagged upstream's, to which its four files are byte-identical — so §4(d) has nothing to carry, and **no holder and no year has been invented** | the same test |
   | the corpora that ask for credit, each named as its licence asks: LibriSpeech (CC BY 4.0) to its four authors by name, YODAS2 (CC BY 3.0), FLEURS (CC BY 4.0), Common Voice (CC0, credited anyway and *marked* as voluntary), **KsponSpeech + AI Hub (aihub.or.kr)** as the condition it is; and the two rows that disclose **no** corpus, labelled undisclosed | `OssNoticeTest.theCorporaThatAskForCreditAreCreditedByTheNameEachAsksFor` — the undisclosed set is derived from this record, so a row becoming disclosed forces the page's sentence to be rewritten on purpose |
   | §4(b): what we actually change — **selection, naming, packaging**, and nothing else. All 28 files are downloaded byte-for-byte at the pinned revision and refused on a digest mismatch; the `int8` export is the upstream publisher's, and the Russian decoder is not quantised at all | `OssNoticeTest.theModificationsStatedAreTheOnesWeActuallyMake` — the naming half is derived from `PackFile.path` vs `name`, and a **forbidden-phrase scan** fails the build if the page starts claiming a modification we do not make |
   | it is **reachable**: a Settings row → `onNavigateToLicenses` → the `open_source_licenses` route → *this* asset | `OssNoticeTest.theLicencesScreenIsReachableFromSettingsAndOpensThisAsset` — four one-line links, each of which breaks silently |
   | **(4.10.0) the `Speaker labels` section**, paying CC-BY-4.0 for the bundled NVIDIA NeMo TitaNet-small: the creator by name, the work, the licence by its full name with a link to its text, the statement of what was changed (**the filename, and nothing else**), both filenames and the shipped `sha256`, and NVIDIA's model card | `SpeakerEmbedderPinTest.theAttributionThisCcByModelAsksForIsPAIDOnThePageTheUserCanOpen` — the same shape as the pack rows: a presence check on the committed page. It also asserts the adapter's KDoc no longer says the line is owed, and that this document carries the model's **PENDING OWNER SIGN-OFF** row, so a green suite can never be read as the clearance |

   **What none of that establishes, and what two inspections did.** Every row above reads
   `app/src/main/assets/oss_licenses.html` **in the source tree**. A green suite is not legal clearance,
   and on its own it is no evidence at all that the asset was packaged into a bundle's `base/`. Two
   artefacts were opened on 2026-09-13 (4.5.2 Task 4), and **they are two separate facts**:

   | what was opened | what was read inside it | what that establishes |
   |---|---|---|
   | the **4.5.1/93 RELEASE bundle** — the artefact the controller built and verified: `bundleRelease`, 5,438,505,736 B, `sha256 4a168f0a1794cb4fc2a13501ca382fd59d4b848516cd8d7a9c1f705782e2cd65` | `base/assets/oss_licenses.html`, 12,492 B, `sha256 7901187a688a45c0478e1b165b67d1e59ed5af86bc1e519bbb5172b89913e524` — **byte-identical** to that release's own committed page (its CRLF working-tree form) | the RELEASE path carries this asset into `base/` **unmodified**, with `isMinifyEnabled` and `isShrinkResources` both on. It says nothing about 4.5.2's notices: that bundle was built before them |
   | a **DEBUG bundle** built from this branch at `0663afe` — the three payload gates excluded with `-x` and all ten asset packs therefore empty, which is the only bundle this worktree can build | `base/assets/oss_licenses.html`, **39,024 B**, `sha256 73ecd96b27544a57cd7355086356f17c51096a1225a1ec174fef9d26d50ee11c` — byte-identical to the committed page in its CRLF working-tree form, which is what the asset merge copies and therefore what a bundle from this machine carries. Every notice element probed PRESENT inside the packaged entry (both licence texts with the Apache APPENDIX line and ggml's real copyright line, all seven `id="pack-*"` rows carrying seven distinct 40-character revisions, KsponSpeech + aihub.or.kr + the NIA credit, the five corpora, the §4(b) statement) and a deliberately wrong control probe MISSING; and the Settings row → route → asset chain's four strings present in the packaged dex | **this exact page** reaches `base/assets/` of a bundle built from this branch, intact to the byte |

   **The page CHANGED at 4.10.0/100, after both inspections above, and neither of them describes
   it any more.** 4.10.0 bundles NVIDIA NeMo TitaNet-small for the speaker labels; CC-BY-4.0 makes
   attribution a term, so the page gained a **Speaker labels** section paying it. The committed
   page was then **40,899 B**, `sha256 e52b058adafd89bf5412ac2d3ac1704d1fca800f4b01a15abe31374c3d8b5319`
   in the CRLF working-tree form the asset merge copies. **Both numbers have now been READ OUT OF THE 4.10.0 ARTEFACT**, not derived: the controller's
   `bundleRelease` of 2026-09-19 14:32 (5,476,001,464 B) was opened and its
   `base/assets/oss_licenses.html` is **40,899 B**, `sha256 e52b058adafd89bf5412ac2d3ac1704d1fca800f4b01a15abe31374c3d8b5319`
   — byte-identical to the committed page. Every Speaker-labels element probed PRESENT inside the
   packaged entry (the section heading, NVIDIA NeMo TitaNet-small, the licence by its full name,
   the shipped filename, the `sha256`, the licence link and the model card) and a deliberately
   wrong control probe MISSING. The 2026-09-13 rows above stand as what they always were:
   observations on the page as it stood at `0663afe`.

   **The page CHANGED again at 4.16.0/113 (the MediaTek APU tier, P3a), and the 4.10.0 read no
   longer describes it.** Since P2-6 every APK carries LiteRT 2.1.1 — `libLiteRt.so` in
   `lib/arm64-v8a/` and `libLiteRtDispatch_MediaTek.so` in the base module's assets, whatever chip
   the device has — so the page names both (Google, Apache License 2.0, unmodified) under
   **Speech recognition**, and reproduces the one further notice LiteRT's own licence file carries:
   Caffe's BSD 2-Clause notice, for code TensorFlow derives from Caffe, byte for byte as the litert
   2.1.1 AAR's `LICENSE` gives it (`OssNoticePackagingTest` holds the entries, the notice's digest
   and the packaging needles). A `TODO(owner)` sat beside the MediaTek entry then: the **NeuroPilot
   Express** notice for the compiled mt6989 pair waited on the owner's reading of that licence, which
   gated the tier's first Play upload (ACCEPTED 2026-09-25 — the next paragraph). The committed
   page was then **44,799 B**,
   `sha256 8505b13a3e38ce989093fc2642708129d06d5559a81fb0fbba0d2927389fca09` in its CRLF working-tree
   form, and **both numbers were READ OUT OF AN ARTEFACT**, not derived: the 4.16.0/113 **debug APK**
   (`:app:assembleDebug` of 2026-09-25 02:52 on `feat/mediatek-apu-tier-p3a`, 203,338,313 B,
   `sha256 e992156c63ac94cde0a1e25a090c740407dabc7efb963fad510d367b5206c8a2`) was opened and its
   `assets/oss_licenses.html` is **44,799 B**, `sha256 8505b13a3e38ce989093fc2642708129d06d5559a81fb0fbba0d2927389fca09`
   — byte-identical to the committed page. Both LiteRT entries, the Caffe notice and the
   `TODO(owner)` comment probed PRESENT inside the packaged entry, and a deliberately wrong control
   probe MISSING; the same APK carries `lib/arm64-v8a/libLiteRt.so` (5,104,832 B, `6ddc1b3d…`) and
   `assets/libLiteRtDispatch_MediaTek.so` (409,728 B, `9e963c56…`), the pinned 2.1.1 bytes. **A
   debug APK is not a release bundle**: no 4.16.0 `bundleRelease` has been opened, and one read of
   its `base/assets/oss_licenses.html` is what closes that gap before the upload.

   **The NeuroPilot Express SDK licence was ACCEPTED by the owner on 2026-09-25 — *"we already
   agreed to the license"* — and the page CHANGED once more, so the P3a read above no longer
   describes it.** That licence is the one MediaTek's host compiler comes under, and the mt6989
   pair was compiled with it (`tools/mtk-apu/README.md`; design §2.1 and the sheet's §7). The
   `TODO(owner)` comment beside the MediaTek entry is gone; in its place the page carries a plain
   provenance entry, `id="mediatek-apu-models"`: the two model files in the
   `npu_turbo_mt6989_enc` / `npu_turbo_mt6989_dec` packs were compiled with MediaTek's NeuroPilot
   Express SDK (the host compiler, NeuroPilot v8_0_10; each file records its compiler as
   `adapter 8.2.30`) from the `openai/whisper-large-v3-turbo` weights (MIT), and are distributed
   only inside this app, for MediaTek chips, as that SDK's licence permits. No licence term beyond
   the one the README records is claimed, and none was invented. `OssNoticePackagingTest` holds
   the entry, its place beside the dispatch entry, the pack names against the census row, the
   compiler against the pack build's pin and the census's provenance, and that no `TODO(owner)` is
   left on the page. The OpenAI Whisper weights' MIT notice needed no change: the page already
   carries it ("OpenAI Whisper models — MIT License … (all tiers)", the MIT text in full at its
   foot), and the AI-chip files are those same weights. The committed page is now **45,303 B**,
   `sha256 4bce69be77470d41d70225eb54c2ac69dde7e2b00d8041670a20a05daee99592` in its CRLF
   working-tree form, and **both numbers were READ OUT OF AN ARTEFACT**, not derived: the
   4.16.0/114 **debug APK** (`:app:assembleDebug` of 2026-09-25 13:32 on
   `feat/mediatek-apu-tier-p3c` @ `00220736`, 203,321,512 B,
   `sha256 b830654afad41119f250e97b1a8ec1b4a4c4ca17b8f5bd74c6b3d569582cafcf`, badging
   `versionCode='114' versionName='4.16.0'`) was opened and its `assets/oss_licenses.html` is
   **45,303 B**, `sha256 4bce69be77470d41d70225eb54c2ac69dde7e2b00d8041670a20a05daee99592` —
   byte-identical to the committed page. The new entry and every fact it states (both pack names,
   NeuroPilot v8_0_10, `adapter 8.2.30`, the weights, the distribution term) and the OpenAI
   Whisper models MIT entry probed PRESENT inside the packaged entry, `TODO(owner)` and every HTML
   comment ABSENT, and a deliberately wrong control probe (`npu_turbo_mt6991_enc`) MISSING. **A
   debug APK is not a release bundle**: no `bundleRelease` carrying this page has been built or
   opened, and one read of its `base/assets/oss_licenses.html` is what closes that gap before
   114's upload. **And the build the owner uploaded to the internal track as 4.16.0/113 on
   2026-09-25 does not carry this page**: it was built before the page changed, so it carries the
   P3a page, the `TODO(owner)` comment included.

   **And the gap, stated as a gap: no release bundle of 4.5.2 has been opened, because none has been
   built.** A debug bundle is not a release bundle — different build type, no R8, the payload gates
   skipped, the packs empty — so reading the two rows above as one conclusion is an **inference**
   about an artefact that does not exist yet. What closes it is one `bundleRelease` of 4.5.2 and one
   read of its `base/assets/oss_licenses.html`. Until then the notices condition is written down,
   observed on the release path for the ASSET and observed in a debug bundle for the CONTENT — and
   **if that read ever comes back missing, the condition is unmet and the Korean pack must not be
   promoted, whatever this suite reports.** `OssNoticePackagingTest` derives the byte count and
   digest above from the page itself, so editing the page reddens the build until somebody opens a
   bundle again and records what they saw.
4. Run the §AL acceptance rows on device for every language being published.

---

# The evidence, one section per language

**These sections are the record of what each row was cleared ON**, and they stay whether a row is
outstanding or cleared: a clearance can be withdrawn, a withdrawn row is an outstanding row, and an
outstanding row whose section nobody wrote is a red suite on the day it is needed rather than before.

Every corpus term quoted below was read from its own publisher by the qualification table,
`docs/superpowers/research/2026-09-11-language-qualification-table.md` — that document is the
authority for them and carries the full reads, **except where its §10 errata or a section below
records a correction**, in which case `StreamingPackClearance.kt` is. Every **licence** was
additionally re-read live on 2026-09-12 and is recorded in the row's own `provenance` field.

## German — `de`

**The pack:** `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` at commit
`322557b0f88fc5a9823bc71027d4160f0c7612cc`, 70,938,534 B.

**CLEARED — Brandon Slacum, 2026-09-13.** He accepted reliance on the pinned Apache-2.0 declaration.
**The email below was never sent and the uploader never replied**; that hold is *retired*, not
satisfied. What was ACCEPTED with the clearance: that the declaration is unwitnessed by its author,
and that a corpus named in one line of front matter is a thin training disclosure. The question is
kept verbatim because it is what a re-opening of this row would send.

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

**CLEARED — Brandon Slacum, 2026-09-13.** The risk was ACCEPTED, not sized: the corpus is still
undisclosed and the optional email to `alphacep` was never sent. This row was outstanding because
nobody had decided it; it is cleared because somebody did.

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

**CLEARED — Brandon Slacum, 2026-09-13, WITHOUT the counsel read below.** His decision records that a
legal opinion is not a prerequisite for these unchanged weights, and none was sought. What was
ACCEPTED: the YODAS2 attribution chain two hops downstream — **unanswered, not answered**. The four
questions are kept verbatim because they are what a re-opening of this row would ask, and because
question 2's answer is the one thing already acted on: the collection-level credit is on the licences
screen, which is the most attribution the dataset makes possible.

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

**What changed on the yes:** the three edits above, **plus the credits line, which shipped in
4.5.2** — its wording was never counsel's to decide, since the four questions went with the
owner's decision of 2026-09-13. `app/src/main/assets/oss_licenses.html` now credits YODAS2 under
CC BY 3.0 to the ESPnet authors and to the channel (which is as far as that dataset makes
attribution possible), FLEURS under CC BY 4.0, Common Voice 17.0 as CC0-and-credited-anyway, and
`librivox-indonesia` as *Creative Commons, version unstated upstream* — left unstated here rather
than assumed. This is also the one pack whose MIT upstream supplies **no copyright holder and no
year**, and the page says so instead of inventing one.

## Korean — `ko`

**The pack:** `kangkyu/icefall-asr-ko-streaming-zipformer-72m` at
`db24b58d22736349eaeb34cc181ad0f3debf9903`, 72,969,700 B. The **cleanest licence cell in the survey**
— Apache-2.0 in a plain (parseable) README front matter, in the README's own License section, and
platform-surfaced in `cardData` and the tags. All three re-read on 2026-09-12.

**CLEARED — Brandon Slacum, 2026-09-13**, on AI-Hub's published FAQ below. **Not a reply from NIA**,
who were never written to. What was ACCEPTED: the **overseas scope** the FAQ does not address either
way. What was **not** a residual and is **not** optional: the attribution — it is the condition the
grant is traded for, and **Korean ships only if the KsponSpeech / AI-Hub (aihub.or.kr) / NIA credit
ships with it.**

**CORRECTED 2026-09-13, and this section is the correction.** What it said before: **two questions
for counsel** (qualification table C2) — whether the uploader could grant Apache-2.0 over weights
whose corpus rights vest in **NIA** *"whose commercial exploitation is conditioned on a separate
agreement"*, and whether the mandatory NIA attribution reaches this app through the weights. Both
were built out of the AI-Hub **데이터 이용정책**: 「※ 내국인만 데이터 신청이 가능합니다」, a separate
agreement for a party **outside Korea**, a separate agreement for **export**, use *"only for training
AI learning models"*, no transfer and no sale.

**Every one of those clauses governs ACCESS TO THE DATA.** We never applied for KsponSpeech, never
received it, never held it, and do not ship it — nothing in this app has ever been within a mile of
that policy's subject matter. Reading terms written for a data applicant as terms on a third party's
published weights is what made this a counsel row.

**The clause that actually governs us is in AI-Hub's FAQ** (`aihub.or.kr/aihubnews/faq/list.do`), and
it is a grant:

> AI 허브 데이터를 학습에 활용하여 개발한 AI 모델, 서비스 및 연구 결과물 등 2차 저작물은
> 영리·비영리 목적으로 자유롭게 활용하거나 판매·배포할 수 있습니다

— secondary works such as AI models developed by using AI-Hub data **for training** may be freely
used, or **sold and distributed**, for commercial and non-commercial purposes. **With attribution:**

> 이 경우 활용한 데이터셋의 정식 명칭과 AI 허브(aihub.or.kr)를 출처로 표기해 주시기 바랍니다

— cite the dataset's **official name** and **AI 허브 (aihub.or.kr)** as the source. And what is
prohibited is redistributing the data itself:

> 원본 데이터 자체를 제3자에게 제공하거나 배포하는 행위는 허용되지 않습니다

**So the attribution is the PRICE, not a nice-to-have.** The qualification table costed the credit at
0.1 d *"with no downside"*, as insurance against a counsel answer. It is not insurance. It is the
condition on which the grant is given, which means: **Korean ships only if its acknowledgement ships
with it.** It is on the licences screen (`app/src/main/assets/oss_licenses.html`) naming KsponSpeech,
AI-Hub (aihub.or.kr) and NIA (한국지능정보사회진흥원), and the suite fails the build if any of the
three goes missing.

**What is left is one residual, and it is your own risk call.** The FAQ does not address a party
**outside Korea** either way — it neither extends the secondary-works grant to one nor withholds it.
There is nothing further published to read, so this can only be accepted or refused, not researched.
A Korean-language clarification request to AI-Hub is **optional extra evidence, not a condition**.

**Where the rest of the evidence lives.** The README's own data table names *"KsponSpeech 1,000h
(AIHub dataset 123)"* and links `aihub.or.kr/aihubdata/data/view.do?dataSetSn=123` — both re-read at
the pinned commit on 2026-09-12. The licence cell itself is the **cleanest in the survey** and is not
in question.

**Already done, and not to be undone:** the Korean canary clip is **FLEURS** and deliberately **not**
the k2-fsa mirror's `test_wavs`, which are AI-Hub audio.

## Chinese — `zh` (the bilingual Chinese-English pack)

**The pack:** `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t` at
`e2382758de9a0219b4efe682b95af30b399db3b8`, 49,752,335 B. Apache-2.0, read in the front matter and
platform-surfaced on every link of the chain (re-read 2026-09-12). This is the **50 MB V1 export**,
not its 198 MB sibling.

**CLEARED — Brandon Slacum, 2026-09-13**, on the corrected basis below: an Apache-2.0 grant over an
**undisclosed** corpus, which is Russian's shape exactly. What was ACCEPTED is that unsized risk. No
counsel opinion was sought and nothing was ever sent: the corpus claim that had **designated** this
a counsel row went with the correction, and the designation went with it.

**CORRECTED 2026-09-13, and this section is the correction.** What it said before: that the weights
were trained on **WenetSpeech-L** (12,000 h), whose publisher states the corpus is *"available to
download for non-commercial purposes"*, and that this **named** restriction made `zh` the weakest row
in the survey — *"the heaviest corpus question in the document"*, a **counsel** row wanting a written
opinion. **That is not established, and the claim is withdrawn.** Three reads say so:

- the exact shipped mirror's own 4,115-byte card carries an environment dump reading
  `training_subset: 'mix'`;
- the official **sherpa-onnx documentation** describes an *internal* corpus;
- `training_subset: '12k_hour'` — WenetSpeech's own name for its L subset — is on the card of the
  **fork parent**, `pfluo/k2fsa-zipformer-chinese-english-mixed`, and nowhere on this row's.

**So this row is `LINEAGE`, not a corpus term.** A parent's training disclosure is evidence about the
parent; it is not a disclosure about the bytes this catalogue downloads, which is the same
distinction Russian's row already makes about its untagged mirror. What is left is an **apache-2.0
grant over an undisclosed corpus** — Russian's exact shape.

**So this is now your own risk call**, not counsel's: no written opinion can size a corpus nobody has
named, and paying for one would buy an answer about `pfluo`'s bytes rather than about ours. If you are
comfortable, record yourself as the grantor with that reasoning in `because`.

**What must NOT be written back into this record.** Two things, because both are one search away:

1. the *"named non-commercial restriction"* framing above — it is in the qualification table
   (`§7`, correction 6) and in the earlier survey, and the table now carries this correction in its
   own errata;
2. the card's **evaluation** numbers as **training** corpora. This row publishes AiShell-1, TEST_NET
   and TEST_MEETING figures. A number measured on a corpus is not a disclosure that the weights were
   trained on it, and a row with no corpus is exactly where that conversion gets made. The suite pins
   it across every row.

**What changed on the yes:** the three edits above — **and the notices, which shipped in 4.5.2.**
The page's Chinese entry still names **no corpus**, and after this correction that is simply
accurate: there is no corpus to name. What changed is that the silence is now *stated* — the page
says in terms that this pack and the Russian one disclose no training corpus, quotes the card's own
`training_subset: 'mix'`, and says that no corpus has been attributed on a resemblance and no
*evaluation* set is listed as if it were a training one.

**The consequence, corrected.** It used to be that nothing on that page pointed at this row, so a
condition attached later would have to be noticed by a human. That is no longer so:
`OssNoticeTest.everyPackTheAppCanFetchHasAnAttributionRowWithItsPinnedRevision` pins a
`id="pack-zh"` row carrying this pack's own 40-character revision, so a **re-pin** to different
bytes reddens the build until the page is updated. A new *corpus term* still would not prompt
itself — no test can know about a document nobody has read — but the row it would have to be
written into now exists and cannot quietly disappear.

**One thing the correction does NOT dissolve.** A Chinese-**only** alternative is still worse, and for
reasons that have nothing to do with this row: every one of them carries WenetSpeech **plus**
AISHELL-2 **plus** KeSpeech as *declared* corpora — three agreements, one of which (KeSpeech) bars
*"Adaptations"* and is revocable on the licensor's notice, while another (`aidatatang_200zh`) is
delisted so its licence cannot even be read. Refusing this row does not open a cheaper door.

---

# The two cleared on 2026-09-12, before the owner's decision

All seven rows are cleared; these two were cleared a day earlier and by a different grantor, which is
why they are written up apart from the five above and why the pin test holds the owner-decision
census as its own literal. Collapsing the two grants into one list is how a decision quietly grows to
cover something it never named.

Neither needs anything, and both are recorded with their evidence in `PackClearanceRecord` so the
record is a complete census rather than a list of problems. **They get their own sections for the
same reason the five above do:** a clearance can be withdrawn, a withdrawn row is an outstanding row,
and an outstanding row the suite cannot find a section for is a red suite — so the section exists
before it is needed rather than as the surprise fourth edit of a withdrawal.

## English — `en`

**The pack:** `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26` at
`672fbf1b30579d6585301139bb363f42a0ad4a24`, 72,654,782 B.

Apache-2.0 (`cardData.license` plus the `license:apache-2.0` tag, re-read 2026-09-12), LibriSpeech
(CC BY 4.0), no agreement anywhere in the lineage. It has been in the built product since 4.4.0; the
grantor of record is you, by shipping it. If this one is ever reopened it is
**your own risk call** — not counsel's, and not the uploader's.

## French — `fr`

**The pack:** `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14` at
`3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73`, 128,227,451 B.

Apache-2.0 read **twice**: the repository's own 204-byte non-LFS README front matter, and
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
opinion**, and it is yours to withdraw. Keeping it is **your own risk call** in exactly the way
Russian's is; if you would rather it waited for the same research the other five are getting — a
**counsel** read, or your own — withdrawing it is the mirror of the three edits above, in the same
three places:

**1. The verdict** — `FR` in `StreamingPackClearance.kt`, back to an open question. Everything else
in the row (licence, `readAt`, `readOn`, `provenance`, `corpora`, `pinnedCommit`) stays; only the
verdict changes, and it may not be blank — a row that says "not cleared" without saying what is
missing leaves you nothing to do, and the suite checks both fields:

```kotlin
verdict = ClearanceVerdict.Outstanding(
    question = "the apache-2.0 grant and the CC0/CC BY 4.0 corpus lineage were read by the " +
        "controller, not by a lawyer — is that read enough to publish on?",
    action = "decide it on the same terms as Russian, or send it to counsel with the other three",
    answerer = ClearanceAnswerer.OWNER,
),
```

**2. The switch** — `"fr"` out of `PRODUCTION_CLEARED`.

**3. The pin** — `allSevenAreClearedAndFiveAreTheOwnersDecisionOf20260913`, the reverse of a
clearance: `"fr"` out of the cleared list, out of the switch, and **into** the outstanding census as
`"fr" to ClearanceAnswerer.OWNER` (in the record's order — `fr` is the second row, so the pair goes
**first**). Rename the test. If you record `COUNSEL` instead of `OWNER` in edit 1, the census pair
takes `COUNSEL` too — the suite reads the answerer from the record rather than from this list. The
**owner-decision census** is untouched by a French withdrawal, because `fr` was never in it; a
withdrawal of one of the five the owner decided takes that literal as well.

**And one conditional fourth edit, which applies to any withdrawal, not just French's.** The suite
asks an OUTSTANDING row's section for the words of *its own* answerer, in this document's own
spelling — `uploader` for `UPSTREAM_AUTHOR`, `your own risk call` for `OWNER`, `counsel` for
`COUNSEL`. So **if the answerer you record in edit 1 is one this row's section does not already
name, add the phrase to the section**: one sentence, in the row's own words, saying who the question
now goes to. That is the only edit a withdrawal can take beyond the three above, and it is why a
cleared row's section is asked (before any withdrawal) to name *some* answerer rather than a
particular one — a requirement keyed to one answerer is a red suite the day a row whose answerer is
a different one changes state, which is exactly how this paragraph came to be written. French's
section names all three phrases today, so its own withdrawal takes none of this.

Then the suite is green again, and production is simply withheld on six languages instead of five.
(Withdrawal was measured too: the earlier version of this paragraph named two edits and claimed the
suite would stay green; applied, it was **four failures**, one of them an assertion that this
document had no French section to point at. It has one now — that is why the two cleared languages
are written up like the outstanding five.)
