# Live words, per language — the definitive qualification table

**Synthesis, 2026-09-11.** Seven per-language audits (`qual-{fr,ru,zh,ko,id,zh-en,et-en}.md`) and two
false-negative hunts (`hunt-es-it-pt.md`, `hunt-ja-tr-vi-nl-pl.md`), each of which re-read its own primary
sources rather than trusting
`docs/superpowers/research/2026-09-11-multilingual-streaming-research.md`. This document is the answer to
the owner's ruling of 2026-09-11:

> *"all of the packs that this model supports for a real time word for word streaming, all languages that
> it provides, we should match that to our system, and we just get it by the language that you select with
> onboarding. If it is auto or if the language does not have a streaming model, then we just go back to
> VAD, normal VAD activity that commits the chunks."*

**Reading rules, applied at every sentence.** **READ** = an auditor fetched the bytes and parsed them on
2026-09-11 (tree API with LFS oids, HTTP Range read of the int8 encoder tail, `tokens.txt` counted line by
line, licence text fetched at `resolve/main`). **INFERRED** = reasoning over those reads. **UNMEASURED /
NOT PUBLISHED** = no number exists. **UNVERIFIED** = an upstream card metric nobody reproduced.

**One caveat that applies to every row and is stated once.** Every "loads" verdict below is
**metadata-derived**: `model_type`, the two head-dims keys and `decode_chunk_len` say
`OnlineZipformer2TransducerModel` will accept these graphs. **No model in this document has been run
through the shipped AAR.** Nothing here substitutes for the survey's §5.3 rung-1 gate.

**Second caveat.** Both hunts exhausted their WebSearch budget mid-run, so discovery was API / tree /
release-index based, not search-engine corroborated. The two best finds (`jgsch`-tr, `ken4869`-ja) came
from a **tag** enumeration, not a name search — which is itself evidence a differently-shaped index would
surface more.

---

## 0. THE ANSWER IN ONE SCREEN

1. **"All languages" resolves to seven, not eight, and not eighteen.** English (shipping) plus **fr, de,
   ru, id, ko, zh** — the last four each carrying a named, unbounded cost that is the owner's or counsel's
   to pay, not engineering's. **421,369,659 B of new payload** for six new packs.
2. **Not one row came back GREEN. All seven audited rows are AMBER.** The survey's cheapest-row framing
   survives, but every single language carries at least one cost the survey did not record.
3. **The Spanish hole is NOT a runtime hole — and it is still a hole.** A Spanish streaming `zipformer2`
   that loads through `sherpa-onnx-1.13.7.aar` with zero new native bytes exists **inside sherpa-onnx's own
   official release**. What blocks it is a **0-byte LICENSE** and a **1,280 ms decode cadence — 4× the
   app's**. §3. The $150/mo decision is **not** dissolved; it is re-aimed, and it needs a third question.
4. **The measured 0.4 s word lag is a property of `decode_chunk_len = 32`, and it is per-pack.**
   en/de/fr/zh/zh-en are 32 (320 ms). **ru/id/tr/et/ja/lyr-pt are 64 (640 ms). Every Kroko community build
   is 128 (1,280 ms) or 256 (2,560 ms).** The survey has no cadence column and its §0 claim that Route A
   "keeps the measured 0.4 s lag" is **established for German only**. §1, §5-item-9.
5. **There are FIVE seam defects, not three.** The survey's list gains: **`PAD_MS = 500` is a 320 ms /
   `T = 45` measurement and is ~285-320 ms short for every `T = 77` pack**, which silently drops the last
   word of every utterance and can Fail the canary into a process-wide `disabled`; and **`PreviewText.before()`
   concatenates tokens (spaces present) while the live path renders `result.text` (spaces absent)**, so a
   CJK-classified pack changes its spacing mid-utterance at every cap cut. §6.
6. **Korean is a `zh`-class row, not a `de`-class one.** sherpa 1.13.7's `IsCJK` covers `0xA840-0xD7AF`,
   which **contains Hangul Syllables U+AC00-U+D7A3**, so `RemoveSpaceBetweenCjk` deletes **every Korean
   word space** — and inconsistently (spaces survive before an ASCII digit). "Words appear" is **false for
   Korean** until a 0.25 d pure-Kotlin fix lands. The largest undiscovered cost in the run.
7. **Four survey numbers a build must not copy forward.** fr's WER is **10.57, not 9.95** (9.95 is the
   **GigaSpeech** checkpoint and GigaSpeech is non-commercial-only). ko's **8.25 CER / RTF 0.038 are the
   chunk-64 = 1.28 s export**, a different model; ours is **8.635 / 0.052**, and neither is greedy. ru's
   untagged-mirror worry is **discharged cryptographically**; the real counsel item is its **undisclosed
   training corpus**. zh-en's corpus is **not "internal"** — it is **WenetSpeech-L 12k h**, non-commercial
   by its own page. §7.
8. **The one row the survey priced most expensively is the one it should price least.** A **50 MB**
   bilingual zh-en pack exists with a **byte-identical `tokens.txt`** to the 198 MB row and an encoder
   **0.60× the English pack's** — likely *faster* than what ships today, at a quarter of the bytes, serving
   Chinese **and** English in one utterance. §1 row zh-en, §2.

---

## 1. THE TABLE

