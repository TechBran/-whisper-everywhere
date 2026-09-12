# Licensing clearance for six speech models — a standalone prompt

**Paste everything below the line into a fresh session.** It carries all the context that session needs; it does
not assume any memory of the project. Bring the "Deliverable" table back to the build session when it is done.

---

## What I need from you

I ship a **paid Android app** (one-time purchase) that does on-device speech-to-text. I am adding six small
speech-recognition models to it, one per language. The models are **bundled inside the app** and delivered to
customers through Google Play Asset Delivery — so I am **redistributing** each model's weights to paying users, at
scale, inside closed-source software.

I need to know, for each of the six, **whether I can legally do that** — and where I can't, what would make it
possible. I will act on your findings, so be accurate about what you verified versus what you inferred, and tell
me plainly when the honest answer is "a lawyer has to decide this."

You are not my lawyer and I am not asking you to be. What I want is: the facts assembled and verified from primary
sources, the specific question a lawyer would need to answer, and drafts of the emails that would resolve the ones
resolvable by asking.

## The one distinction that matters most

Three separate things, and people conflate them constantly:

1. **The engine.** sherpa-onnx, Apache-2.0. Not in question.
2. **The weights.** Each model repo carries its own licence, set by whoever uploaded it. This is what I redistribute.
3. **The training data.** A repo owner's `license: apache-2.0` tag is their claim about *their own contribution*. It
   is **not** clearance for the corpus the model was trained on. Several of my six have permissive tags sitting on
   top of corpora with real restrictions — that gap is the main thing I need investigated.

Also note a research trap that has already produced one wrong answer on this project: **Hugging Face's API is not
a reliable source for a licence.** If a repo's README is stored via Xet/LFS, the platform does not parse it and
reports `license: Not specified` even when the file plainly declares one. Always fetch
`https://huggingface.co/<repo>/resolve/main/README.md` and read the front matter yourself. Conversely, a licence
can be present in `cardData` and the `license:` tag while the *top-level* `license` API field is absent — so a
shallow read of one field reports nothing. Check the raw file and the card data.

## The six, with what I already know

Two are settled and need no work from you, listed so you don't redo them:

- **English** — `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`. Apache-2.0, LibriSpeech. Already
  shipping in production since August. Settled.
- **French** — see below; I believe it is clear, but there is one residual question I'd like closed.

### 1. French — `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14` (commit `3db9565d`)

**What I have.** `license: apache-2.0` read directly in a 204-byte, non-LFS README front matter, *and*
platform-surfaced in `cardData.license` and the `license:` tag. The upstream repo it was exported from,
`shaojieli/icefall-asr-commonvoice-fr-pruned-transducer-stateless7-streaming-2023-04-02`, independently declares
`apache-2.0` with `datasets: mozilla-foundation/common_voice_12_0`. Corpus: **Common Voice 12.0 French (CC0-1.0)**
fine-tuned on a **LibriSpeech (CC BY 4.0)** English pre-train. This is the strongest licence position of the six.

**The one residual question I want closed.** The upstream training script fine-tunes from
`exp/english_pretrain/epoch-30.pt` with `--init-modules "encoder"`, and it does **not** state whether that English
pre-train was LibriSpeech only or LibriSpeech **+ GigaSpeech**. This matters because **GigaSpeech's audio is
non-commercial-only** — its gated card says researchers may use the database "only for non-commercial research and
educational purposes" and binds for-profit employers too. Prior analysis concluded GigaSpeech is *not* in the
exported artefact, reasoning that the export is `epoch 29 avg 9`, which the card's results table maps to the
"LibriSpeech then Common Voice" row (WER 10.57), while the GigaSpeech row is a **different** checkpoint
(`epoch 30 avg 9`, WER 9.95) whose decoding results aren't in the repo at all. That reasoning is sound but it is an
inference from a results table, not a statement from the author.

**What I want:** verify that inference independently, and draft a short email to the author asking one question —
was the English pre-train used for the released checkpoint trained on LibriSpeech alone, or did it include
GigaSpeech?

### 2. German — `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de`

**This is the one I most want resolved, because it is one email and it unblocks the cheapest model of the six.**

**What I have.** The repo's README is **180 bytes and Xet/LFS-stored**, so Hugging Face does not parse it: the
model page shows "empty or missing yaml metadata in repo card" and the API reports `license: Not specified`. The
file's entire content, fetched at `resolve/main`, is front matter reading:

```
---
license: apache-2.0
datasets:
- mozilla-foundation/common_voice_17_0
language:
- de
metrics: [wer, cer]
pipeline_tag: automatic-speech-recognition
tags: [icefall, sherpa]
---
```

Corpus: **Common Voice 17.0 only** — the cleanest corpus position of all six. So the grant *is* declared; it is
simply declared in a file the platform doesn't display, which means nobody browsing the page would ever see it.

**What I want:** draft an email to the author (contactable via his Hugging Face profile) that (a) confirms the
Apache-2.0 grant covers redistribution of the weights inside a commercial closed-source app, and (b) politely asks
whether he would add a plain `LICENSE` file or make the card render, so the grant is visible to anyone. Keep it
short and appreciative — he has given something away for free and is doing me a favour by answering.

### 3. Russian — `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` (commit `31fa603e`)

**What I have.** The mirror carries **no licence tag at all**. Its upstream, `alphacep/vosk-model-small-streaming-ru`,
declares **apache-2.0** and the platform surfaces it. The provenance between them is already established
cryptographically — all four files I would ship are byte-identical to the upstream's (three LFS object hashes plus
a SHA-256 computed on `tokens.txt`), so the mirror is a copy and the upstream's grant is the one that governs.

**The real question is the corpus, and it is undisclosed.** The card says only that it was "trained with
k2-fsa/icefall on Russian data." That's it. No corpus named.

**What I want:** find out what it was trained on — check Alpha Cephei's own site, their GitHub, the Vosk model
documentation, and any release notes — and if it isn't documented anywhere, draft an email to Alpha Cephei asking.
Then tell me whether an undisclosed corpus under an Apache-2.0 weights grant is a risk I should accept for a paid
app, and what a cautious lawyer would say.

### 4. Indonesian — `spacewave/sherpa-onnx-streaming-zipformer2-id`

**What I have.** An **MIT** tag from the repo owner. The corpus is a mix that includes **YODAS2**, which is
**YouTube-derived**, alongside Common Voice, FLEURS and LibriVox.

**The question.** What YODAS2's terms actually permit for a model shipped in a paid app. An MIT tag from an
uploader is not corpus clearance, and YouTube-derived training data is the category most likely to carry terms the
uploader had no authority to waive.

**What I want:** the actual YODAS2 licence and terms read from source (it comes out of a research group — find the
dataset card and any paper or terms document), what it says about commercial use and about redistribution of
models trained on it, and a plain verdict on whether this one is worth pursuing or dropping.

### 5. Korean — `kangkyu/icefall-asr-ko-streaming-zipformer-72m` (commit `db24b58d`)

**What I have.** The weights licence is the cleanest cell of the six: `apache-2.0`, read in a 7,926-byte plain-blob
front matter *and* platform-surfaced in both `tags` and `cardData`. No email needed for the weights.

**The corpus is the problem.** It is **KsponSpeech**, which is **AI-Hub dataset 123**, published by Korea's NIA.
Clauses already read from the AI-Hub policy:

- "※ 내국인만 데이터 신청이 가능합니다" — only Korean nationals may apply for the data;
- a party **outside Korea** needs a separate agreement;
- **export** of the data needs a separate agreement;
- use is "only for training AI learning models";
- no transfer or sale;
- **attribution to NIA is mandatory and extends to derivative works.**