Ordered by the app's own written statement of priority: the index of
`PreferencesManager.SUPPORTED_LANGUAGES` (`:541-597`, read in the checkout today), counting `"auto"` as 0 —
so the number is the language's 1-based position among the 54 selectable languages. I re-derived the whole
list: `en 1 · es 2 · fr 3 · de 4 · it 5 · pt 6 · nl 7 · pl 8 · ru 9 · zh 10 · ja 11 · ko 12 · ar 13 ·
hi 14 · tr 15 · vi 16 · th 17 · id 18 · … · et 36 · … · fa 39 · bn 41`.
(**Correction to `qual-et-en.md`:** it reports Estonian as #37 — that counts `"auto"` as a language.
Estonian is **#36**. The survey's own numbering is right.)

Pack bytes = the **sum of the four files a pack would carry**, from the HF tree API with LFS oids. Never a
release tar: the shipped English pack is **72,654,782 B on disk against a 310,414,022 B tar** (4.3×
over-statement) — and the trap cuts both ways, since the official Spanish tar **under**-states its pack by
20% and PengChengStarling's tar under-states by 80 MB. Badge = `StreamingPackCatalog.sizeBadge`
= `(bytes + 500_000) / 1_000_000` MB (`:76-83`, verified).

### 1.1 The rows that can carry live words

| # | Lang | Repo (commit to pin) | Pack bytes / badge | `model_type` · cadence | Seam change it needs | Licence · where the grant was read | Corpus · counsel? | Token facts the copy derives from | Accuracy vs whisper-**small** | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **1** | **en** | `csukuangfj/…-streaming-zipformer-en-2023-06-26` `672fbf1b` | **72,654,782** · **73 MB** | `zipformer2` · **32 = 320 ms**, `T 45` | — **SHIPPING** | apache-2.0, platform-surfaced | LibriSpeech + GigaSpeech-free | 502 lines · digits only `#0/#1` · lowercase only the 3 specials · `▁` U+2581 on 339 · 1 real punct piece (`'`) | LS 3.06/7.79 greedy @320 ms vs **small.en 3.1** | **SHIPPING.** Measured: lag p50 **0.401** / p95 **0.523** s, RTF **0.054** @2 threads, **0 retractions**, load 802-860 ms |
| **2** | **es** | `csukuangfj/…-streaming-zipformer-es-kroko-2025-08-06` `20cf7a49` | **155,838,792** · **156 MB** | `zipformer2` ✓ head-dims present · **128 = 1,280 ms**, `T 141` | **none** — the hard-coded `"zipformer2"` is already right | **UNREADABLE.** `license: other`, `license_name: "test"`, **`LICENSE` is 0 bytes**; prose says "CC-BY-SA" with **no version**. READ at `Banafo/Kroko-ASR` front matter + a 0-byte fetch | not stated by Kroko | 652 lines · **564 lowercase, 68 uppercase, 61 accented, 16 digit-bearing, 8 punctuation** | **NOT PUBLISHED** for any Kroko community model. Denominator CV9-es **10.3** | **REFUSED** — 4× cadence + no grant a licensee can rely on. §3 |
| **3** | **fr** | `shaojieli/…-streaming-zipformer-fr-2023-04-14` `3db9565d` | **128,227,451** · **128 MB** | **`zipformer` (V1)** · `query_head_dims` + `value_head_dims` **ABSENT** · **32 = 320 ms**, `T 39` | **MANDATORY: per-pack `modelType`.** Unpaid it is `_Exit(-1)` — an uncatchable process kill, not a caught load failure | **apache-2.0.** READ in the 204-B non-LFS front matter at `resolve/main` **and** platform-surfaced (`cardData.license` + `license:` tag — note the *top-level* `license` key is absent) | CommonVoice 12.0 fr (CC0) on a LibriSpeech (CC BY 4.0) pretrain. **GigaSpeech is NOT in the exported lineage** (INFERRED from epoch 29 avg 9 ↔ the 10.57 row, three ways) ⇒ **no counsel** | 502 lines · 2 digit-bearing (`#0/#1`) · 3 lowercase (specials) · 495 uppercase · `▁` at id 4 on 239 · 4 punct incl. `'` at id 7 (**same id as English**) · 15 accented **capitals** | **10.57** greedy CV-fr test (repo's own `wer-summary`) ÷ **22.7** CV9 = **0.47×** | **AMBER · SHIP NOW.** Needs no email, no re-mirror, no canary hunt (Kokoro `ff_siwis`). Pays the crash fix the seam owes anyway |
| **4** | **de** | `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` | **70,938,534** · **71 MB** | `zipformer2` ✓ head-dims present · **32 = 320 ms**, `T 45` | **none** | **apache-2.0, author-declared in a 180-byte Xet/LFS README the platform does NOT parse** (API: `license: Not specified`). READ at `resolve/main` ⇒ **ONE EMAIL** | CommonVoice 17.0, declared on the card | 502 lines · digits only `#0/#1` · lowercase only the 3 specials · `▁` at id 3 · ALL-CAPS German BPE | **test 10.58 / dev 8.58**, greedy, **streaming**, chunk-32-left-128, from the repo's own summary files ÷ **13.0** CV9 = **0.81×** | **AMBER · SHIP NOW after one email.** Cheapest row. +0.25 d for subdirectory + **commas in filenames**; no Kokoro German voice. **NOT re-audited this run** — carried from the survey's own verified reads |
| **5** | **it** | `kouhxp/…-streaming-zipformer-it-kroko` `e748910d` | **155,839,076** · **156 MB** | `zipformer2` ✓ head-dims present · **128 = 1,280 ms** | none | **NOTHING.** Mirror has **no README at all** (`resolve/main/README.md` → **404**), `cardData: null`. Weights are `Kroko-IT-Community-64-L` (encoder **byte-identical**) and **Italian is ABSENT from Kroko's own `language:` list** `[en,fr,de,es,pt]` | not stated | **`tokens.txt` DEFECTIVE** — carries a 4-byte LE length prefix (`db170000` = 6,103), so line 0 is `\xdb\x17\x00\x00<blk> 0`. Once stripped: 650 lines · 557 lowercase, 86 uppercase, **113 accented**, **0 digits**, 8 punct | **NOT PUBLISHED.** Denominator CV9-it **16.0** | **REFUSED** — weakest Kroko grant of the three + 4× cadence + a corrupt token file |
| **6** | **pt** | **A:** `ken4869/lyr-asr-pt-streaming` `4a093bf4` — **B:** `kouhxp/…-pt-kroko` `d0472d06` | **A: 158,921,070 · 159 MB** — B: 155,839,204 · 156 MB | A: `zipformer2` ✓ · **64 = 640 ms**, `T 77` — B: `zipformer2` · **128** | none | **A: an EXPLICIT REFUSAL, READ in Japanese** — 「ライセンス未設定（全権利留保）… 第三者による利用を許諾するものではありません」 + 「LYR アプリ専用」; no `LICENSE/LICENCE/COPYING/NOTICE` (all 404); contact `hello@lyr.jp`. **B:** Kroko's 0-byte LICENSE, but **pt IS on Kroko's list** | **A: CLEAN** — YODAS2 `pt000` manual subset, 1,556.6 h, CC BY 3.0 | A: 1,000 lines · **TAB-separated** · 577 `▁` · **981 lowercase, ZERO uppercase**, 97 accented, **17 digit pieces**, 1 punct (`'`) — the closest shape to English of any non-English row | A card (**UNVERIFIED, in-domain YODAS**): WER 0.1318 / CER 0.0607, RTF p50 0.075, beating nemotron-3.5-streaming-0.6b by −0.0749 [−0.0844,−0.0657]. **Cannot be divided by CV9-pt 12.5** | **REFUSED today — and the cheapest "yes" in the set.** Two independent one-email routes (lyr *and* Kroko) |
| **7** | **nl** | `kouhxp/…-streaming-zipformer-nl-kroko` | **155,839,210** · **156 MB** | `zipformer2` ✓ head-dims present · **128 = 1,280 ms**, `T 141` | none | Kroko's 0-byte LICENSE; **nl is ABSENT from Kroko's `language:` list**; all three mirrors `license: None` | not stated | Same 4-byte prefix defect (`61180000`). Once stripped: 650 lines · 555 lowercase, 92 uppercase, **ALL TEN ASCII digits as tokens**, **22 punct**, 56 Latin-Extended | **NOT PUBLISHED.** Denominator CV9-nl **14.2** | **REFUSED** — **and this is NOT a "wrong runtime" row.** `Kroko-NL-Community-64-L` (640 ms) exists as a `.data` bundle; **no int8 sherpa export of it does** |
| **8** | **pl** | **none exists** | — | — | — | — | — | — | Denominator CV9-pl **16.9** | **REFUSED — exhaustive negative.** 8 independent indexes checked incl. all 498 `asr-models` assets, all 43 icefall `egs/`, 189 `filter=sherpa-onnx` repos, `author=alphacep`. **No `Kroko-PL-*` community file.** If a second runtime is ever authorised, **pl is the language that justifies it** (Vosk-pl 50 MB Apache-2.0, 18.36 CV-test = **1.09×** — the best ratio on the whole Vosk table) |
| **9** | **ru** | `csukuangfj/…-streaming-zipformer-small-ru-vosk-int8-2025-08-16` `31fa603e` | **28,572,945** · **29 MB** — cheapest in the survey | `zipformer2` ✓ head-dims present · **64 = 640 ms**, `T 77` | **per-pack `PAD_MS` ≥ ~820 ms — a defect, not polish** | **apache-2.0 at upstream** `alphacep/vosk-model-small-streaming-ru`, **platform-surfaced**. Mirror untagged — **discharged cryptographically**: all four pack files byte-identical (3 LFS oids + a `sha256` computed on `tokens.txt`) | **UNDISCLOSED.** The card says only *"trained with k2-fsa/icefall on Russian data"*. ⇒ **one email**, nice-to-have not gating | 502 lines · digits only `#0/#1` · **498 lowercase Cyrillic, ZERO uppercase** · `▁` at id 7 on 262 · 4 punct (specials + a standalone `-`) · all 33 Russian letters | **11.2** CV12 (author's own 11-set benchmark) / 11.3 card ÷ **15.0** CV9 = **0.75×**. On the author's 11-set **mean** it beats Whisper Large-v3 (14.67 vs 16.21) | **AMBER · SHIP WITH COST.** Fewest seam changes of any row. Free upgrade recorded: `alphacep/vosk-model-streaming-ru` v0.56, 71,694,239 B, **byte-identical `tokens.txt`**, same cadence, +43 MB |
| **10** | **zh** | `k2-fsa/…-streaming-zipformer-multi-zh-hans-2023-12-12` `ac54a23c` | **72,470,080** · **72 MB** | `zipformer2` ✓ head-dims present · **32 = 320 ms** ✓, `T 45` | **canary RULE (not just clip)** · copy noun · **2 new `PreviewText` defects** | **apache-2.0**, READ in a 28-byte front matter **and** platform-surfaced | **THE HEAVIEST COUNSEL QUESTION IN THE DOCUMENT.** 5 explicitly non-commercial corpora (WenetSpeech, KeSpeech, SLR38, SLR47, SLR68), **3 also no-derivatives**, KeSpeech bars *"Adaptations"* and is **revocable on notice**, `aidatatang_200zh` **delisted** (openslr.org/62 → **404**), AISHELL-2 **application-gated**, WenetSpeech **disclaims the audio copyright** | 2,002 lines · **1,426 single Han, ZERO multi-char Han** · 315 `▁` pieces · 2 digit-bearing · 0 lowercase, 0 uppercase · byte-fallback `<0x00>`…`<0xFF>` · **BUT its own published hypotheses carry 31 uppercase Latin acronyms in 25,394 chars** | 17 greedy **streaming** CERs published for the same checkpoint at the **other** export (chunk-32/left-256): aishell-1 test **3.63**, wenetspeech test_net **9.66** ÷ whisper-small **20.8** FLEURS / 29.4 CV9 ≈ **0.2×**. **Nothing at our chunk-16 export** | **AMBER · SHIP WITH COST.** No re-mirror, no `modelType`, no cadence change, 8 Kokoro voices. ≈**3.35 d** |
| **—** | **zh-en** | **50 MB:** `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t` `e2382758` — **198 MB:** `csukuangfj/…-bilingual-zh-en-2023-02-20` `98590b7e` | **49,752,335 · 50 MB** — or 198,270,793 · 198 MB | **`zipformer` (V1)** · head-dims **+ `num_heads` ABSENT** · **32 = 320 ms**, `T 39` | **per-pack `modelType`** (free if fr lands first) · copy noun · **+0.25 d path split on the 50 MB row** | **apache-2.0**, READ in front matter **and** platform-surfaced, on every link of the chain | **WenetSpeech-L 12k h** — the author's own `training_subset: '12k_hour'`. *"available to download for non-commercial purposes … WenetSpeech doesn't own the copyright of the audios"* + a Google-Form mailed `PASSWORD`. **A named restriction is a worse position than an unnamed unknown** | **`tokens.txt` BYTE-IDENTICAL on both rows** (`a8e0e4ec…`) · 6,257 lines · 5,755 single Hanzi (zero multi-char) · **494 all-caps Latin BPE at ids 3-496** (384 = **77.6%** shared with the English pack) · **ONE emittable numeral, `2` at id 4883** · **ZERO punctuation** · 328 `▁`, all Latin | 198 MB card (beam/chunk-64, **UNVERIFIED**): AiShell-1 **3.04**, TEST_NET 8.97. 50 MB card: 4.79 / 11.6. Pessimistic greedy/chunk-32 band **0.26×-0.75×**. **English half UNMEASURED — the number the owner's use case turns on** | **AMBER · SHIP WITH COST — and the only row in the catalogue that puts anything on the strip when an English speaker talks mid-Chinese.** Verified code-switch output: `这是第一种第二种叫呃与 ALWAYS ALWAYS什么意思啊`. **Ship the 50 MB row.** Its canary is the **bundled English clip, unchanged** (a −1.5 d correction) |
| **11** | **ja** | *(no shippable row)* | — | — | — | `ken4869/lyr-asr-ja-streaming` **loads** (166,323,946 B, `zipformer2`, 640 ms) and is **explicitly all-rights-reserved** in an unparsed README | `egs/csj` is a licensed NINJAL corpus | lyr-ja: 3,878 lines, TAB-separated, **character vocabulary — ZERO `▁`**, 59 punct incl. fullwidth, **no plain ASCII digit token** (only `①②③⑤⑨`) ⇒ **a Japanese previewer that cannot write "2026"** | icefall `egs/csj` publishes **greedy @320 ms CER 5.43/4.14/4.31** vs Vosk-ja **9.52** on the **same corpus and metric** = **0.57×** — the only like-for-like ja comparison anywhere. **Its weights repo `TeoWenShen/…` returns HTTP 401** | **REFUSED.** Four plausible ja/vi ONNX candidates killed by the **offline tell** (`comment = "non-streaming zipformer2"`, no `decode_chunk_len`, head-dims missing). `ayousanz/kodama-ja-streaming-small` is apache-2.0 and genuinely streaming — and is **Moonshine** (no `OnlineMoonshine*` class; 5-8 files vs 4 slots) |
| **12** | **ko** | `kangkyu/icefall-asr-ko-streaming-zipformer-72m` `db24b58d` | **72,969,700** · **73 MB** | `zipformer2` ✓ head-dims present · **32 = 320 ms** ✓, `T 45` (the **chunk-16** export) | **strip text from `tokens` not `text`** (0.25 d, closes the space deletion **and** a live defect) · canary RULE (shared with zh) · **all three copy flags** · unrenderable-token deny-list | **apache-2.0 — the cleanest cell in the survey.** READ in a 7,926-B plain-blob front matter **and** platform-surfaced in `tags` + `cardData`. **No email needed** | **KsponSpeech = AI-Hub dataset 123 (NIA).** Policy READ clause by clause: *"※ 내국인만 데이터 신청이 가능합니다"*; a party **outside Korea** needs a separate agreement; **export** needs a separate agreement; *"only for training AI learning models"*; no transfer/sale; **attribution to NIA is MANDATORY and extends to derivative works**. ⇒ **counsel, 2 questions** | 2,460 lines · 2,457 **single characters** · 2,301 Hangul syllables · **`▁` appears ONCE as its own token at id 3, never as a prefix** · **10 standalone ASCII digits** · **32 punctuation pieces** · **26 uppercase + 25 lowercase Latin** · **7 unrenderable tokens** (U+FFFD, U+007F, 5 PUA) | **8.635** CER chunk-16 **beam-8** (no greedy CER published at any chunk); johnBamma's greedy@320 ms **10.21/11.07** as the pessimistic stand-in ⇒ **~0.48×-0.59×** of an INFERRED whisper-small ~18.1 CER (FLEURS-ko **19.6 WER**, not metric-comparable) | **AMBER · SHIP WITH COST.** ≈**3.6 d** + counsel. **A `zh`-class row, not a `de`-class one** — see §6(4) |
| **15** | **tr** | `jgsch/sherpa-onnx-zipformer-tr` | **70,907,649** · **71 MB** — 0.98× the English pack | `zipformer2` ✓ head-dims present · **64 = 640 ms**, `T 77` | **none** — loads with the hard-coded `"zipformer2"` unchanged | **NO GRANT OF ANY KIND EXISTS.** `README.md`, `readme.md`, `LICENSE`, `LICENSE.txt` — **all four 404**; `license: None`, `cardData: null`; the author's 3 public repos are all unlicensed. **Worse than German: there is nothing to confirm, only something to create** | unknown | 500 lines · 193 `▁` · **448 lowercase / 34 uppercase (MIXED)** · **ZERO digits** · **18 punctuation-only pieces** · 163 Latin-Extended | The bar is now known: `duxx/turkish-stt-zipformer` (**CC-BY-NC-4.0, dead end**) publishes **13.09** CV17-tr dev ÷ **23.7** CV9 = **0.55×** | **REFUSED.** The survey's `TBD` is closed and the survey's implication that no Turkish model loads is **wrong** — a 71 MB `zipformer2` Turkish pack is sitting there, licence-silent |
| **16** | **vi** | `csukuangfj/…-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` | **338,873,347** · **339 MB** — **139 MB over Play's 200 MB cellular line** | `zipformer2` ✓ head-dims present · **32 = 320 ms** — **the app's exact cadence** | **a one-line NATIVE patch** ⇒ rebuilt AAR + new native bytes | apache-2.0 on `stdo/PengChengStarling`; the mirror's README is a 105-byte GitHub pointer | GigaSpeech2 / reazonspeech / mgb2 | 16,016 lines · ids 2-9 are literally `<ZH> <EN> <VI> <RU> <JA> <AR> <TH> <ID>` · 8 languages' habits **unioned** ⇒ all three flags true, `normalizeLocale` **undefinable** | **vi gigaspeech2-vi test `%WER 7.09`**, read from the `errs-*.txt` file itself. **ja 13.34 is a CER reported as a WER** — not comparable to FLEURS-ja 12.0 | **REFUSED — but the door is narrower than the survey says.** v1.13.7's own `GetEmptyResult()` writes the initial token in **exactly** the langtag's slot with `int32_t blank_id = 0; // always 0`. **Not a private fork — a one-line native patch.** Unreachable from Kotlin (both seams checked). And **the published 7.09 is a langtag number, not a sherpa number** |
| **18** | **id** | `spacewave/sherpa-onnx-streaming-zipformer2-id` `4e5a13cb` | **70,908,694** · **71 MB** | `zipformer2` ✓ head-dims present · **64 = 640 ms**, `T 77` | **per-pack `PAD_MS`** · parameterise `ScriptedRecognizer` · 3 KDoc cadence lines | **MIT.** READ in a 902-B non-LFS front matter **and** platform-surfaced via `cardData.license` — **the *top-level* `license` key is absent**, so a shallow read reports "Not specified": **the German trap in a second costume, by key naming rather than Xet storage** | **`espnet/yodas2`, CC BY 3.0, YouTube-derived.** **85.6% of the Indonesian label hours are AUTO-captions** (8,463.61 of 9,883.70 h). `video_id` is deliberately **not** YouTube's ⇒ **attribution structurally unsatisfiable**. Chain of title = a search flag that *"should (mostly)"* hold, extrapolated per channel, backed by a takedown mailbox. + `librivox-indonesia` tagged only `cc`, no version ⇒ **counsel, 4 questions** | **The cleanest vocabulary in the survey.** 500 lines · **ZERO digit-bearing pieces** (cleaner than English's `#0/#1`) · 3 lowercase (specials) · 496 uppercase · `▁` on 359 at id 7 · **1 punctuation piece and it is `<sos/eos>`** · **no accents, Latin only** · all 26 A-Z as single pieces | Card (**all four UNVERIFIED** — no decode log, no `test_wavs`, no eval script, decode mode unstated): CV **11.58** ÷ **18.4** = **0.63×**, FLEURS **8.96** ÷ **16.3** = **0.55×** | **AMBER · SHIP WITH COST.** Canary is the strongest non-English story: `▁SATU 134 ▁DUA 164 ▁TIGA 231 ▁EMPAT 324 ▁LIMA 320` all **whole words**, **no numeral aliases needed**, verifiable by a non-speaker |
| **36** | **et** | `TalTechNLP/streaming-zipformer-large.et-en` | **156,221,502** · **156 MB** | `zipformer2` ✓ head-dims present · **64 = 640 ms**, `T 77` | **all three copy flags `true` for the first time** · per-pack `PAD_MS` ≥ ~820 ms | **MIT — the strongest licence position of any candidate.** READ in a 701-B front matter **and** platform-surfaced; `TalTechNLP` is **the actual author**, not a re-uploader | ~1300 h Estonian transcribed + **~3000 h auto-transcribed TV broadcasts** + **~500 h English YouTube labelled by Whisper-turbo**. Direct evidence of uncuration: the Estonian vocabulary contains **two Devanagari letters** (`क`, `र`) ⇒ **counsel** | 1117 lines (`vocab_size 1000`; ids ≥1000 unreachable) · 617 `▁` · **106 uppercase-bearing, 908 lowercase-bearing** · **9 emittable standalone digits** · **23 pure-punctuation tokens** · 22 Latin diacritics + 2 Devanagari | **NOTHING PUBLISHED — no WER, no CER, no RTF.** Cannot be pre-screened at all; **both** sides would need measuring. whisper-small et = **67.2** CV9 / **51.3** FLEURS | **REFUSED / LAST.** **The accuracy ratio INVERTS**: the previewer would paint largely correct Estonian and whisper-small would type ~1 error in every 2 words over it. The rung-3 rule has **no arm** for that shape. Picker **#36**, behind 9 top-16 languages with no row at all |
| **39** | **fa** | `nemo-ctc-fa-shenava-*-streaming` ×3 | 11.7 / 31.0 / **99.3 MB** int8 | **CTC, not a transducer** | **+1.5 d** family surcharge (file-count change + a second config branch) | apache-2.0 | — | not audited this run | **NOT PUBLISHED** | **HOLD** — not re-audited; the CTC family is a different pack shape |
| **41** | **bn** | `csukuangfj2/…-streaming-zipformer-bn-vosk-2026-02-09` | **94,119,939 — fp32 ONLY** | — | **+1 d** (quantize and own the digests) | apache-2.0 upstream; mirror untagged | — | not audited this run | whisper-small bn **118.6** CV9 / **104.4** FLEURS ⇒ **the gate is meaningless** | **REFUSED** — whisper-small does not work in Bengali, so there is no ratio to pass |

### 1.2 Not in the picker, therefore unable to arm under the ruling

- **`alphacep/vosk-model-small-streaming-uz`** — **a row the survey does not have.** Vosk's
  streaming-**zipformer** generation (which is where the ru and bn rows come from) is `ru`, `bn`, `uz`.
  Uzbek is **absent from `SUPPORTED_LANGUAGES` entirely**, so it cannot be selected at onboarding and
  cannot arm. **WATCH:** that generation grew ru → bn → uz inside ~6 months; re-check
  `api/models?author=alphacep` for a `pl` or `nl` entry before any Stage-4 decision.

---

## 2. WHAT "ALL LANGUAGES" RESOLVES TO

The AAB is a measured **5,086,892,220 B**. Play is not the constraint on bytes (individual asset pack
1.5 GB; on-demand cumulative 30 GB) but the **pack count** is the real ceiling at **100**, and **4 slots
are used today**.

### 2.1 SHIP NOW — nothing blocks but our own engineering

| Lang | # | Pack bytes | Badge | The one thing gating it |
|---|---|---|---|---|
| **fr** | 3 | 128,227,451 | 128 MB | The per-pack `modelType` fix — **which is a crash fix the seam owes anyway**, not a French cost |
| **de** | 4 | 70,938,534 | 71 MB | **One email** to `daniel-dona` confirming the apache-2.0 in the unparsed README. Owner action, one line. If it comes back negative, de drops and this group is **fr alone at 128 MB** |
| | | **199,165,985 B** | **199 MB** | **2 new Play slots (6 of 100).** AAB → ≈**5.29 GB** at payload, ≈**5.25 GB** after deflate |

Neither row needs counsel. Neither corpus carries a third-party agreement. Both are word-emitting Latin
`▁`-BPE at **320 ms**, so **the measured 0.401 s lag and the zero-retraction strip transfer unchanged** —
and this is the *only* group for which that sentence is true.

### 2.2 SHIP WITH A STATED COST — reachable, and the cost is not ours alone to pay

| Lang | # | Pack bytes | Badge | Cadence | The stated cost, and whose it is |
|---|---|---|---|---|---|
| **ru** | 9 | 28,572,945 | **29 MB** | **640 ms** | **Engineering:** per-pack `PAD_MS` ≥ ~820 ms **before the canary is run once**. **Owner:** accept or refuse an INFERRED **~0.56-0.72 s** word lag against a measured 0.401 s. **Owner (optional):** one email about the undisclosed corpus |
| **id** | 18 | 70,908,694 | 71 MB | **640 ms** | **Counsel:** YODAS2 — 4 questions + a CC BY 3.0 / MIT credits line (~0.5 d). **Engineering:** per-pack `PAD_MS`, parameterise the test double. **Owner:** the same 640 ms ruling |
| **ko** | 12 | 72,969,700 | 73 MB | **320 ms** ✓ | **Counsel:** AI-Hub / NIA — 2 questions. **Engineering:** the `tokens`-not-`text` strip fix, the canary rule, all three copy flags, the unrenderable-token deny-list (≈3.6 d). **Do unconditionally regardless:** the NIA acknowledgement on the licences screen (0.1 d, no downside) |
| **zh** *or* **zh-en** | 10 | 72,470,080 *or* **49,752,335** | 72 MB *or* **50 MB** | **320 ms** ✓ | **Counsel — the heaviest question in the document,** and it is a **Chinese-LANE ruling, not a per-row one**: every Chinese-only alternative carries WenetSpeech **plus** AISHELL-2 **plus** KeSpeech — three agreements where the bilingual row carries one. **Do not spend the measurement day until counsel answers** |

**Two ways to total this group, because zh and zh-en both serve picker #10 and you ship one:**

| Set | New payload | Badge sum | Languages with live words |
|---|---|---|---|
| ru + id + ko + **zh** (monolingual Mandarin) | **244,921,419 B** | 245 MB | 4 |
| ru + id + ko + **zh-en 50 MB** (Mandarin **and** English in one utterance) | **222,203,674 B** | 222 MB | 4, one of them code-switched |

**The recommendation is the 50 MB bilingual row**, and it is the sharpest practical finding of the run: a
**byte-identical `tokens.txt`** to the 198 MB row (so one audit covers both and the copy is identical), an
encoder **0.60× the English pack's** (so the RTF/RAM/load-time risk plausibly *disappears* rather than
merely being bounded), at **a quarter of the bytes** — and it is the **only** row in the entire catalogue
that puts anything on the strip when an English speaker talks mid-Chinese. A Chinese-only pack shows a
**blank strip** at exactly that moment.

### 2.3 THE WHOLE THING, if every clearance lands

| | Bytes | AAB |
|---|---|---|
| Ship-now (fr + de) | 199,165,985 | |
| Ship-with-cost (ru + id + ko + zh-en 50 MB) | 222,203,674 | |
| **Total new payload** | **421,369,659 B** | 5,086,892,220 → **≈5.51 GB** at payload; **≈5.43 GB** after deflate (`preview_en`'s ONNX deflates 73 MB → 59,181,612 B = 0.815×, so this is INFERRED at that ratio) |
| **Play slots** | **6 new** | **10 of 100** |
| **Languages with live words** | **7** — en, fr, de, ru, zh(+en), ko, id | |

### 2.4 REFUSED, WITH THE REASON

**Zero bytes shipped. What each would cost if its blocker cleared is recorded so nobody re-prices it.**

| Lang | # | Reason, in one line | Bytes if cleared |
|---|---|---|---|
| **es** | 2 | Loads today, in sherpa-onnx's own release. **0-byte LICENSE under `license_name: "test"`** + **1,280 ms cadence** | 155,838,792 |
| **it** | 5 | Same, and worse: **no README at all**, Italian **absent from Kroko's own language list**, corrupt `tokens.txt` | 155,839,076 |
| **pt** | 6 | Best pt row is an **explicit all-rights-reserved refusal**; the Kroko alternative is 1,280 ms | 158,921,070 |
| **nl** | 7 | **1,280 ms** (best community option 640 ms, with no int8 export) + Kroko's empty LICENSE. **NOT a runtime wall** | 155,839,210 |
| **pl** | 8 | **No streaming model exists in any loadable family.** Exhaustive negative across 8 indexes | — |
| **ja** | 11 | The loadable row is **explicitly all-rights-reserved**; the icefall recipe's weights are **HTTP 401**; the apache-2.0 streaming option is **Moonshine** | 166,323,946 |
| **tr** | 15 | A 71 MB `zipformer2` pack exists with **no grant of any kind, anywhere** — the email must *create* a licence | 70,907,649 |
| **vi** | 16 | **339 MB** (over Play's cellular line) behind a **one-line native patch** ⇒ rebuilt AAR, new native bytes | 338,873,347 |
| **et** | 36 | **The accuracy ratio inverts** (whisper-small et 67.2/51.3) — a rule gap, not a model gap — plus picker **#36** | 156,221,502 |
| **bn** | 41 | fp32-only, and **whisper-small does not work in Bengali** so there is no ratio to pass | 94,119,939 |
| **fa** | 39 | CTC family, +1.5 d pack-shape change, no published number. **HOLD**, not refused | 11.7-99.3 MB |
| **uz** | — | **Not in the picker at all** ⇒ cannot be selected, cannot arm | 29 MB-class |
| **zh-en 198 MB** | — | Superseded by the byte-identical 50 MB row at 0.25× the bytes | 198,270,793 |

**The number that should decide the owner's priorities:** es + it + pt + nl = **626,438,148 B for four
languages at 640-1,280 ms**, against **421,369,659 B for seven languages at 320-640 ms**. The refused group
costs **1.5× more bytes for 43% fewer languages at 2-4× the lag.**

---

## 3. IS THE SPANISH HOLE REAL?

**Two answers, and both matter.**

### **NO — it is not a runtime hole. That claim is REFUTED.**

The survey states (§0.3, §1.3): *"What Spanish does **not** have is a model that loads through the runtime
already shipping"* and files es/it/pt/nl under a **runtime wall**. All four are wrong.

**READ, by my auditors, on 2026-09-11:**

- `csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06` — **owned by the sherpa-onnx
  maintainer**, and its tarball ships in the project's **own official `asr-models` release**
  (`…-es-kroko-2025-08-06.tar.bz2`, 124,394,665 B, one of **498 assets enumerated in full**).
- Its four-file form: **155,838,792 B**, all **flat at the repo root, no commas, no subdirectories** — so
  `StreamingPack.urlOf = baseUrl + file.name` works **unchanged**. Cheaper on delivery plumbing than
  German.
- `model_type = zipformer2`, `version = 1`, `comment = streaming zipformer2`, **`query_head_dims` and
  `value_head_dims` both PRESENT** — so `SherpaPreviewRecognizer.kt:46`'s hard-coded `"zipformer2"` is
  **already correct** and the `SHERPA_ONNX_EXIT(-1)` crash path that condemns French is **unreachable**.
  Spanish needs **neither** of the two fixes French needs.
- Despite plain `.onnx` names they **are** quantized: `producer_name = onnx.quantize`,
  `onnx.infer = onnxruntime.quant`.
- Italian, Portuguese and Dutch have the same shape in community mirrors — **155,839,076 / 155,839,204 /
  155,839,210 B**, all `zipformer2`, all head-dims present.

So: **es/it/pt/nl are not a runtime wall. They are a licence-and-cadence wall.** That is the German mistake
repeated on a different axis — an earlier sweep concluded "no model" where the truth was "a model whose
metadata the platform does not describe".

### **YES — the hole is still real, and the $150/mo decision is NOT dissolved.**

**Blocker 1 — cadence, and it may be unbuyable at any price.** `decode_chunk_len = 128` = **1,280 ms**.
The app's measured word lag (p50 **0.401 s**, p95 **0.523 s**) is *cadence-derived*: sherpa emits at most
once per encoder chunk. A chunk-128 pack **cannot** have a word lag below 1.28 s. That is not "word for
word"; it is short-phrase-by-short-phrase, **4×** the experience the owner validated. And it is not
escapable inside the community tier:

- Every Kroko community drop is `-64-L-Streaming-001` (**640 ms**) or `-128-L-Streaming-001`
  (**1,280 ms**) — and the `-L` number is **left context, not chunk**: the "128-L" builds read
  `decode_chunk_len = 256` = **2,560 ms**.
- **There is no Kroko community build below 1,280 ms for any language**, and **no int8 sherpa export of any
  64-L build exists** that either hunt could find.
- kroko.ai's only statement on this is that the commercial models *"add lower-latency options"*. **Whether
  a `decode_chunk_len = 32` build exists at any tier is UNSTATED.** Nobody has asked.

**Blocker 2 — the licence, read rather than assumed.** `Banafo/Kroko-ASR`'s card front matter is
`license: other`, **`license_name: test`**, `license_link: LICENSE` — and **`LICENSE` is 0 bytes** (HTTP
200, `size_download=0`, confirmed twice independently). The prose says *"CC-BY-SA licensed community
models"* with **no version anywhere**. The `es-kroko` mirror's entire README is **55 bytes**: *"See license
at https://huggingface.co/Banafo/Kroko-ASR"* — a pointer to an empty file. The only readable grant in the
whole lineage is a **third-party** mirror's 1,516-byte `LICENSE` naming **CC BY-SA 4.0**. And Kroko's own
card scopes community models to *"hobby projects, research, or free tiers"* while directing production to
*"commercial models … Licensed for professional and closed-source products"*.

**Every other Spanish door, closed by my auditors' own reads:**

| Door | What was read | Why closed |
|---|---|---|
| `daniel-dona/…-zipformer-streaming-es` | Recursive tree: **only** `exp/jit_script_chunk_16_left_128.pt` (267,277,530 B) + 7 shell scripts. **No `.onnx`. No `tokens.txt`. No README.** `/refs`: `main` only | **TorchScript, never exported.** The closest thing to a repeat of the German find, and it is genuinely empty |
| `daniel-dona/…-stateless7_streaming-es` | `README.md` **28 B** = a real unparsed `license: apache-2.0`. Tree: `.gitattributes` + `README.md`, **nothing else** | **The German blind spot pointing at ZERO weights.** An unparsed-licence lead is worth only as much as the tree behind it |
| `bookbot/…-robust-es-v0` | `tokens.txt` is **203 B, 37 lines**: `a ai au b d e …` plus **`t͡ʃ ɲ ɾ ʎ ʝ θ`**. **No U+2581, no word pieces** | **IPA phonemes.** A strip painting `ʎ ʝ θ` is worse than no strip |
| `jguerrisi/…-kws-zipformer-es-3M` | **`decode_chunk_len = 32` — cadence parity!** — 5,049,102 B, apache-2.0 **surfaced**. And: ~3.3 M params, `num_encoder_layers = 1,1,1,1,1,1`, `encoder_dims = 128×6`, trained on **five distress phrases synthesized 400× each**, **no WER of any kind published** | **A keyword spotter.** Recorded as a deliberate negative because it is the row most likely to be mistaken for a win. Its own README says *"`license:` above is a suggested default — adjust to match your data/voice obligations"* — **the one row where the platform surfaces apache-2.0 is the one row whose author says not to rely on it** |
| Vosk-es (Apache-2.0, 39 MB) | Unchanged from the survey | Kaldi ⇒ a **second ASR runtime** (`vosk-android-0.3.75.aar`, 13,472,638 B) |
| Moonshine-es (MIT, 121,800,392 B) | Unchanged | A **vendored second ONNX Runtime**, no Maven artifact, and **8 files against `StreamingPack`'s 4 named slots** |

### The verdict, and what the owner's email must now ask

**The hole has moved, not closed.** It is no longer *"buy Kroko or add a second runtime"*. It is now
**"buy Kroko, or clear Kroko's own free community weights, and in either case answer whether a 320 ms
Spanish build exists at all."** The engineering is nearly free — no new native bytes, no `modelType`
change, no `urlOf` change. What is not free is (a) a lawyer's read of an unversioned "CC-BY-SA" backed by
an empty file, and (b) an owner ruling on **1,280 ms**.

**The Kroko email needs THREE questions, and the third is new:**

1. Does the licence permit **redistribution inside a Play-distributed paid app**, **under which CC-BY-SA
   version**, and will you put a **non-empty `LICENSE`** in the repo? *(`license_name` is currently the
   placeholder string `"test"` over a 0-byte file.)*
2. Do the **commercial** weights ship as sherpa-format int8 encoder/decoder/joiner + tokens loadable by our
   own sherpa build, or only through your SDK? *(For the **community** weights the answer is now
   demonstrably **yes** — k2-fsa itself publishes `…-{en,es,fr,de}-kroko-2025-08-06` in its own release.)*
3. **Does a `decode_chunk_len = 32` (320 ms) build exist for any language at any tier?** Every community
   drop is 64-L or 128-L. If the answer is no, **$150/mo does not buy the feature the owner validated**,
   and es/it/pt/nl/pl close for a reason no money fixes.

**That third question is worth more than the decision it might cancel.** Ask it before spending anything.

---

## 4. THE COPY CONSEQUENCES

**First, an honest correction to the premise.** There is **no user-facing sentence** in the app promising
"no capitals, no punctuation, no numerals". I grepped for it. What exists is:

- **What the user is promised** — `StreamingPackCopy.ADDITIVE` (`:56-57`), spelled once and carried by
  every install sentence: *"**Words appear** on the bubble as you talk; the typed transcript is still the
  speech model's."* Plus `SETTINGS_INSTALLED`: *"Installed (73 MB). **Words appear** on the bubble as you
  speak **English**; the typed transcript is unchanged."*
- **What the code silently assumes** — `PreviewText.kt:8`: *"The model emits **ALL CAPS** (rung 1 §1.3);
  the strip shows lowercase. `Locale.US`: never a Turkish dotless i"*, implemented as an unconditional
  `raw.trim().lowercase(Locale.US)`. And `PreviewTextTest.kt:9`: *"497 uppercase pieces, no lowercase, no
  digits"*.

So the copy problem splits in two, and the halves have different shapes.

**(a) The promise — "Words appear" — breaks for exactly four candidates.** `zh` and the Chinese half of
`zh-en` (every Hanzi token is a single character, so characters appear, not words). `ja` (a character
vocabulary with zero `▁`). And **`ko`, which breaks it for a reason that is not a language property at
all** — see §6(4). Korean orthography **is** space-separated, so the survey's zh prescription ("change the
noun to characters") is **wrong for Korean**: it would describe a runtime defect as a feature, and the
house copy rules should refuse it. **Take the 0.25 d `tokens`-not-`text` fix and "Words appear" stays true
verbatim.**

**(b) The assumption — ALL-CAPS, digit-free, punctuation-free — breaks for eight of the fourteen token
files read this run.** The assumption is not load-bearing on the *user-facing* sentence today, which is
exactly why it is dangerous: `normalize` case-folds unconditionally and the pinned test comment states
counts that are wrong in both directions (**the shipping file is 495 uppercase / 3 lowercase / 2
digit-bearing, not "497 / none / none"** — fix that once, because fr has identical counts and the wording
will be copied forward).

### 4.1 The flag matrix — every value read off `tokens.txt`, never assumed

| Lang | `emitsCase` | `emitsPunctuation` | `emitsDigits` | `normalizeLocale` | "Words appear"? | What must change |
|---|---|---|---|---|---|---|
| **en** (ship) | false | false *(one `'` piece — `DON'T` is possible, and the shipping sentence already lives with it)* | false | `Locale.US` | ✅ | — |
| **fr** | false | false | false | `Locale.US` | ✅ | **Nothing.** The apostrophe is already in the English vocabulary **at the same id 7**, under the same sentence. `É→é`, `Ç→ç`, `Œ→œ` are correct for French |
| **de** | false | false | false | `Locale.US` | ✅ | One row of its own: **German nouns arrive lowercase**, which a German reader reads as **wrong**, not rough. The sentence must say *"no capitals, **including nouns**"* |
| **ru** | false | false | false | `Locale.US` *(a no-op — already lowercase Cyrillic; the Turkish dotless-i hazard is Latin-only)* | ✅ | **Doc nit only:** `PreviewText`'s *"The model emits ALL CAPS"* becomes English-specific. Its one joiner is `-` (какой-то) where English has `'` |
| **id** | false | false | false | `id` / `Locale.US` | ✅ | **Nothing — strictly cleaner than English.** Zero digit-bearing pieces at all, and its only punctuation piece is `<sos/eos>` |
| **zh** | **must not case-fold** | false | false | `Locale.US` | ❌ **characters** | The **noun** → *"**Characters** appear … as you speak Chinese"*. And `normalize` is **not** the no-op it looks: the model demonstrably emits **31 uppercase Latin acronyms in 25,394 chars of its own published hypotheses** (NBA/PPT/TV via byte fallback), so `lowercase(Locale.US)` paints `nba` where whisper types `NBA` |
| **zh-en** | false | false | **TRUE** | `Locale.US` | ❌ for half | **One emittable numeral, `2` at id 4883** — the shipping pack has **zero**, so this pack breaks that property by exactly one token. And **no single sentence is true of both halves**: characters for Chinese, words for English. *(Happy accident: `GpuCanaryPolicy`'s position-two alias set already contains `"2"`, so a numeral rendering **scores** rather than fails.)* |
| **ko** | **TRUE** | **TRUE** | **TRUE** | `ko` | ✅ **only after the fix** | **All three, and the app's central promise.** 10 standalone ASCII digits · 32 punctuation pieces (`. ? , ! ) ( % - : '` + `‘ ’ “ ” – ·` + fullwidth) · 26 uppercase + 25 lowercase Latin. **The ko strip will carry `.` `?` `,` `!` that the English strip never does.** Plus a **deny-list for 7 unrenderable tokens** (U+FFFD id 2188, U+007F id 2214, five PUA) — which benefits **every** pack. Plus the **NIA acknowledgement** on the licences screen |
| **et** | **TRUE** | **TRUE** | **TRUE** | `et` | ✅ | **23 emittable punctuation marks · 9 emittable standalone digits · 106 emittable cased pieces**, and the cased pieces are ordinary high-frequency words (`▁Ja ▁Et ▁Aga ▁Ma ▁Eesti`), not rare proper nouns. **et would be the FIRST pack to set all three `true`**, so every `true` branch gets written and tested for the first time on this row — the flags are not a free abstraction while no pack exercises them (**+0.5 d over the survey's per-language estimate**) |
| **es** (Kroko) | **TRUE** | **TRUE** | **TRUE** | `es` | ✅ | 564 lowercase / 68 uppercase / 61 accented / 16 digit-bearing / 8 punctuation. The English digits canary **does not transfer** — the model may answer *"uno dos tres"* with `1 2 3`, so both word **and** digit aliases per position |
| **it** (Kroko) | **TRUE** | **TRUE** | false | `it` | ✅ | Case + punctuation. **Easier canary than Spanish** (no digit pieces ⇒ word aliases only: `uno due tre quattro cinque`) |
| **nl** (Kroko) | **TRUE** | **TRUE** | **TRUE** | `nl` | ✅ | **All ten ASCII digits are tokens**, 22 punctuation pieces, 92 uppercase-bearing (`▁En ▁De ▁Het ▁Ik ▁Maar`). A **third** capability profile, distinct from English/German and from Turkish. Note: **the digits canary would actually work here** |
| **pt** (lyr) | false | false | **TRUE** | `pt` | ✅ | Digits only — 17 digit pieces. **The closest token shape to the shipped English pack of any non-English row** (981 lowercase, zero uppercase, one apostrophe), so `normalize` and `before()` transfer with the least change |
| **pt** (Kroko) | **TRUE** | **TRUE** | false | `pt` | ✅ | Case + punctuation; 147 accented |
| **tr** (jgsch) | **partial** (448 lower / 34 upper) | **TRUE** (18 pieces) | **false** | **`tr` — NOT `Locale.US`** | ✅ | The one row where `normalizeLocale` is load-bearing rather than cosmetic: `Locale.US` lowercasing of `İ` is the exact hazard `PreviewText`'s KDoc names. **And a Turkish canary must be word-form** (`bir iki üç dört beş`) because the vocabulary has **no digit** |
| **ja** (lyr) | **TRUE** (37 upper + 37 lower Latin as tokens) | **TRUE** (59 pieces incl. fullwidth `＆（）＊＋．／：＝＠［］＿`) | **false** | `ja` | ❌ **characters** | **A Japanese previewer that cannot write "2026"** — no plain ASCII digit token exists, only `①②③⑤⑨`. Plus the CJK space rule |

### 4.2 The mechanical consequences on strings

- **`BADGE` is a class-init `val` computed once from `EN.totalBytes`** (`StreamingPackCopy.kt:49`,
  verified) and appears in **seven sentences**. With fr at 128 MB, ru at **29 MB** and the bilingual at
  50 MB, **every sentence carrying a size must take the pack.**
- **Eight strings name English** (`:62, 71-72, 86-87, 90, 116, 149-152`). The language **name** is already
  in the app (`SUPPORTED_LANGUAGES` is `("fr" to "French")`, `("ko" to "Korean")`, …), so this is a
  **lookup over a template**, not a per-language string table, and `StreamingPackCopyTest`'s verbatim pins
  stay verbatim.
- **Open ruling for the owner:** whether the feature **name** stays *"Live words"* (`SETTINGS_TITLE:62`,
  `SWITCH_TITLE:64`, `DELETE_SUBTITLE:69` and the repair/fetch family) or becomes language-derived for
  zh/ja. **Recommendation: keep it** — the docblock at `:8` says *"'Live' is the app's own word for the
  surface"* — and template only the descriptive sentence.
- **The house rules still bind** every new string: no banned speed words, no `\d+ms` claim, and the literal
  `"typed transcript"` in every install string (`StreamingPackCopyTest.kt:129-146`).
- **And one claim must be retired for six of the seven rows:** `LANGUAGE_STEP_SENTENCE` says *"Live words
  on the bubble are English-only for now"*. Beyond that, **the 0.4 s figure cannot be reused in copy for
  any 640 ms pack** — ru, id and et all emit at half the rate the measurement was taken at.

---

## 5. WHAT IS STILL UNKNOWN — with the cheapest action that settles it, and who takes it

### 5.1 OWNER — five emails and three rulings, and they gate more than all the engineering below

| # | Unknown | Cheapest action | What it unblocks |
|---|---|---|---|
| **O1** | Is German's apache-2.0 real? The grant is author-declared in a file the platform does not parse | **One email to `daniel-dona`.** A confirmation, not a request — the grant *is* declared | **The cheapest row in the survey (71 MB, zero seam change).** If no: de drops and 4.5.0 ships fr alone |
| **O2** | Does Kroko permit redistribution of the community weights in a closed paid app, under which CC-BY-SA version — **and does a 320 ms build exist at any tier?** | **One email, three questions** (§3) | **es, it, pt, nl** — and possibly **cancels** the $150/mo decision, or proves it cannot buy the feature |
| **O3** | Will `lyr.jp` license the Portuguese weights? The card refuses third-party use **and names a contact and invites enquiry** | **One email to `hello@lyr.jp`** | **pt at 640 ms, 159 MB, on a clean YODAS2-manual corpus** — the best-published streaming numbers of any refused row |
| **O4** | What was the Russian 0.54 streaming model trained on? | **One email to `alphacep`** | Nothing gating — the apache-2.0 grant on the artefact is what a licensee relies on. Nice-to-have |
| **O5** | Will `jgsch` create a licence for the Turkish weights? | **One email — but it must CREATE a grant, not confirm one** | **tr at 71 MB.** Lowest probability of the five |
| **O6** | **Is a live strip that repaints once every 1.3 s worth shipping under the name "Live words"?** | **A ruling.** No engineering answers it | Answering **no** closes es/it/pt/nl on the community tier **permanently** and makes O2 purely about the commercial tier |
| **O7** | **Is "sub-second" the bar, or "same as English"?** ru/id/tr/et are all 640 ms; INFERRED ~0.56-0.72 s (ru), ~0.7/0.84 s p95 (id) | **A ruling, after the measurement in E1** | If sub-second: **ru and id clear it**. If same-as-English: **no Russian or Indonesian model can ever clear it** |
| **O8** | **The rung-3 rule has no arm for a previewer FAR BETTER than the finalizer** — *"> 1.5× → strip-only; > 2× → stop"* only catches the previewer being worse | **A ruling: write the third arm** | Estonian is the first row to expose it, but it will recur. The strip would advertise an accuracy the app cannot deliver |

### 5.2 COUNSEL — four questions, ranked by weight

| # | Unknown | Cheapest action | Note |
|---|---|---|---|
| **C1** | **Chinese (zh and zh-en).** May weights derived from 5 explicitly non-commercial corpora — 3 also no-derivatives, one (KeSpeech) barring *"Adaptations"* and **revocable on the licensor's notice**, one **delisted** so its licence cannot be read, one application-gated, and a 10,000 h YouTube/Podcast component whose publisher **disclaims the audio copyright** — ship inside a **paid** app? | **A written opinion.** Not a one-email row | **It is a LANE ruling, not a per-row one**: every Chinese-only alternative carries **three** agreements where the bilingual row carries one. **Do not spend the measurement day until it answers** — no accuracy number changes it |
| **C2** | **Korean.** (i) Could the uploader grant Apache-2.0 over weights whose corpus rights vest in **NIA** and whose commercial exploitation is conditioned on a separate agreement? *(Apache-2.0 §7 disclaims warranty of title, so **we** carry that risk.)* (ii) Does the mandatory NIA attribution reach the app **through the weights**? | Two questions | **Act on (ii) now regardless:** add the NIA / 한국지능정보사회진흥원 acknowledgement to the licences screen. **0.1 d, no downside.** And **do not** take the canary clip from the k2-fsa mirror's `test_wavs` — those are AI-Hub audio |
| **C3** | **Indonesian (YODAS2).** Does training on a CC BY 3.0 corpus create an attribution obligation on the **weights**, and can a Collection-level credit discharge it when the dataset **removed the per-author handle**? Does the uploader's grant reach **85.6% auto-captions**? Is a channel-level heuristic + a takedown mailbox sufficient diligence two hops downstream? Which CC licence is `librivox-indonesia`? | **4 questions, ~0.5 d + one credits line** | The licence class itself is **fine** — CC BY 3.0 is commercially permissive, no NC, no SA |
| **C4** | **Estonian.** ~3000 h of third-party TV broadcast + ~500 h of YouTube + **Whisper-turbo pseudo-labels**. Direct evidence of uncuration: two **Devanagari** letters in the Estonian vocabulary | One read | Only if O8 ever makes Estonian worth building |

### 5.3 A PROBE — everything measurable, cheapest first

| # | Unknown | Cheapest action | Cost |
|---|---|---|---|
| **E1** | **Per-pack cadence and pad.** `decode_chunk_len` is per-pack (32 / 64 / 128 / 256) and **nobody recorded it**. `PAD_MS = 500` was measured against English's `T = 45` (~465 ms) and is **~285-320 ms short for every `T = 77` pack** ⇒ **the last word of every utterance silently never emits**, and the canary may **Fail into a process-wide `disabled`** behind a sentence the 4.4.0 sheet records as rendered nowhere | **Add `decodeChunkLen` to `StreamingPack`, derive `padMs` from `T`, and assert both against the Range-read metadata in the layout test** | **≈0.25 d, and the cheapest thing on this list.** A **prerequisite for running the ru canary even once** |
| **E2** | **The blank-init PengChengStarling probe — NOT on the Stage-0 list and cheaper than everything on it** | Run the project's **own** `zipformer/onnx_pretrained-streaming.py` (which **is** sherpa's blank-init behaviour) over gigaspeech2-vi test and reazonspeech test with the csukuangfj int8 trio; compare to the published 7.09 / 13.34 | **0.5 desk-day, no device, no build.** Settles **ja and vi** definitively. Three decisive outcomes: **(a)** numbers hold ⇒ vi and ja are reachable today and §0's gap list is wrong by two languages; **(b)** numbers degrade but the **script** is right ⇒ **this is the answer to the Auto lane**, not a per-language pack; **(c)** script drifts ⇒ closed permanently |
| **E3** | **Word lag at 640 ms.** The feature's one measured user-facing number is 0.401 s / p95 0.523 s at 320 ms | **A PC rung 1**, which reports first-emission cadence directly. Put the number in front of the owner (O7) | 0.25 d. **The one number the owner needs for ru and id** |
| **E4** | **fr RTF and RSS.** Structurally **worse** than English and not because the model is bigger: icefall states **70,369,391 params** and the fp32 encoders are within 12%, but the older `stateless7` exporter quantized **fewer ops** — **2.31× reduction vs English's 3.69×**, so more fp32 MatMuls per chunk on equal parameters | **A device rung 3.** This is a **GATE for fr, not a formality** | 0.5 d + owner device hours. Escape hatch if it lands badly: a re-quantized re-mirror — **a separate, named decision about becoming the mirror owner, not a quiet patch** |
| **E5** | **zh RTF.** The joiner is **1,033,416 B against English's 259,335 B** (2,000-token vocab = 4× the output projection) **and** Mandarin emits ~1 character per 2 frames — joiner calls per second rise on **two** axes | **A device rung 3** | 0.25 d + ~1 h device. Mandatory rather than a formality |
| **E6** | **fr's chunk-32 WER is NOT PUBLISHED.** 10.57 was measured at `--decode-chunk-len 64` (640 ms); the export is 32 (320 ms) — halving the chunk halves the right context. **The survey flags this class of gap for zh and not for fr** | A PC rung 1 on the CommonVoice-fr test set, which ships its own reference | 0.5 d, already budgeted |
| **E7** | **zh at our export.** 17 greedy streaming CERs exist for the same checkpoint at the **other** export | FLEURS `cmn_hans_cn` through the existing rung-1 harness at 320 ms / 2 threads, **scored as CER**, **plus whisper-small on the identical clips** — both sides of the ratio on one corpus, which no published pair provides | 1 d + ~30 min compute. **0.5 d of it is a traditional→simplified fold before scoring**, because whisper-small returns traditional characters while this vocabulary is hans-only, and without the fold the denominator is inflated and **the ratio flatters us** |
| **E8** | **The English half of zh-en.** No English WER is published for either bilingual row. **This is the number the owner's stated workflow actually turns on** | A rung-1 run against whisper's own output on **code-switched** audio | The bilingual's English sub-vocabulary is **494 pieces against the English pack's 495, 384 shared (77.6%)** — INFERRED: a full `lang_bpe_500`-scale English inventory, not a token-starved afterthought |
| **E9** | **Every "greedy" number in this table.** ko publishes **beam-8 only** (no greedy CER at any chunk); ru was measured at `modified_beam_search`/`max_active_paths=10` **with `dither=3e-5`** while the app runs greedy with `dither = 0f`; zh-en's card is beam/chunk-64 from a differently-named checkpoint; id's four numbers have **no decode log, no `test_wavs`, no eval script and no stated decode mode** | Each row's PC rung 1, with the app's own config | Already the survey's §5.3 plan. **But the pre-screen must not be read as evidence** |
| **E10** | **Whether any of this loads at all.** Every verdict here is metadata-derived | The §5.3 rung-1 gate | Non-negotiable |

### 5.4 RECORDED SO NOBODY RE-FINDS IT

- **The offline-vs-streaming tell** — an icefall zipformer2 **offline** export writes
  `comment = "non-streaming zipformer2"` and **omits `decode_chunk_len`, `query_head_dims` and
  `value_head_dims`**; the **online** export writes all three. **One Range read separates them**, and it is
  how four plausible ja/vi candidates a name search would have promoted were killed.
- **An unparsed README can hide a REFUSAL as easily as a grant.** `ken4869/lyr-asr-{ja,ko,pt}-streaming`
  all report `license: None` to the API while their READMEs say
  「ライセンス未設定（全権利留保）」 + 「LYR アプリ専用」. **The `ko` one matters**: picker #12 currently
  rests on `kangkyu`, and an adjacent account publishes a Korean streaming `zipformer2` that is explicitly
  forbidden. **The German lesson cuts both ways.**
- **The German trap has a second costume: key naming.** `id`'s README parses fine, but the API's
  **top-level `license` key is absent** — only `cardData.license` carries it. A sweep reading
  `model["license"]` reports "Not specified" for a perfectly readable MIT grant. fr has the same shape.
- **A `tokens.txt` line count ≠ decoder `vocab_size` is SAFE on the zipformer transducer path and
  instantly FATAL on the NeMo family.** The literal
  `"number of lines in tokens.txt %d != %d (vocab_size)"` **is** in the shipped
  `libsherpa-onnx-jni.so` (offset 221825) — but it appears in exactly 5 sources at v1.13.7, all
  NeMo/Canary/Parakeet, and `online-recognizer-transducer-impl.h` has **zero** hits. et-en's 1117 vs 1000
  and zh-en's 6,257 vs 6,254 are both harmless.
- **`OnlineToneCtcModelConfig` IS in the shipped AAR** (in the `.so` **and** in `classes.jar`, with
  `toneCtc` a real `OnlineModelConfig` field). The 8 kHz Russian T-one model fails on the **model** — CTC,
  fp32-only, 144,193,702 B in one file, a 35-line character vocabulary — **not on the runtime**. Do not
  re-open it hoping for an AAR bump.
- **v1.13.7 already closes the invalid-UTF-8-across-JNI risk** for byte-fallback vocabularies:
  `SafeNewStringUTF` = `NewStringUTF(RemoveInvalidUtf8Sequences(s).c_str())` (`jni/common.h:150-152`),
  applied to `result.text` and every `result.tokens[i]`. No cost, no ruling — **but do not bump the AAR
  downward.**
- **Two byte-level defects in the `kouhxp` Kroko mirrors** — their `tokens.txt` files carry a **4-byte
  little-endian length prefix** left over from Banafo's `.data` container, so **line 0 is
  `\xa1\x18\x00\x00<blk> 0`**. csukuangfj's official `de-kroko` file starts cleanly. **These mirrors are
  unverified re-packs, not blessed exports**, and both would have shipped invisibly.
- **`HTTP 401` rows, unaudited:** `TeoWenShen/…csj…` (the Japanese bar's own weights) and
  `hataphu/zipformer-k2-rnn-lm-vi`. **Unknown whether gating (recoverable) or deletion (not).**
- **`R4kSo1997`'s nl/tr `.tar.bz2`** (124,383,931 / 122,071,564 B) were not downloaded, so whether they
  hold int8 or fp32 is **unverified**.

---

## 6. THE SEAM — FIVE DEFECTS, NOT THREE

The survey's §2.2 lists three. Two more were found this run, and both are silent failures.

1. **`warm()` is idempotent on the ENGINE, not the PACK** (`StreamingPreviewEngine.kt:113` tests
   `recognizer != null` and never looks at `pack`). A naive second pack paints **English over French while
   the gate logs `preview=1`**. Fix: pack identity on the engine + `release()` on a language change; the
   reload bill is the measured 802-860 ms load + 20-28 ms warm-up.
2. **`modelType` is hard-coded `"zipformer2"`** (`SherpaPreviewRecognizer.kt:46`) and is **wrong for fr
   and for both zh-en rows**, and the failure is **crash-class**: `OnlineTransducerModel::Create`
   short-circuits on the non-empty config string (`online-transducer-model.cc:146`), forces
   `OnlineZipformer2TransducerModel`, which reads `query_head_dims` through
   `SHERPA_ONNX_READ_META_DATA_VEC` whose miss path is `SHERPA_ONNX_EXIT(-1)` = **`_Exit(-1)`** — an
   **uncatchable process kill** that never reaches `warm()`'s `catch`, `onLoadFailure()`, `markCorrupt()`
   or `disabled`. **Prefer `modelType = ""`** over a per-pack string: `OnlineZipformerTransducerModel`
   reads exactly seven keys and **all seven are present in the French encoder**, English keeps working
   because its own metadata says `zipformer2`, and it is valid for **every** candidate in this table.
   **Add a test pinning the field empty**, or the next reader helpfully restores `"zipformer2"` and ships a
   process kill.
3. **The English canary cannot pass for a non-English model, and a Fail latches `disabled` for the
   PROCESS** (`:136-142`) behind `SETTINGS_DISABLED_ON_DEVICE`, which the 4.4.0 acceptance sheet records as
   **rendered nowhere**. ⇒ **silently no live words, in every language, with no explanation.**
   **Correction to the survey:** the scope is **process lifetime, not permanent** — `PreviewCanary`'s
   docblock (`:26-27`) says *"The verdict is never persisted (no preference, no per-(versionCode, pack)
   latch)"*. Still process-wide, still a defect; it self-heals on restart. **`GpuCanaryPolicy` must not
   change** — its verdict **is** a persisted whisper-GPU latch (`:18-20`) — so extract the scoring or give
   the previewer a sibling policy. **zh and ko need a different RULE, not just a clip**: both collapse to
   **one token** (`RemoveSpaceBetweenCjk` + `[^\p{L}\p{Nd}]+`), so per-position matching is structurally
   impossible and `MAX_TOKENS = 20` is unreachable. They need **character-set overlap + a length band**
   (一二三四五 / 일이삼사오). Shared work: **0.75 d for both.**
4. **NEW — `PAD_MS = 500` is a 320 ms / `T = 45` measurement and is short for every `T = 77` pack.**
   The 450/500 cliff its KDoc records *is* `T`: English needs 25 + 44×10 = **465 ms** of feature to run one
   forward pass. **ru/id/et/tr need `T = 77` ≈ 785 ms**, so 500 ms is **~285 ms short**, `isReady` stays
   false, the tail chunk is **never decoded** and the utterance-final word is dropped. Corroborated
   upstream: ru's own `decode.py` pads **600 ms**, and 2.0 s for its 128-shift variant — **upstream never
   pads 500 for a Russian model.** ⇒ `PAD_MS` must be **per-pack, ≥ ~820 ms for ru/et**, and it is a
   **prerequisite for running those canaries even once**, because a Fail lands in defect (3).
5. **NEW — `PreviewText.before()` concatenates tokens (spaces PRESENT) while the live path renders
   `result.text` (spaces ABSENT).** `SymbolTable::operator[]` rewrites a leading `▁` to a space
   (`symbol-table.cc:191-200`) while `Convert()` applies `RemoveSpaceBetweenCjk` to the text
   (`online-recognizer-transducer-impl.h:69`). The app uses **both** paths —
   `StreamingPreviewEngine.kt:302` renders `normalize(result.text)`, `:331` renders `before()` on a cap cut
   with a retained tail — so **on zh the strip changes its spacing mid-utterance at every cap cut**, and
   **on ko the frozen prefix is spaced while the live tail is not.** One fix closes this and item (6):
   **for a flagged pack, build the strip text from `tokens`, not `text`.** ~0.25 d, **pure Kotlin, no AAR
   change, zero new native bytes.**
6. **The Korean space deletion, which is defect (5)'s twin and the largest undiscovered cost in the run.**
   `IsCJK` at v1.13.7 covers `0x1100-0x11FF` and `0xA840-0xD7AF` — and **Hangul Syllables U+AC00-U+D7A3 sit
   inside the second range**. `RemoveSpaceBetweenCjk` is called **unconditionally, with no language guard**,
   so *every* Korean word space is deleted: `안녕하세요 저는 한국어 음성 인식 모델입니다` →
   `안녕하세요저는한국어음성인식모델입니다`. **Worse, spaces survive before an ASCII digit**
   (`0x33` is neither CJK nor `IsPunct`), so `회의 시간을 오후 3시로 옮겨 주세요` →
   `회의시간을오후 3시로옮겨주세요` — **arbitrarily inconsistent spacing, harder to defend in copy than
   either extreme.** Verified in the **shipped binary**: `jni/arm64-v8a/libsherpa-onnx-jni.so` carries the
   literals `1.13.7`, `Skip OOV`, `query_head_dims`, `decode_chunk_len` and U+3002, so the `IsCJK` range
   read in source is the one that runs on device.

Plus two smaller items that benefit every pack: **an unrenderable-token deny-list** in
`PreviewText.normalize` keyed on `Cc`/`Cf`/`Co`/`Cn`/`Cs` + U+FFFD (ko has **7** such tokens), and **fixing
`PreviewTextTest.kt:9`'s counts** (495 uppercase / 3 lowercase / 2 digit-bearing, not "497 / none / none")
before fr copies the wrong wording forward.

---

## 7. CORRECTIONS THE NEXT BUILD MUST CARRY

Each was established from a primary source by the auditor who found it, against the survey's own cell.

| # | Survey says | The build must say | Why it matters |
|---|---|---|---|
| **1** | fr WER **9.95** | **10.57** | 9.95 is the **GigaSpeech** checkpoint (epoch 30 avg 9). The export is **epoch 29 avg 9** — confirmed three ways (ONNX filenames, `exp/export.sh`, `exp/decode.sh`) — and the repo's **own** `wer-summary-test-cv-greedy_search.txt` reads `greedy_search 10.57`. icefall's `RESULTS.md` appends verbatim: *"This best result is trained on the full librispeech **and gigaspeech**"*. **GigaSpeech's gated terms are non-commercial-only.** The survey cites the GigaSpeech model's WER against the LibriSpeech model's corpus clearance — mutually exclusive claims. **Ratio 0.47×, not 0.44× — nothing is lost by being correct. Pin the prohibition in the catalog row's comment**, because 9.95 is the number a future reader will find in `RESULTS.md` and reach for |
| **2** | ko **8.25 % CER @ chunk-64, RTF 0.038 — "the only non-English row with a published sherpa RTF"** | **8.635 CER, RTF 0.052 — and neither is greedy** | The 8.25/0.038 pair is the **chunk-64 export = `decode_chunk_len 128` = 1.28 s cadence**, a *different model* at 3.2× the measured word lag. The app's cadence is **chunk-16 = 320 ms**. And **every** kangkyu CER is **beam-8** — there is **no published greedy CER for this model at any chunk**, while the app runs `greedy_search`. The RTF cell is factually true and **analytically empty** (rung 1: *"PC RTF is not device RTF … it is irrelevant"*); ko and en have **identical** `num_encoder_layers` and `encoder_dims`, so INFERRED ko lands near English's measured **0.116 @ 2 threads** on the Tab |
| **3** | zh **"NOT PUBLISHED at chunk-16"** | **17 greedy streaming CERs ARE published for the same checkpoint at the other export** | Nothing at *our* export — correct. But `zrjin/…-ctc-streaming-2023-11-05` publishes aishell-1 test **3.63**, wenetspeech test_net **9.66**, kespeech test 12.54 … at chunk-32/left-256, and the export script proves **both exports came off one checkpoint**. So "chunk-16 vs chunk-32" is **one export versus the other**, not one number under two names, and the published figure is an **optimistic bound** on ours |
| **4** | §1.5 **"SHIP AFTER COUNSEL: ru (mirror untagged)"** | **The mirror half is DISCHARGED; the counsel item is the UNDISCLOSED CORPUS** | All four pack files are **byte-identical** to a platform-tagged apache-2.0 upstream (3 LFS oids + a computed `sha256` on `tokens.txt`). That is a **stronger** position than German's, whose grant the platform cannot read. What actually survives — **no corpus is named at all** — the survey never mentions |
| **5** | id's four numbers, **unmarked** | **All four UNVERIFIED**, as the ru row is marked | German's 10.58/8.58 come from **files in the repo naming the decode config**. The Indonesian repo holds **12 entries and not one is a decode log** — no `wer-summary`, no `test_wavs`, no eval script, no `exp/`, and the card **never says** the numbers came from the streaming ONNX at chunk-32/left-256 |
| **6** | zh-en corpus **"internal multilingual dataset"** | **WenetSpeech-L 12,000 h** | That phrase is **not in the repo** (whose README is 296 bytes and describes no corpus) — it is the *sherpa-onnx docs*' description. The **author's** card publishes `training_subset: '12k_hour'`, WenetSpeech's own name for its L subset. **A named non-commercial restriction is a worse position than an unnamed unknown**, because it converts "we didn't know" into "it was on the card" |
| **7** | zh filed under **SHIP** | **SHIP AFTER COUNSEL — the heaviest corpus row in the document**, heavier than ko and heavier than id | The survey's own reading rule — *"an `apache-2.0` tag on a mirror is the mirror owner's tag, never a corpus clearance"* — is the rule that condemns this row, and §1.5 did not apply it |
| **8** | Route A **"keeps the measured 0.4 s lag"** across eight languages | **Established for German only.** en/de/fr/zh/zh-en are 320 ms; **ru/id/tr/et/ja/lyr-pt are 640 ms; every Kroko build is 1,280 or 2,560 ms** | The survey's §1.1 table has **no cadence column**, and the omission hides a per-pack property that carries `PAD_MS`, a test double and three KDocs with it. **Add `decodeChunkLen` to `StreamingPack`** |
| **9** | et picker **"—"** | **Estonian is #36 and IS selectable**, so it **would** arm under the ruling | *(And `qual-et-en.md`'s "#37 of 55" counts `"auto"` as a language — I re-derived the list; the survey's numbering is right and Estonian is 36.)* |
| **10** | §1.3/§1.5 **nl = "CLEAN LICENCE, WRONG RUNTIME"** | **Kroko-NL loads on the AAR in the APK.** It is a **cadence** wall (1,280 ms) and a **licence** wall (0-byte LICENSE) | Same correction for **es and it**: §0.3's *"What Spanish does not have is a model that loads through the runtime already shipping"* is **refuted** |
| **11** | tr published WER **"TBD"** | **13.09 CV17-dev / 17.39 daily-dev / 20.16 FLEURS-val** — and **a licence-silent 71 MB `zipformer2` Turkish pack exists** | From `duxx`'s own README + `evaluation.json`. `duxx` itself is **CC-BY-NC-4.0** (a corpus term that propagated to the weights — the exact risk the survey flags as "corpus counsel", already fired) |
| **12** | §1.4 PengChengStarling: the door *"exists only in the project's private fork"* | **A one-line native patch.** v1.13.7's own `GetEmptyResult()` writes the initial token in **exactly** that slot: `int32_t blank_id = 0;  // always 0` | Still native ⇒ rebuilt AAR + new native bytes, and **unreachable from Kotlin** (both candidate seams checked: `OnlineTransducerModelConfig` has no such field; `OnlineStream::SetOption` is a bare `unordered_map` the transducer decoder never reads). **And the published 7.09/13.34 are langtag numbers, not sherpa numbers** — the project's own ONNX script runs blank-init too |
| **13** | §2.2 *"zh costs the most design per byte"* + a −1.5 d canary surcharge | **"zh AND ko"** — and **zh-en pays NO canary cost at all** | ko has the same one-token canary collapse from the same line of the same function. And the 198 MB/50 MB bilingual rows run the **bundled English clip unchanged**: `▁ONE/▁TWO/▁THREE/▁FOUR` are whole pieces, `FIVE` splits `▁FI`+`VE` **identically to the English pack**, and the clip's transcription carries **no CJK** so `RemoveSpaceBetweenCjk` never fires. **Residual risk named:** an English-only clip exercises only the English half |
| **14** | §2.2 *"silently no live words, **forever**"* | **Process lifetime, not permanent** | `PreviewCanary`'s own docblock: *"The verdict is never persisted."* Still process-wide, still a defect |
| **15** | §2.3 *"the 8 kHz T-one Russian"* as a **+0.5 d** sample-rate surcharge | **RED on three independent counts** — CTC not a transducer, **fp32-only in one 144,193,702 B file**, and a **35-line character-level** vocabulary that breaks "words appear" worse than zh does. **And the AAR already supports it** | It fails on the **model**, not the runtime |
| **16** | Only one Vosk Russian streaming model exists | **`alphacep/vosk-model-streaming-ru` v0.56 was missed** — apache-2.0 platform-surfaced, four int8 files at **71,694,239 B**, **byte-identical `tokens.txt`**, architecturally identical to the shipped English encoder, same 640 ms cadence | **The ru row's zero-seam accuracy upgrade at +43 MB.** Record it in the catalog KDoc so a disappointing rung-1 number costs 0.25 d, not a re-audit |

---

## 8. RECOMMENDED SEQUENCE

**Before anything:** send **O1** (de's email), **O2** (Kroko's three questions), **O3** (lyr's email) and
open **C1** (the Chinese lane) and **C2/C3** (ko/id). They are free, they run in parallel with engineering,
and **three of them can cancel work rather than create it.**

1. **The seam + fr + de** — the survey's Stage 1, plus **E1** (`decodeChunkLen` + a derived `padMs`, and
   the assertions that pin them) and the empty-`modelType` test. fr forces the crash fix at a desk instead
   of on a device; de is 71 MB of zero-seam-change validation. **199 MB, 320 ms, the measured lag intact.**
2. **ru** — 29 MB proves the multi-pack machinery at the lowest byte cost anyone could ask for, on a
   different script with an already-lowercase vocabulary, and it is the row that **forces E1 to be correct**
   before a canary Fail can latch `disabled` process-wide. **Report E3's lag number to the owner here.**
3. **id** — the fewest unknowns per byte, the cleanest vocabulary in the survey, the strongest canary story,
   **and the second-best accuracy ratio** — gated on C3 and E1.
4. **zh-en at 50 MB, or zh at 72 MB** — gated entirely on **C1**. Prefer the bilingual row: it serves
   picker #10 **and** keeps the strip alive when an English speaker talks. Pays the canary-rule work.
5. **ko** — gated on **C2**, and sequenced here because **the canary rule and the space/noun handling are
   the same work as zh's**. Land the `tokens`-not-`text` fix and "Words appear" stays true verbatim.
6. **Never, on today's evidence:** es, it, pt, nl, pl, ja, tr, vi, et, bn. Each has a reason in §2.4, and
   **four of them turn on a single email (O2) and one ruling (O6)**.

---

## 9. READ vs INFERRED — the whole document in one paragraph

**READ by an auditor on 2026-09-11, not taken on report:** every pack byte count and LFS oid in §1, summed
from `api/models/<repo>/tree/main?recursive=true` and **never** from a release tar; every `model_type`,
`version`, `decode_chunk_len`, `T`, layer/dim vector and the **presence** of `query_head_dims` +
`value_head_dims`, from HTTP Range reads of each int8 encoder's tail with the `metadata_props` protobuf
**parsed rather than regex-scraped** (a naive 4-char string scan aligns `decode_chunk_len` to `77`, because
the real value `64` is two characters and the key `T` is one — et-en's auditor nearly lost that row to it);
every licence text fetched at `resolve/main` **and** checked against what the platform surfaces, in both
directions; every `tokens.txt` fetched and counted line by line, character by character, with the U+2581
marker identified specifically; the shipping English encoder's metadata and `tokens.txt` re-read at the
pinned commit for a same-method side-by-side; `sherpa-onnx v1.13.7`'s own C++ at tag
(`online-transducer-model.cc`, `online-zipformer{,2}-transducer-model.cc`,
`online-recognizer-transducer-impl.h`, `symbol-table.cc`, `text-utils.cc`, `macros.h`,
`online-transducer-greedy-search-decoder.cc`, `jni/common.h`); `libsherpa-onnx-jni.so` and `classes.jar`
extracted from the shipped `app/libs/sherpa-onnx-1.13.7.aar` and string-scanned; the Whisper paper's
Tables 10/11/13 extracted from **the PDF** with the rotated-header column order **decoded and then
cross-validated eight ways** against independently-reported values; nine Mandarin corpus licences from
their own publishers including `openslr.org/62`'s 404; the AI-Hub 데이터 이용정책 clause by clause in
Korean; YODAS2's shard list, hours table and verbatim licence sentences; all 498 assets of k2-fsa's
`asr-models` release; icefall's entire master tree (3,736 paths) and all 43 `egs/`; and every checkout line
cited, at main `3f982b9`/`cef5715` — plus, by this synthesizer today, `SUPPORTED_LANGUAGES` (to settle the
picker off-by-one), `StreamingPackCopy`'s eight English strings and the class-init `BADGE`,
`StreamingPreviewTuning.PAD_MS`, `StreamingPackCatalog.sizeBadge`, the rung-3 decision rule, and the fact
that **no user-facing "no capitals, no punctuation, no numerals" sentence exists in the app.**

**INFERRED, labelled at each use:** that GigaSpeech is absent from fr's exported lineage (from the epoch/avg
↔ WER-row correspondence plus commit-date ordering, **not** from a manifest — `exp/train.sh` is silent on
whether the English pretrain carried it, and that is said out loud); that fr's RTF and RSS are worse than
English's (from the 2.31× vs 3.69× quantization-coverage gap on equal parameter counts); that word lag
roughly doubles at 640 ms (~0.56-0.72 s for ru, ~0.7/0.84 s p95 for id) — the **cadence** is read, the lag
**consequence** is not measured; that the pad floor is `T` frames, hence `PAD_MS = 500` is short for every
`T = 77` pack (derived from the frame arithmetic and from English's measured 450/500 boundary matching
`T = 45`'s 465 ms — **the single highest-value thing to verify on device**); that YouTube auto-captions are
of doubtful reach for an uploader's CC BY grant and that Collection-level attribution is the only
attribution YODAS2 makes possible; that the ko/id/ru corpus restrictions bind **the data** and not the
weights (the favourable reading, and the likely one — **and exactly the counsel question**); that Estonian's
previewer is several times better than whisper-small's 51.3 (direction near-certain, magnitude not); that
Polish is commercial-only at Kroko; that the NeMo 20k multilingual is offline (from the cadence-suffix
naming convention, since its weights are tar-only); that blank-init preserves PengChengStarling's accuracy
(**UNMEASURED — and E2 is exactly what settles it**); the AAB deflate ratio; and **every day figure**, which
follows this repo's own convention that no estimate here is recorded against an actual.

**UNMEASURED / NOT PUBLISHED:** on-device word lag, RTF, load time, resident memory and retraction count
for **every** non-English row; fr's WER at its own chunk-32 export; zh's WER at its own chunk-16 export;
any greedy CER for ko at any chunk; the English half of either bilingual row; et-en's, tr's, vi's and ja's
accuracy of any kind; whether any model in this table loads through the shipped AAR at all.

**UNVERIFIED upstream metrics, flagged where quoted:** ru's card 11.3 (`"verified": false`, corroborated
twice by the same author at 11.2 CV12 — better than "unverified", still not third-party); every one of
id's four numbers; every CER in the kangkyu card and in particular its whisper-large-v2 comparison row;
both bilingual zh-en cards; lyr-pt's 0.1318 (and its own card states the reservation — the test set is the
model's own training domain).