**What I want:** read those terms properly from the AI-Hub source, and answer two things. First — do the
restrictions bind a *model trained on* the data, or only the data itself? (This is the crux: I would never possess
or redistribute KsponSpeech, only weights derived from it.) Second — if the answer is that I can ship it, what
attribution must appear in my app, and where? Then tell me whether this needs a Korean-law lawyer specifically.

### 6. Chinese-English bilingual — `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t`

**What I have.** 49,752,335 bytes — a quarter the size of the better-known 198 MB bilingual model, with a
byte-identical token file. The corpus is **WenetSpeech-L, 12,000 hours**, identified from the author's own training
parameters (the card describes it only vaguely). WenetSpeech carries a **named non-commercial restriction**. This
is assessed as the weakest legal position of the six.

**Why I care despite that:** it is the only model in the entire catalogue that keeps producing text when an English
speaker talks in the middle of Chinese audio, which is a real use case for me.

**What I want:** the WenetSpeech licence and terms read from source, a clear answer on whether a model trained on it
can be redistributed commercially, and — if not — whether any alternative bilingual Chinese-English streaming
model exists under permissive terms. Note that a *named* non-commercial restriction is a worse position than an
undisclosed corpus, because there is nothing left to interpret.

## Also worth your time: the commercial option for the languages I can't get free

Spanish, Italian, Portuguese, Dutch, Polish and Turkish have **no** usable free streaming model — the only vendor
covering them is **Kroko**, by a company called **Banafo** (`yello@banafo.com`, and a "Talk to sales" form at
kroko.ai with an On-device SDK option). Their published price is "On-Device SDK — From $150/mo", qualified as
"Licensing by app scale & redistribution." Their free "community" weights are described as CC-BY-SA but the
`LICENSE` file in their Hugging Face repo is **literally zero bytes**, under a `license_name` of `"test"`, and
their own card scopes community models to "hobby projects, research, or free tiers" while directing production use
to a commercial licence.

Three questions to put to them, and **the third is the one that decides whether any of this is worth paying for**:

1. Does the licence permit redistribution of the weights inside a Play-distributed **paid** app, under which
   CC-BY-SA version — and would they put a non-empty `LICENSE` in the repo?
2. Do the **commercial** weights ship as sherpa-format int8 encoder/decoder/joiner plus tokens, loadable by my own
   sherpa build — or only through their SDK? (A second runtime in my app is a cost I'd likely refuse.)
3. **Does a `decode_chunk_len = 32` (320 ms) build exist for any language at any tier?** Every community model they
   publish is 640 ms or 1,280 ms, and since the engine emits at most once per chunk, a 1,280 ms model cannot put
   words on screen faster than 1.28 seconds behind the speaker. My whole feature is built around ~0.4 seconds.
   Their marketing page claims "320ms" partials, which suggests such a build exists, but their model card only
   says commercial models "add lower-latency options" with no number. **If the answer is no, $150/mo buys me
   something I don't want, and those six languages close for a reason money can't fix.**

Draft that email too.

## Deliverable — bring this back to me

A table, one row per language, in exactly this shape, because it maps onto a clearance record in my code that
currently has these cells blank and a test that fails while they are:

| Language | Verdict | The grant relied on | Where you read it | Date | Who granted it |
|---|---|---|---|---|---|

`Verdict` is one of **CLEARED** (I can ship it to paying customers), **BLOCKED** (I cannot, with the reason), or
**NEEDS A LAWYER** (name the specific question and which jurisdiction).

Plus: the drafted emails, ready to send, one per recipient — the German author, the French author, Alpha Cephei,
and Banafo.

Two things I'd rather you tell me than spare me: if a language is not worth pursuing, say so and I'll drop it; and
if you think my whole framing is wrong somewhere — for example if redistributing derived weights is a materially
different question from redistributing a corpus in a way I've under-weighted — say that first, before the table.
