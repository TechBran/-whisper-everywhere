# Training our own streaming packs — the synthesis

**Date: 2026-09-12.** Six research lanes (`train-recipe`, `train-hardware`, `train-data`,
`train-finetune`, `train-accuracy`, `train-pipeline`) and two refutation lanes
(`train-refute-compute`, `train-refute-quality`) ran against the hypothesis that we should train our
own chunk-32 streaming Zipformer packs from Common Voice rather than buy, borrow or go without.

**Reading rule for this document.** Every external fact below was **READ by a lane**, not by me — I
read the eight lane reports in full and cite the lane plus its source. So the strongest honesty
label I can attach to a number here is **LANE-READ** (a lane fetched the artefact and quotes it),
**LANE-MEASURED** (a lane computed it from a fetched artefact, script named), **LANE-DERIVED**
(arithmetic over those, shown), or **INFERRED** (judgement, mine or a lane's, reasoning exposed).
Where I make a synthesis call that no lane made, I say **SYNTHESIS**. Where the lanes disagree, §12
adjudicates instead of averaging. I did not re-fetch any URL; §13 says where that matters.

---

## 1. THE ANSWER IN ONE SCREEN

**The refuters landed. Lead with that.**

**The programme as stated is refuted.** "Train our own for the six missing languages, from scratch,
on Common Voice, and the corpus problem dissolves" does not survive contact with the accuracy
evidence. The quality refuter killed **Polish and Turkish outright**, and **Spanish survives only by
abandoning the Common-Voice-only premise** — which is the premise. Applying that refuter's own
correction (divide by whisper-small's *neutral-audio* row rather than the Common Voice row every
ratio in the qualification table uses), **all six predicted packs land at 1.86× to 3.68× the
finalizer** on the honest scaling exponent — across the "strip-only" line for all six and across
the ">2× → stop" line for Spanish and Portuguese, **on `multi` alone, with no appeal to the turbo
tier.**

**What is NOT refuted, and it is a lot:**

1. **The recipe chain has no hole in it.** `egs/*/ASR/zipformer --causal 1` writes
   `model_type: "zipformer2"` as a *literal*, writes `query_head_dims`/`value_head_dims`
   *unconditionally*, and the CommonVoice export script **is** the LibriSpeech one (a 59-byte
   symlink). The app's metadata gate is satisfied by construction on a pack we export ourselves.
   (LANE-READ, `train-recipe` §1, §4.)
2. **320 ms is free.** Training samples `--chunk-size` randomly from `"16,32,64,-1"` per batch; the
   cadence is picked at **export**. The German Common Voice model used the unmodified default and
   its pack carries `decode_chunk_len 32`. **The single decisive reason for rejecting Kroko —
   every community build at 640/1,280/2,560 ms — evaporates at zero compute premium.**
   (LANE-READ, `train-recipe` §2, `train-hardware` §1.)
3. **Compute is not the constraint and is not close.** The corpora are 126–595 hours. At the most
   pessimistic single-card throughput any human has published for this workload, 30 epochs of Dutch
   is **13.8 GPU-hours — one night.** A refuter tried six ways to make compute the reason to stop
   and failed five times. (`train-refute-compute` §4, §8.)
4. **Both failure modes are front-loaded.** OOM dies in minutes (`scan_pessimistic_batches_for_oom`
   feeds the largest batches first); divergence dies inside the Eden LR peak at ≈2 GPU-hours with a
   saved bad model and a named cause. **There is no known failure mode that burns a week silently.**
   (`train-refute-compute` §3 — found while trying to stop the plan.)
5. **The normalizer prize is real and is measured in our own committed tests.** icefall's Common
   Voice normalizer is `uppercase, no digits, no punctuation except the apostrophe` — line for line
   the token census `PreviewPackMetadataTest` pins for the shipping pack. **Every §4.1 flag-matrix
   cost the qualification table books (ko's 32 punctuation pieces + 10 digits + the CJK space bug,
   et's all-three-true row, nl's ten digit tokens, the it/nl corrupt 4-byte-prefixed `tokens.txt`,
   the lyr TAB separator) is a cost of consuming somebody else's normalizer. This loop does not pay
   them.** (LANE-READ, `train-pipeline` §5.)
6. **The Common Voice CC0 premise survives the October-2025 move.** All **402** Common Voice
   datasets on Mozilla Data Collective carry `licenseAbbreviation: CC0-1.0`, `isPaid: false`, and
   the dataset page names *"training and evaluating automatic speech recognition (ASR) models"* as
   the intended use. (LANE-READ, `train-data` §1.2 — a 12×100-page REST sweep.) Two conditions: **no
   re-hosting the tarball**, **no speaker re-identification**. One open counsel question.
7. **RTF, RAM, load time, word lag and zero retractions transfer by construction**, because they are
   properties of *that graph at that cadence*, not of the English weights. Keeping every model-shape
   default reproduces the 66,110,931-parameter model. (LANE-READ + INFERRED, `train-pipeline` §7.2.)

### The verdict

> **CONDITIONAL GO — on a decision gate, not on a training run. NO-GO on the programme as written.**
>
> Spend **three desk-days and roughly one GPU-hour** on the three experiments in §7 that need
> almost no GPU. They can kill the whole idea, or license it, for less than the cost of one
> overnight run. **Do not start a multi-day training run until they have answered.**
>
> **If the gate passes: one language, and it is Dutch, by fine-tuning the German checkpoint —
> not from scratch.** ~0.4–0.9 GPU-days on the RTX 2000 Ada in the MS-02.
>
> **Three languages are closed now, on evidence, before any GPU time: Turkish, Polish, Portuguese.**
> Turkish because 130 h is the entire clean world and the one datapoint that made it look viable is
> contaminated four ways. Polish because every one of four independent prediction routes lands it at
> or worse than the German anchor's *best-ever* epoch. Portuguese because every route says worse
> than whisper-small, and Vosk's Portuguese small model is the only outright failure on their whole
> table.
>
> **Spanish is the language you want and the hardest to win**, because whisper-small is already 5.6
> on FLEURS-es. It is reachable only as MLS + VoxPopuli + Common Voice — which reopens exactly the
> clearance conversation the prize was supposed to close.

### Why Dutch is first — SYNTHESIS, and the lanes disagreed about this

Three lanes nominated three different languages. Dutch wins on the union of their reasons:

- **Cheapest, by a wide margin.** 126 validated hours, a **3.4 GB** download, ~0.4–0.9 GPU-days
  fine-tuned. An honest *no* is affordable, which is the condition under which starting is correct.
  (`train-hardware` §7, `train-finetune` §7.)
- **It has the best available pretrain, and the pretrain is a sister language.**
  `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` is already **zipformer2 at chunk-16**
  (so the export metadata comes out identical to the shipping pack, zero seam change), is
  **Common-Voice-17-only** (the cleanest corpus position in the whole catalogue), publishes
  `exp/epoch-30/epoch-30.pt`, and German is the closest language in the target set to Dutch.
  (LANE-READ, `train-finetune` §8.)
- **Dutch is where a close pretrain matters most**, because it is the smallest corpus and the
  from-scratch prediction is the *worst* in the set (Route A honest: 31.7 WER). If a de→nl
  fine-tune at 126 h cannot clear the bar, **nothing in the set will**, and the programme ends for
  under one GPU-day. That is the most information per GPU-hour available anywhere in this
  programme.
- **It is also the natural speaker-diversity experiment**, which is the biggest unmeasured question
  in the corpus argument. Dutch holds both extremes in one language: CV-nl is 96.6 h from **1,884**
  speakers; MLS-nl is 1,554 h from **40** LibriVox readers (9 M / 31 F). One paired run answers
  "does diversity beat hours at chunk-32" for all six languages. (LANE-READ, `train-data` §4.2, §8A.)
- **Its bar is among the two most generous of the six on neutral audio** (whisper-small FLEURS-nl
  **16.4**, second only to Turkish's 15.9 — and Turkish is closed for corpus reasons).

**The dependency, stated plainly: Dutch-first rests on the German checkpoint's licence, and that
licence is not machine-registered.** The HuggingFace API reports **no `license` field and no
`cardData`** for that repo, and its `raw/main/README.md` is a **180-byte Git LFS/xet pointer, not
text** — the apache-2.0 that two lanes report was read through `resolve/main`, which follows the
pointer and hides the problem. **This is the exact failure this project has already been burned by
once.** (LANE-READ, `train-refute-compute` §6.) **Send that email on day 0.** If it does not clear:
fall back to **Italian from scratch** (364 h, 10.5 GB, 7,342 speakers, the second-most-generous
Common Voice bar, a Kokoro voice for the canary, and no third-party checkpoint dependency at all) —
the `train-pipeline` lane's own recommendation, and the right *loop-proof* language even though its
accuracy prospects are worse than a fine-tune's.

---

## 2. THE COST

### 2.1 GPU-days, per language, on a named card

Every figure below rests on an **unmeasured card factor** and I say so before the table. The two
primary timing anchors in the entire evidence base are:

| anchor | card | h-audio / GPU-hour | status |
|---|---|---|---|
| CV-17 German, causal, `world_size 1`, fp16, md 300 | **unnamed** (`Device: cuda:0`, AWS EC2) | **273** | LANE-MEASURED from its own published log |
| LibriSpeech causal, 4 GPUs, md 1000 | **unnamed**, inferred 32 GB V100 | **623** | LANE-MEASURED from its own published log |
| ReazonSpeech streaming, 3,000 h | RTX 3090 | 750 | third-party report, icefall #1661 |
| WenetSpeech-L causal | A100 40 GB | ~760 | LANE-DERIVED from a read 0.5 s/step, icefall #1698 |

The `train-hardware` lane's card estimates are **INFERRED by scaling on memory bandwidth**, on the
strength of icefall #1698 (an H100 with ~3× an A100's tensor throughput delivered 0–40 % more
training speed — **this workload does not convert tensor FLOPS into speed**):

- **RTX 2080 Ti** (616 GB/s, 13.45 TFLOPS fp32): **~330** h-audio/GPU-h, band 250–450.
- **RTX 2000 Ada** (224 GB/s, 12.0 TFLOPS fp32): **~200** h-audio/GPU-h, band 180–255.

The counter-intuitive conclusion is that **the older gaming card is ~1.5–1.7× the faster trainer**,
on bandwidth. NVIDIA's headline "191.9 TFLOPS" for the Ada is FP8-with-sparsity and is irrelevant
here.

**The table. The named card is the RTX 2000 Ada in the MS-02, because that is the only card the
software will actually run on (§2.3).**

| lang | CV 26.0 validated h | **from scratch, 30 ep — Ada** | from scratch — 2080 Ti | **fine-tune, 14 ep — Ada** | fine-tune — 2080 Ti |
|---|---|---|---|---|---|
| **es** | 595.5 | **3.7 d** | 2.3 d | **1.7 d** | 1.1 d |
| **it** | 363.7 | **2.3 d** | 1.4 d | **1.1 d** | 0.6 d |
| **pt** | 188.0 | **1.2 d** | 0.7 d | **0.5 d** | 0.3 d |
| **pl** | 176.9 | **1.1 d** | 0.7 d | **0.5 d** | 0.3 d |
| **tr** | 130.1 | **0.8 d** | 0.5 d | **0.4 d** | 0.2 d |
| **nl** | 126.4 | **0.8 d** | 0.5 d | **0.4 d** | 0.2 d |
| **all six** | **1,580** | **9.9 d** | 6.0 d | **4.6 d** | 2.8 d |
| *de, as calibration* | *856 (its **train bucket** — it ran `use_validated_set: False`)* | *5.4 d* | *3.2 d* | — | vs the German run's **measured 3.6 d** |

⚠ **Read the calibration row carefully: the German run trained on its `train` bucket (~856 h), not on
its 1,392 validated hours.** Our six would run with `--use-validated-set 1` (§4.1), so the table
above prices *more* audio per language than the anchor consumed. At 1,392 h the same arithmetic gives
8.7 Ada-days — which is what you would pay to reproduce German properly today.

LANE-DERIVED throughout. Hours are LANE-READ from Mozilla's own
`cv-corpus-26.0-2026-06-12.json` (322,454 B, 294 locales), cross-validated byte-for-byte against
MDC's `sizeBytes`.

**One honest sentence about this table: the ±2× band the lanes attach to it is understated, and the
real uncertainty is 27×, and it lives in the *shape* of the run.** (`train-refute-compute` §5.)

| shape | corpus | **Ada** | **2080 Ti** | evidence it works at 126 h |
|---|---|---|---|---|
| Six **fine-tuned** from a pretrained `.pt`, 14 ep | 1,580 h | **4.6 d** | 2.8 d | **none published** below ~250 h |
| Six **from scratch**, CV-only, 30 ep (the headline) | 1,580 h | **9.9 d** | 6.0 d | German at 1,392 h only |
| Six from scratch, **CV + MLS**, 30 ep | 4,564 h | **28.5 d** | 17.3 d | none |
| Six from scratch, CV + MLS, **80 ep** | 4,564 h | **76 d** | 46 d | none |
| Build our own clean pretrain (MLS non-en + CV), then fine-tune | 7,607 h | **47.5 d** | 28.8 d | none |
| The French precedent's shape (LibriSpeech + GigaSpeech-XL pretrain) | 10,960 h | **68.5 d** | 41.5 d | **the only shape with published evidence — and barred** |

**Dutch alone is the headline case and the 13× is the finding.** Dutch is simultaneously the
cheapest language (126 h → one night), the one the accuracy lane most needs MLS for, and the one
where MLS is **12× its Common Voice** (1,554 h vs 126 h). CV-only Dutch is 0.8 Ada-days; CV+MLS
Dutch is **10.5**. The language that makes this estimate look cheap is the language where the fix
costs the most compute.

And the one shape with published evidence is barred on corpus grounds: **GigaSpeech XL is 10,000 h,
access is a Google Form, and its README states no licence for the audio at all** — YouTube- and
podcast-derived. That is the YODAS2 trap again, and this project has already rejected a language for
it. (LANE-READ, `train-refute-compute` §6.)

### 2.2 Data-prep time and disk, separately

**Prep wall time is UNMEASURED and I will not interpolate it.** What is LANE-READ is the shape:
stages 1–8 are CPU/RAM/IO bound and embarrassingly parallel — `nj=16`,
`compute_fbank_commonvoice_splits.py` defaults to `--num-workers 20` and `--batch-duration 600.0`,
uses `KaldifeatFbank` with `torch.set_num_threads(1)` on `torch.device("cpu")`, and writes through
`LilcomChunkyWriter`. **The GPU contributes nothing to this phase.** Cheapest measurement: run
stage 4 (dev/test only) alone, time it, read the `.lca` byte sizes, extrapolate — **five minutes**,
and it also measures the real lilcom ratio, which is the other inferred number in the disk table.

| item | Dutch | Italian | Spanish | all six |
|---|---|---|---|---|
| CV 26.0 tarball (LANE-READ `size`) | **3.4 GB** | **10.5 GB** | **51.9 GB** | **78.8 GB** |
| extracted mp3 + TSVs | ≈ same again | ≈ same again | ≈ same again | ≈ 79 GB |
| 80-dim fbank, validated, lilcom (INFERRED ≈3× at ~29 MB/audio-h) | ≈ 4 GB | ≈ 14 GB | ≈ 23 GB | **≈ 46 GB** |
| MUSAN fbank | ≈ 2–3 GB | ≈ 2–3 GB | ≈ 2–3 GB | ≈ 3 GB (shared) |
| checkpoints at `--keep-last-k 30` × ~1.06 GB | **24–48 GB** ⚠ | 24–48 GB ⚠ | 24–48 GB ⚠ | per-run |
| **working set** | ≈ 35–60 GB | **≈ 70–90 GB** | **≈ 150 GB** | **≈ 130–150 GB** + downloads |

**Two sharp edges.** (a) `--keep-last-k 30` × 66 M params is the item that silently fills a disk —
set it to **5–10** or a multi-day run dies on `ENOSPC` at epoch 22. (b) **Spanish is the most
expensive download in the group it is meant to justify** — 51.9 GB, because 1,146,345 of its
1,680,810 clips are unvalidated `other`. Italian gets 364 validated hours for 10.5 GB.

**Acquisition is a human-in-the-loop browser download, not a script stage.** icefall's `prepare.sh`
hard-codes an S3 URL that now returns **HTTP 403 AccessDenied** (LANE-READ, probed on three objects
and the bucket root), and the HuggingFace mirror is **empty** with a notice that Common Voice is
*"exclusively available through Mozilla Data Collective"* as of October 2025. Stage −1 becomes an
MDC-credentialled download into `$dl_dir/$release/$lang`; **nothing downstream changes**, because
the tarball layout (`clips/` + seven TSVs) is what the recipe consumes and MDC's per-language
`sizeBytes` match Mozilla's own `size` field byte-for-byte. **Do not build a scraper around an
account wall.**

### 2.3 Which of the three machines does which part — the decisive fact is not a preference

> **k2 publishes ZERO Windows CUDA wheels.** A lane downloaded the index (605,767 B) and counted
> platform tags across all **2,191** entries: 4,148 Linux tags, **0 `win_amd64`**. The CPU index has
> **2,048** `win_amd64` wheels, so the absence is deliberate, not a scraping artefact. k2's own docs
> say it: *"If you want to build k2 with CUDA support on Windows, please consider compiling k2 from
> source."* And `import k2` is a hard import in **both** `train.py` and `export-onnx-streaming.py`.
> (LANE-READ twice independently — `train-recipe` §6, `train-hardware` §6.4, re-verified by
> `train-refute-compute` §7.)

**This is a correctness blocker, not a friction argument, and it stands independently of every
throughput number above.** The owner's suspicion about the MS-02 is correct, for a harder reason
than the one he gave — and the 128 GB is the *weakest* of the four real reasons.

| machine | role | why |
|---|---|---|
| **Minisforum MS-02 Ultra** — Linux, 128 GB, RTX 2000 Ada 16 GB | **Everything that touches k2: data prep (stages 1–9), the training run, decoding, export, int8** | k2 CUDA wheels just install. Not his working machine. Many cores for the RAM/CPU/IO-bound prep. Avoids the Windows path/IO friction this project already fights |
| **Dev PC** — Windows, RTX 2080 Ti 11 GB + RTX 2000 Ada 16 GB | **The app-side work**: the Gradle asset-pack module, the catalogue row, `build_asset_packs.py` triples, `:app:testDebugUnitTest`, the bundle. Plus the Kokoro canary clip | No CUDA k2. Reachable only through WSL2 or a dual-boot, on the machine he works from, for a job he has said he does not want there |
| **Tab S10+** (MT6989) | **The device gate**: RTF at 2 threads, word lag p50/p95, RSS delta, retraction count over ≥20 utterances | It is the device the shipping numbers were measured on. Nothing else can close K8 |

**Do not plan a two-GPU dev-PC run.** `--max-duration` is a **single scalar applied per rank**, so a
2080 Ti + Ada pair must be configured for the 11 GB card and is paced by the slower one — plausibly
slower than the Ada alone. No icefall documentation exists for heterogeneous DDP. Two *independent*
single-GPU runs extract far more from that box. (LANE-READ + INFERRED, `train-recipe` §2,
`train-finetune` §5.)

**The 2080 Ti's WSL2 escalation is worth a weekend only for a multilingual pretrain** (6.0 vs 10.1
days for all 1,580 h). At per-language scale the wall-clock difference is one overnight while the
friction difference is large.

### 2.4 The 11 GB / 16 GB question: answered, and there is no wall

The two lanes look like they contradict each other and the synthesis must not read them that way:
`train-recipe` headlines *"17.6 GB of per-GPU VRAM against an 11 GB and a 16 GB card"* while
`train-hardware` headlines *"11 GB is not a problem, 6,462 MB."* **These are the same fact at two
batch sizes.** The refuter fit both points — 66.1 M causal zipformer2 at fp16, different corpora,
different years, different torch versions:

```
F + 300k  =  6,462 MB     (CV German, md 300)
F + 1000k = 17,557 MB     (LibriSpeech, md 1000)
  ⇒ k = 15.85 MB per second of audio in the per-rank batch
  ⇒ F = 1,707 MB fixed  (weights + ScaledAdam state + CUDA context)
```

and the recipe lane's independent checkpoint-byte estimate of the fixed part (*"roughly 1.0–1.5 GB"*)
lands on the same number by a third route. Ceilings, allowing PyTorch's usual 15–30 % allocated-to-
reserved gap: **2080 Ti ≈ 520–560; RTX 2000 Ada ≈ 800–870.** Against a reported convergence floor of
**~100** and a real working value of **300**. **The usable window is wide and the working value is in
the middle of both cards.** (`train-refute-compute` §1.)

Three further points, all LANE-READ:

- **icefall's Zipformer recipes have no gradient-accumulation option at all.** Grepped in two
  `train.py` files and repo-wide; zero hits in any zipformer recipe. So "11 GB forces gradient
  accumulation" is not a claim that can be made. Effective batch is `max_duration × world_size` and
  nothing else.
- **The VRAM risk that actually bites is one pathological cut** — a single 133 s clip defeated
  3× H200 at md 800, 600 *and* 400. **Common Voice is immune by construction**: the CV recipe already
  filters cuts to 1–20 s and CV clips average 3.85–5.44 s.
- **The famous "4× RTX 2080 Ti → max-duration 150" datapoint does not apply to us.** It is
  `pruned_transducer_stateless5`, a **Conformer** recipe from 2022, markedly more memory-hungry per
  second of audio. Anyone quoting it at us is quoting a measurement of a different model.

### 2.5 fp16 on Turing: the recipe's own path, with one bounded risk

`--use-fp16 1` is what every published recipe command passes, including the shipping English
previewer's own run and the French cross-lingual fine-tune. `finetune.py` has **no `--use-bf16`
argument at all**, and `egs/commonvoice/ASR/zipformer/train.py` has **zero bf16 references**. So the
2080 Ti is not on a second-class path; it is on *the* path, and **the Ada's bf16 buys nothing that
icefall exposes without porting the LibriSpeech `--use-bf16` / `params.dtype` /
`create_grad_scaler` path into the CV recipe first** — a small mechanical diff that is real work and
is in nobody's GPU-day figure. (LANE-READ, four lanes agreeing.)

The failure mode is fatal, not cosmetic: `raise_grad_scale_is_too_small_error` **aborts** the run
below grad scale 1e-5, with `save_bad_model()` first. Mitigations, all cheap, all to be applied
**before** the first run:

1. **`base_lr 0.025`** from scratch (the recipe default is **0.045**; both successful single-GPU
   runs in the entire evidence base independently chose 0.025), or **0.0045** for a fine-tune.
2. **Apply PR #1955 to `egs/commonvoice/ASR/zipformer/asr_datamodule.py`** — lower `num_buckets`
   from 30 and drop the deprecated `shuffle_buffer_size`. The PR's own diagnosis: large
   `num_buckets` on a corpus of many short utterances produces *"continuous short batches [that]
   trigger `grad is too small` error in our zipformer model."* It was merged 2025-06-19 for aishell,
   aishell2, gigaspeech, librispeech and wenetspeech and **never applied to `egs/commonvoice`** —
   which is precisely the many-short-utterances corpus it describes. **This is the highest-value
   single line of code in the whole programme.**
3. Consider tightening the cut filter from `T ≥ S` to `T ≥ 1.5 × S` (<0.5 % data loss, removes a
   known NaN source).

**Turing-specific evidence: none exists.** `gh api search/issues q='repo:k2-fsa/icefall Turing'`
returns `total_count: 0`, re-verified by the refuter. The positive evidence is architectural (IEEE
binary16 has identical dynamic range on Turing and Ampere; PyTorch AMP requests fp32 accumulation on
both) plus the most relevant run in existence (German CV causal fp16, **zero** grad-scale warnings
across epochs 1 and 13, grad_scale climbing 1 → 128). **If it is wrong, the Ada becomes the only
viable card rather than merely the slower one — which is another reason to make the MS-02 the
default.** One closure the refuter did land in the plan's favour: k2's `CMakeLists.txt:300` lists
`75` in the base arch candidates and **no CUDA-version branch ever removes it** (CUDA 13 drops
50/60/61/70, keeps 75), so **sm_75 is a supported build target at every CUDA version k2 ships.**
Residual: `K2_BUILD_FOR_ALL_ARCHS` defaults **OFF**, so whether the *published wheel's* fatbin
contains sm_75 still needs the two-minute check (M0).

---

## 3. FROM SCRATCH vs FINE-TUNE — the decision, and the number that decides it

> ### **FINE-TUNE. And the reason is data, not compute.**

Stated precisely, because three different things are being multiplied together and only one of them
is the argument:

**Reason 1 — from scratch will not ship at these corpus sizes, and that is the whole decision.**
CV 26.0 validated hours are **es 595 · it 364 · pt 188 · pl 177 · tr 130 · nl 126**. Against them,
the only published from-scratch Common Voice streaming datapoint in existence is German at **1,392
validated / ~912 train hours → 10.58 test WER**. Four of our six are at **9–15 % of that**.
**Nothing published trains a shippable streaming Zipformer transducer from scratch on 130 hours.**
And icefall's own error text says the small-batch regime our cards force makes it *worse*, not just
slower: *"decrease the value of base_lr … until base_lr hits 0.02 (**Note that this will lead to
certain loss of performance** … You can compensate this by increasing the num_epochs)."*
(LANE-READ, `train-finetune` §0.6, §5, §6.)

**Reason 2 — transfer *wins* on accuracy, so there is no penalty to trade off.** The one clean
ablation that exists — same language, same architecture, same corpus, same metric, same chunk size
— is the French pack's own published three-way table: **from scratch 10.90 → LibriSpeech pretrain
10.57 → LibriSpeech + GigaSpeech pretrain 9.95** (greedy @640 ms). Transfer beats native by **3.0 %
and 8.7 % relative.** (LANE-READ, `train-finetune` §4.)

**Reason 3 — a fine-tune escapes the small-batch penalty by construction.** It runs at
`base_lr 0.0045`, already *below* the 0.02 floor icefall's own guidance walks small-GPU users down
to, with `lr_batches 100000` / `lr_epochs 100` so there is barely a schedule to get wrong. The
French precedent ran `base_lr 0.004` at **`max_duration 200`** — a value that fits an 11 GB card —
and produced the best published CV-fr streaming result. (LANE-READ + INFERRED,
`train-finetune` §5.)

**Reason 4 — compute, and it is the WEAKEST of the four.** The honest published saving is **~2.1×**
(14 epochs vs 30): all six fine-tuned is **4.6 Ada-days** against 9.9 from scratch. And **the 14
depends on an inference.** The French fine-tune's validation loss floors at **epoch 12–14** (epoch 14
= 0.1563, best-ever epoch 24 = 0.1561, epoch 30 = 0.1598 — *worse*), while the German from-scratch
run at epoch 9 of 30 is still at **17.63 against a 10.58 final — 67 % off**. **A fine-tune
front-loads; from scratch does not.** But validation transducer loss and WER decouple, and icefall
exports with `--avg 9`, so late epochs still contribute. **One decode pass settles it** (`--epoch 14
--avg 9` vs `--epoch 29 --avg 9` on the same dev set, under an hour). If it turns out you need 25
epochs the compute advantage shrinks to ~1.2× and this reason evaporates entirely. The other three
do not.

### The catch, and it is a real gate

**A fine-tune needs the PyTorch `.pt`, not the ONNX — and neither candidate checkpoint carries a
licence HuggingFace registers.** (LANE-READ, `train-refute-compute` §6, confirming
`train-finetune` §9 independently.)

| candidate | what the HF API says | what a lane read through `resolve/main` |
|---|---|---|
| `Zengwei/icefall-asr-librispeech-streaming-zipformer-2023-05-17` — the shipping pack's own lineage | **no `license` field, no `cardData`, no `LICENSE` file**, tags `["tensorboard","region:us"]` | its entire README is **one line**: `See https://github.com/k2-fsa/icefall/pull/1058` |
| `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` — the Dutch pretrain | **no `license` field, no `cardData`** | `raw/main/README.md` is a **180-byte Git LFS/xet pointer, not text**; the apache-2.0 is inside the blob |

**The apache-2.0 this project relies on today is `csukuangfj`'s tag on the ONNX *mirror* — a
different artefact in a different repo.** The corpus side is clean either way (LibriSpeech CC BY
4.0, GigaSpeech-free for the English checkpoint; Common Voice 17 only for the German), so the fix is
**one email each**, and the qualification table already has the German email drafted. Send both on
day 0. **The compute consequence of not sending them is a factor of ten on the bill** — remove the
fine-tune shape and the licence-independent options are 28.8–68.5 Ada-days, all of them with no
published evidence that they work at 126 hours.

### Three traps, all verified in source, all silent if missed

1. **`--init-modules "encoder"` is WRONG on our architecture.** V2 matches
   `k.startswith(module + ".")` and keeps `encoder_embed` as a **separate top-level attribute**, so
   `"encoder"` leaves the whole convolutional frontend at random init while the log claims the
   encoder transferred. **Always `--init-modules "encoder,encoder_embed"`, and verify two `Loading
   parameters starting with prefix …` lines in the log, not one.** (V1's matcher has no trailing dot
   *and* V1's frontend lives inside `encoder`, which is why the French `"encoder"` was correct
   there and is wrong here.)
2. **`vocab_size 500 == 500` means nothing will stop you.** Every recipe uses 500, so the
   reinitialisable heads have **identical shapes** across languages. List `decoder`/`joiner` in
   `--init-modules` and the `assert` passes, `load_state_dict` passes, and the run starts with
   English token embeddings sitting under foreign token ids. It will train out of it slowly and
   quietly and you will never see an error. The guard is names-and-shapes only. (Confirmation that
   the heads *were* reinitialised: the French fine-tune's first validation reads **7.294**.)
3. **`egs/commonvoice/ASR/zipformer/` has no `finetune.py`** — only the V1
   `pruned_transducer_stateless7_streaming` one. Copy
   `egs/librispeech/ASR/zipformer/finetune.py` in, swap the data module, delete the GigaSpeech
   `use_mux` branch. The LibriSpeech coupling is **14 lines in 5 places** (import 74, 1297, the mux
   block 1299–1309, dataloaders 1358–1369, `add_arguments` 1510). Keeps zipformer2, keeps the 73 MB
   pack, keeps chunk-16 export, **zero app seam changes.**

And one foot-gun on a multi-day run: `finetune.py` asserts `start_epoch == 1` when `do_finetune` is
true, so **resuming an interrupted fine-tune requires `--do-finetune False`.**

---

## 4. THE DATA, per language

All hours LANE-READ from Mozilla's own `cv-corpus-26.0-2026-06-12.json`; speaker counts from its
`splits`; MLS hours from the MLS paper's own Table 2; VoxPopuli from its README; MOSEL/Granary from
their cards.

### 4.1 The split trap — the single most expensive number in the corpus lane

| lang | validated h | official **`train`** split | validated − dev − test | `other` (unvalidated, same CC0) | speakers | accent buckets |
|---|---|---|---|---|---|---|
| **es** | 595.5 | 487.5 h | 552.4 h | **+1,554.3 h** | **26,940** | 12 |
| **it** | 363.7 | 262.4 h | 317.8 h | +33.8 | 7,342 | **1** |
| **pt** | 188.0 | **27.0 h** ⚠ | 165.5 h | +32.1 | 3,843 | **1** |
| **pl** | 176.9 | **32.5 h** ⚠ | 151.3 h | +3.1 | 3,470 | **1** |
| **tr** | 130.1 | 44.3 h | 104.7 h | +0.1 | 1,829 | **1** |
| **nl** | 126.4 | 56.4 h | 96.6 h | +7.2 | 1,884 | 9 |

**A recipe run at defaults trains Portuguese on one-sixth of the Portuguese that exists.**
`prepare.sh` already carries `use_validated=false` as a flag, so **`--use-validated-set 1` is a
one-word fix** — and it is the difference between a trainable corpus and a toy. Two follow-ons: you
must then carve your own **speaker-disjoint** dev/test (the `validated.tsv` pool is not partitioned
for you), and **`prepare.sh` line 334 hardcodes the BPE training text to `cv-<lang>_cuts_train.jsonl.gz`
even when `use_validated=true`** — so for pt/pl/tr/nl the tokenizer would be fit on a 3–7× smaller
text sample than you actually train on. One-line fix; do it.

Demographic skew, LANE-READ and load-bearing for a dictation previewer: **it/pt/nl are 52–67 % male
with 6–14 % female**, and it/pt/pl/tr carry **exactly one accent bucket** — you cannot even *measure*
dialect coverage, let alone balance it. Turkish has the best gender balance of the six and an unusual
age profile: **~30 % of Turkish clips come from speakers in their sixties or eighties**, which for a
130-hour corpus is a real acoustic-coverage question.

**The spontaneous arm is empty for us, an honest negative.** Common Voice now ships a
`spontaneous-speech` corpus (sps 4.0, 515 h) — exactly the domain the previewer wants. Our six:
**es 1.21 h, tr 0.28 h, nl 0.15 h, it/pt/pl absent.** Worth a re-check per release (0 → 515 h in four
releases); not a lever today.

### 4.2 Licence grades, text read at source

**CLEAN — CC0/CC-BY, non-YouTube, commercial + derived-model safe:**

| corpus | licence, LANE-READ | our languages | caveat |
|---|---|---|---|
| **Common Voice 26.0** | **CC0-1.0** on all 402 MDC datasets; page names ASR training as intended use | all six | **no re-hosting the tarball**, no speaker re-identification |
| **MLS** (SLR94) | *"Public Domain, Creative Commons Attribution 4.0"* — public-domain LibriVox audio, CC-BY compilation | es 918 · nl 1,554 · it 247 · pt 161 · pl 104 · **tr 0** | **read audiobooks, and thin on speakers** (§4.3) |
| **CML-TTS** (SLR146) | CC-BY-4.0, same LibriVox/Gutenberg well | nl 645 · es 443 · it 131 · pt 68 · pl 39 · **tr 0** | **do NOT add to MLS — take max() and de-duplicate by speaker/book** |
| **VoxPopuli data** | **CC0** (audio); human transcripts es 166 · pl 111 · it 91 · nl 53 · **pt 0 · tr 0** | five of six | **its code AND pre-trained models are CC BY-NC 4.0** — prepare it through lhotse's Apache-2.0 recipe, never through Meta's scripts |
| **MOSEL / Granary** | **CC-BY-4.0** Whisper-large-v3 pseudo-labels over that CC0 audio | es 16,970 · it 16,713 · pl 16,502 · pt 15,434 · nl 12,422 · **tr 0** | pseudo-labels (Whisper's WER is the ceiling); YouTube part is in **named directories you simply do not read** |
| **FLEURS** | CC-BY-4.0, ~10 h/language | all six | **reserve for evaluation** — it is the only clean cross-language yardstick we have |

**REFUSED, and two of them for the exact reason this project was burned before:**

- **ISSAI Turkish Speech Corpus** — `license: mit`, 218.2 h, the only substantial non-CV Turkish
  corpus. A lane extracted the publisher's own PDF (mdpi.com 403'd twice; zlib-inflated the streams)
  and it says verbatim: *"The data were collected from open sources … To acquire audio recordings,
  we used a command-line program to download videos from YouTube called youtube-dl…"* The
  transcripts are ISSAI's and MIT-able; **the broadcast audio is not theirs to license.** The
  derivative chain confirms it: `ulaspolat/tsc-tr-filtered-94h` declares **no licence at all**, and
  `KaanAydinli/…-clean` re-declares MIT downstream. **A licence that appears, vanishes and reappears
  down a three-hop chain is the signature of a tag, not a grant.**
- **mTEDx (SLR100) + TEDx Spanish (SLR67)** — **CC BY-NC-ND 4.0**. This kills 83 GB of the best
  *spontaneous* Spanish/Portuguese/Italian speech in the open world, twice over.
- **VoxForge** (via MDC, es 13.6 GB … **tr 726 MB**) — **GPL-3.0 on the data.** Copyleft of unknown
  reach over model weights; if a court held weights derivative, GPL-3.0 demands corresponding-source
  release. **Flatly incompatible with a paid closed-source app. Do not use VoxForge Turkish to plug
  the Turkish hole.**
- **MediaSpeech (SLR108)** — CC-BY-4.0 tag, and openslr's own page says *"extracted from media
  videos available on YouTube."*
- **SLR61/71–75 (es dialects), BIGOS-pl** — CC BY-SA; ShareAlike over weights is unsettled and
  **avoidable for all six**, so avoid it. BIGOS is additionally an *aggregation* with heterogeneous
  upstream licences — never train on an aggregation whose components you have not individually
  cleared.
- **Heroico (SLR39)** — openslr asserts "apache 2.0" over **`LDC2006S37`**. A third-party licence
  claim over a catalogued corpus.
- **YODAS2 / YouTube-Commons and everything on them** — the settled rule, unchanged.

### 4.3 The ranking — and it inverts depending on the question

| | es | it | pt | nl | pl | tr |
|---|---|---|---|---|---|---|
| **Tier A clean hours** (CV validated + VoxPopuli + max(MLS,CML-TTS)) | 1,679 | 702 | 349 | **1,734** | 392 | **130** |
| **Tier A, diversity-weighted** (CV validated + VoxPopuli human only) | **761** | 455 | 288 | 179 | 288 | 130 |
| speakers behind that figure | **~27,245** | ~7,648 | 3,843 | ~2,105 | ~3,752 | **1,829** |
| **Tier B** (CC0 EP audio + CC-BY pseudo-labels) | 16,970 | 16,713 | 15,434 | 12,422 | 16,502 | **0** |

**By raw hours: nl (1,734) ≈ es (1,679) > it > pl > pt ≫ tr.**
**By hours from many speakers: es > it > pl ≈ pt > nl > tr.**

**Dutch swaps from first to fifth between those two rows, and that swap is the most important
sentence in the corpus section.** Dutch's apparent wealth is 1,554 h read by **40 LibriVox
volunteers** (9 M / 31 F), plus a CML-TTS re-cut of the same well by 35. MLS-**Polish** is worse:
**103.65 h from ELEVEN speakers.** A transducer trained mostly on 40 voices reading 19th-century
prose will be excellent at 40 voices reading 19th-century prose. **INFERRED, and it is the corpus
lane's central recommendation: for a chunk-32 previewer, 400–700 h from thousands of speakers would
beat 1,500 h from forty** — because the zero-retraction property is a function of emission confidence
under *acoustic* mismatch, and mismatch is a speaker/channel problem, not a vocabulary problem.
**No measurement exists for this.** Dutch is the natural experiment and it is free (§7, E-nl).

**Recommended corpus shape, per language: all of CV-validated as the spine + VoxPopuli human
transcripts for register + VoxPopuli/MOSEL pseudo-labels for volume, with the LibriVox tier capped
as ballast, never the base.**

### 4.4 Turkish, stated plainly, and Korean/Indonesian, stated as a limit on the thesis

**Turkish is the one language where "Common Voice is CC0 so we own the clearance" does not close the
gap — it closes at 130 h and there is nothing clean to add.** Zero VoxPopuli (Turkey is not in the
European Parliament), zero MLS, zero CML-TTS, zero MOSEL, zero Granary — all of them are EU- or
LibriVox-scoped. The 218 h ISSAI corpus is youtube-dl-sourced under an MIT tag. Three routes: (a)
ship CV-only 130 h and let the gate decide — **and §5 shows the gate it appeals to does not exist**;
(b) take the ISSAI counsel question and reach 348 h; (c) **drop Turkish.** §5 recommends (c).

And a limit on the thesis worth stating so nobody over-reads it: **Common Voice holds id 33.76 h and
ko 2.54 h.** "Training our own dissolves all three problems" is a plan for **es/it/pt/nl/pl/tr and
nothing else.** It does not reach the two rows the qualification table books as counsel-heavy
(ko: AI-Hub/NIA; id: YODAS2). Korean is not rescued by any amount of GPU time.

### 4.5 One open counsel question, and it is small

Whether accepting an MDC **Data Consumer License** can contractually *narrow* CC0 for the accepting
party. CC0 is irrevocable once you hold the bytes; a contract can nevertheless bind you separately.
**Low stakes** — the only plausible obligations (no re-hosting, no re-identification) we would honour
anyway. One paragraph, bundled with the Korean/NIA question already queued. **Also note MDC's
Appendix 1 requires machine-generated data to be "clearly disclosed to downstream users as
machine-generated"** — that bites *outputs*, not the model, but if any plan proposes pseudo-labelling
or TTS augmentation from MDC material, that is where counsel looks.

---

## 5. WHAT OURS WOULD SCORE, AND AGAINST WHAT BAR

### 5.1 Three corrections to the bar, before any prediction

**Correction 1 — the qualification table divides by the most flattering denominator available, and
it does it systematically.** whisper-small's WER disagrees with itself by up to **2.6×** across the
three tables in its own paper, and the qualification table uses Common Voice 9 throughout — the
table on which whisper does **worst** for four of the six:

| | es | it | pt | nl | pl | tr |
|---|---|---|---|---|---|---|
| whisper-small **CV9** (what the table uses) | 10.3 | 16.0 | 12.5 | 14.2 | 16.9 | **23.7** |
| whisper-small **FLEURS** (neutral) | **5.6** | **9.8** | **7.3** | 16.4 | 14.7 | 15.9 |
| whisper-small **MLS** | 7.8 | 21.4 | 13.0 | 17.2 | 11.2 | n/a |
| whisper **large-v2** CV9 (≈ the turbo bar) | 5.6 | 7.1 | 6.3 | 5.8 | 7.6 | 14.5 |

**Correction 2 — the in-domain flattery is measured, not asserted, and it is ~1.5–2.3×.**
`duxx/turkish-stt-zipformer` publishes both sides for one checkpoint: **CV17-tr dev 13.09 = 0.55×
whisper-small** and **FLEURS-tr val 20.16 = 1.27× whisper-small.** A 2.3× swing in the ratio from the
corpus alone — and it is *generous* to us three further ways (beam-12 not greedy, dev not test, and
**FLEURS train was in its training data**). **A CV-trained model measured on CV test is measured at
home. Multiply every ratio in the qualification table by ~1.5 to get the kind you would experience.**

**Correction 3 — the tier, corrected against the code.** The accuracy lane prices the Ultra bar off
`ultra` = `ggml-large-v3-turbo-q5_0.bin`; the quality refuter read `WhisperModel.kt:227-238` and
**that row is `retired = true, unsupported = true`.** The turbo denominator reaches users through
gated **`npu-turbo`** (`NpuModelSpec.TURBO`, 8 Gen 3-class SoCs) with onboarding steering to it
(*"turbo recommended"*, `OnboardingFlowScreen.kt:812`). **So the bar is whisper-small everywhere
except NPU-capable flagships, where it is ~large-v2 class and every predicted pack is 2–5×.** The
lane's conclusion holds; the route and the scope were wrong. **The rung-3 rule cannot be evaluated
per-language — it is per (language × tier).**

### 5.2 The predictions, and applying the refuter's own method to all six

Two exponents, both LANE-READ: **k = 0.25** from Whisper's own law (*"WER halves for every 16×
increase in training data"*), and **k = 0.42** from icefall's own controlled 100 h-vs-960 h
LibriSpeech pair (same recipe, same schedule, same greedy decode: 2.69/6.64 vs 6.92/18.65). The
honest one for the 100–1,000 h band our six occupy is **0.42**; 0.25 is the optimistic bound. Anchor:
German at **11.5** at *our* cadence (the published 10.58 is a **640 ms** number; the same-checkpoint
640→320 penalty on LibriSpeech is **+9 %**).

⚠ **A lane disagreement that lands on exactly one language, adjudicated in §12: the accuracy lane's
predictions were computed on `commonvoice.mozilla.org/api/v1/stats/languages` (es **456** h), while
three other lanes read Mozilla's own release JSON (es **595.5** h). Every other language agrees
within ~2 %; Spanish disagrees by 30 %.** The predictions below are the accuracy lane's, i.e. on the
**pessimistic** Spanish figure. Recomputing Spanish at 595.5 h gives **16.4** honest, ratio **2.93×**
against FLEURS-es — **still past the ">2× → stop" line**, so the verdict holds and only the margin
changes.

| lang | CV h used | **honest (k=.42)** | + MLS | MLS-baseline route | **vs whisper-small FLEURS** (honest) | **verdict** |
|---|---|---|---|---|---|---|
| **es** | 456 *(595.5 → 16.4 / 2.93×)* | 18.4 | 11.6 | 7.2 | **3.3×** | **loses on `multi` alone. Survives only as MLS+VoxPopuli+CV** |
| **it** | 364 | 20.2 | 16.3 | 13.0 | **2.06×** | **at the stop line** |
| **pt** | 188 | 26.9 | 20.7 | 22.8 | **3.68×** | **every route loses. The clearest no** |
| **nl** | 126 | 31.7 | **10.6** | 13.8 | **1.93×** | **MLS-dependent, and MLS-nl is 40 speakers** |
| **pl** | 177 | 27.4 | 22.6 | 20.4 | **1.86×** | **widest disagreement in the set (13.8–27.4); refuted on shape** |
| **tr** | 130 | 31.1 | **no MLS** | n/a | **1.96×** | **CV cannot do Turkish** |
| *de, the anchor* | *1,392* | *11.5* | — | — | *1.13×* | *ships (with an email)* |

**SYNTHESIS: applying Correction 1 alone puts all six between 1.86× and 3.68×. There is no column in
which CV-only from scratch clears the ">1.5× → strip-only" line for any of the six.** The
`train-accuracy` lane's own 2-of-6-to-4-of-6 outcome was computed against the flattering denominator;
against the neutral one it is **0 of 6 from scratch on Common Voice alone.**

The MLS-baseline column is the most important one, because it is the only place where somebody
actually trained **one monolingual model per language on a fixed corpus and measured it against the
same whisper row**. It shows the break point cleanly: **at ~250 h+ the in-domain model beats
whisper-small; at 161 h (pt) and 104 h (pl) it loses by 1.7–1.8× despite home-field advantage.**

### 5.3 The bar that actually matters is error SHAPE, and a refuter measured the curve

WER is a poor proxy for "is watching this useful". The quality refuter found a way to stop guessing:
the German anchor publishes **per-epoch test hypotheses** (epochs 4–9 plus 30, all chunk-32-left-128
greedy on the identical 15,980-utterance test set) **and** its own **242,388-type training lexicon**.
That gives one language, one corpus, one lexicon, one decode, **seven checkpoints spanning WER 26.29
→ 10.58** — so the error shape can be **read off** the WER band every route predicts, instead of
extrapolated. (LANE-MEASURED, `shapecurve.py`; the parse independently reproduces the accuracy
lane's 52.9 % exact-match at epoch 30 to the digit.)

| checkpoint | **WER** | exact-match utts | utts w/ content error | **utts carrying an INVENTED non-word** | invented share of subs |
|---|---|---|---|---|---|
| epoch 30 | **10.58** | 52.9 % | 41.1 % | **24.4 %** | 37.3 % |
| epoch 9 | **17.63** | 35.3 % | 58.8 % | **40.2 %** | 41.4 % |
| epoch 8 | **18.58** | 33.0 % | 61.2 % | **41.5 %** | 41.4 % |
| epoch 7 | **20.58** | 30.4 % | 63.8 % | **43.7 %** | 42.5 % |
| epoch 6 | **21.50** | 27.3 % | 67.2 % | **48.1 %** | 43.0 % |
| epoch 5 | **24.26** | 23.8 % | 70.6 % | **51.5 %** | 45.1 % |
| epoch 4 | **26.29** | 20.8 % | 73.8 % | **56.5 %** | 45.2 % |

**Place the honest predictions on it: es 18.4 → ~41 % · pl 27.4 → past the 56.5 % row · tr 31.1 →
off the end of the curve.** Even on the *optimistic* law, Polish and Turkish put an invented non-word
on the strip in **more than two utterances in five.**

And the architecture guarantees you see all of it. **Zero retractions across 62 runs** means the
invented word is **displayed to completion** for the 0.5–2.5 s until whisper's cased, punctuated,
correct final replaces the line. Verbatim from the anchor's own **epoch-30, 1,344-hour, best-case-
in-the-world** file:

```
LANGZEITUNTERSTÜTZUNG NUTZT DU AM LIEBSTEN  ->  KLANZWETTUNTERSTÜTZUNG LUTZLOANID
BITTERE NACHRICHT MIT   ->  VETERELLEN          EINER PRESSEKONFERENZ -> BRÜCKKONFERENZ
IM LOTTO -> MLOTTO      ABER PSST -> APAPST     WEG DA -> BEKA        LASS UNS NOCH PAPRIKA -> AFRIKA
```

versus the English pack's **accepted** failure, from this repo's own rung 1: *"AND SAW MY FELLOW
AMERICANS … Every content word is right; the sentence's meaning is fully intact; the four errors are
unstressed monosyllables."* **These are not the same kind of wrong.** `saw` for `so` is slack.
`AFRIKA` for `PAPRIKA` is a different sentence.

**The failure mode that should stop a ship is not high WER. It is the confident wrong noun.** The
strip shows a plausible, well-formed, wrong content word; the transducer never revises it; the user
reads it, believes the app misheard, and **re-dictates** — strictly worse than no preview, because a
blank strip costs nothing and a wrong strip costs a retake. Then whisper's final fixes it, so **the
app demonstrates its own unreliability and then corrects itself, on every occurrence.**

### 5.4 The threshold below which it should not ship

**The written rule is already dead and the project knows it.** The rung-3 rule is *">1.5× `multi` →
strip-only; >2× → stop"*, and the shipping English previewer scores **WER 0.182 beside whisper's
0.000** — an infinite ratio — and shipped anyway, on a written human judgement about content words.
A ratio rule needs a corpus where the finalizer itself errs, which means FLEURS or a native-reader
set, not `jfk` and not the canary.

**SYNTHESIS — the four gates I would actually write, all measurable with the harness that exists,
all measured on the SAME audio for both models:**

| gate | metric | threshold | provenance of the number |
|---|---|---|---|
| **G0 — ship** | WER at 320 ms greedy, on **neutral out-of-domain** audio (FLEURS or a native-reader dictation set), never on the language's own Common Voice test | **≤ 18**, and **stop above 20** | Read off the curve above: WER 18–20 is the 41–44 % invented-utterance band, i.e. already past the shipping English pack's clean-audio 21 % and at its gated-audio 43.7 % edge |
| **G1 — ship** | utterances carrying a content-word error | **≤ 33 %** | the shipping English pack is 21.1 % clean / 43.7 % gated, and the project accepted **both**; 33 % is the midpoint and is where the German CV model lands |
| **G2 — ship** | content-word error rate | **≤ 13 %** | same provenance: 4.2 % / 10.0 % accepted, 12.95 % for German CV |
| **G3 — stop** | invented non-words as a share of substitutions, **after** discounting pure re-segmentations | **> 50 %** | at that point the strip reads as gibberish rather than as slack, and zero-retraction means the gibberish stands. German at epoch 5 is 45.1 % |
| **G4 — inversion, per tier** | preview WER ÷ finalizer WER on the same audio | **< 0.75× → must not ship as a preview** | the Estonian case: whisper-small et is 67.2 CV9 / 51.3 FLEURS. Two exits only — the pack becomes the **finalizer** for that language (a different promise: no casing, no punctuation, in several vocabularies no digits), or the language stays off |

G4 is **per-tier**, which is the part worth the owner's attention. On `multi` it plausibly fires for
every language above ~40 % in whisper's CV9 table (et, cy, ar, fa, hi, hu, sk, sl, lt, lv, sr, bn);
on the turbo tier it mostly stops firing. **The same pack can be a legitimate preview on one tier and
a self-contradiction on another.** One-line mitigation if such a language is ever wanted: require the
Ultra/NPU tier before offering the strip for it.

**Two honest weaknesses in these gates, stated before anyone else finds them.** (a) **G1/G2 have no
denominator** — whisper scores 0.000 on every clip this project has measured, so the finalizer's own
content-error rate is unknown. Closing that is 0.25 desk-days on top of experiment A (§7). (b) The
refuter's curve is an **epoch proxy**: an under-trained checkpoint at WER 26 is not a converged
data-starved model at WER 26. The refuter argues the bias runs **against** the plan (the epoch-4
German model has already consumed ~3,648 audio-hours and carries the full 5.65 M-token lexical
prior, where a converged 105 h Turkish model has a weaker prior over a richer morphology), and
experiment B (§7) replaces the proxy with the real thing for the price of a download.

### 5.5 The thing no route can predict, and it is the real risk

**Every number above — including the refuter's curve — is a read-speech, single-sentence, ~5-second,
prompt-driven number.** The app's traffic is spontaneous dictation, minutes at a time, with
disfluencies, restarts, proper nouns and phone-microphone acoustics. A CV-only model will be
systematically weak at exactly what CV does not contain: long-form continuity (the stateless decoder
has **2 tokens** of text context and its acoustic left context was only ever trained on 5-second
islands), disfluencies (CV has essentially none), and out-of-prompt vocabulary (**51.9 %** of the
German model's substitutions already produce strings absent from its own test references).

**And this project has already measured its own version of the domain penalty, on the shipping path.**
The English pack goes **0.182 → 0.318 WER on flatline-gated audio and destroys a content word**
(`country → CUTTER`) while whisper-small is *"completely unmoved by the gating"* — **+75 % relative
on the preview, 0 % on the finalizer**, for the best-resourced pack we own. The flatline cut is in
the app at versionCode 84. A 105-hour Turkish pack has **less** redundancy to spend on that, not more.

### 5.6 Two defects that would ship silently, both found by the refuter

1. **The normalizer IS the labels, and it is unvalidated.** `preprocess_commonvoice.py` implements
   **4 locales out of 443** (`en`, `fr`, `pl`, `yue`) and raises `NotImplementedError` for
   everything else — **including `de` (the anchor), `es` and `tr`**. The **`fr` branch, in master
   since 2023-11-09, deletes every lowercase Latin letter**: LANE-MEASURED,
   `'Bonjour, le monde est grand.'` → `'B    '`. It has stood in the primary source for ~2.8 years.
   The claim is not that it broke the published French model (that model predates it); **the claim is
   worse — nobody has ever run it and looked.** And the German anchor's own normalizer is
   **unpublished**, so **the one datapoint the whole programme rests on is not reproducible.**
2. **Turkish `.upper()` collapses ı/i, and WER cannot see it.** Python's `.upper()` is not
   locale-aware. LANE-MEASURED: `yıl→YIL` *and* `yil→YIL`; `sık→SIK` and `sik→SIK`; `bilgi→BILGI`;
   `iyi günler→IYI GÜNLER` — while `İstanbul→İSTANBUL` keeps its dot, so the convention is
   **inconsistent as well as lossy**. Two consequences: the label set destroys a phonemic
   distinction the model then cannot learn, **and** every Turkish preview word containing *i*
   renders dotless — a misspelling to a Turkish reader on essentially every utterance. **Because
   the references pass through the same function, WER is blind to all of it.** (A lane refuted its
   own adjacent argument honestly: the `[0-9]` strip is a **no-op** on Common Voice — LANE-MEASURED,
   zero digit-bearing sentences in all four sentence pools, because Mozilla's own sentence
   extraction already rejects numerals.)
3. **Therefore a cost nobody has priced: before any of these languages can be *evaluated*, someone
   who reads the language must produce a spontaneous-dictation reference set and classify the pack's
   errors as content / morphology / function.** That is the gate the English pack actually passed,
   and it is quoted in this repo. There is no Spanish, Polish or Turkish equivalent of that sentence
   available to this project. **It is a prerequisite for the ship decision, not a nice-to-have.**

---

## 6. THE ONE-LANGUAGE LOOP, STEP BY STEP

Under the owner ruling *one language at a time*. **A** = automatable, **H** = human decision,
**A/H** = scripted but a human reads the output first. Corrections from the refuters are folded in
and marked **⚠**.

| # | Step | Who | Instrument | Output |
|---|---|---|---|---|
| 0 | **Send the two checkpoint-licence emails** (`Zengwei`, `daniel-dona`) and read the MDC licence line for your language | **H** | §3, §4.5 | a grant, or a fallback to from-scratch |
| 1 | Pick language + corpus shape | **H** | §4 | a code, a CV release, a supplementary-corpus decision |
| 2 | **⚠ Acquire the tarball from Mozilla Data Collective** — *not* `lhotse download` (S3 = 403) | **A/H** | browser + credentials | `download/cv-corpus-26.0/<lang>/` + a recorded sha256 |
| 3 | Validate what arrived | **A** | sha256, clip count vs Mozilla's own stats, `wc -l` on the TSVs | a one-screen census that matches §4.1 |
| 4 | **Write the language's `normalize_text` branch** — the most consequential 3 lines in the loop | **H** | `local/preprocess_commonvoice.py` | **⚠ with a written test.** Keep `'`. **⚠ Turkish needs locale-aware casing or must stay lowercase.** Decide digit policy (drop the utterance, don't strip-and-keep) |
| 5 | **⚠ Apply PR #1955 to the CV datamodule** before anything runs | **H** | `asr_datamodule.py` | lower `num_buckets`, drop `shuffle_buffer_size` |
| 6 | lhotse manifests — `prepare.sh` stages 1–3 | **A** | `lhotse prepare commonvoice` + `preprocess_commonvoice.py` | `cv-<lang>_{recordings,supervisions}_{train,dev,test,validated}.jsonl.gz` |
| 7 | Filterbank features — stages 4–8. **⚠ `num_splits` 20–50, not 1000.** **⚠ Run stage 4 alone first** and measure the real lilcom ratio | **A/H** | `compute_fbank_commonvoice_*.py` | `data/<lang>/fbank/*.lca` + cut manifests + a measured disk figure |
| 8 | BPE — stage 9. **⚠ Fix line 334** to read the *validated* cuts when `use_validated=true` | **A** | `train_bpe_model.py` (unigram, vocab 500) + `prepare_lang_bpe.py` | `lang_bpe_500/{bpe.model,tokens.txt}` — **the app's fourth pack file** |
| 9 | **⚠ Assert dev/test hygiene**: empty intersection of validated-cut ids and the dev/test id lists | **A** | one script | a permanent gate, not a one-off check |
| 10 | Smoke test: `--max-duration` bisect + **measure sec/batch** + write the systemd resume wrapper | **A/H** | `train.py --num-epochs 1 --valid-interval 50`, killed at ~200 steps | the batch ceiling, **the number that prices the schedule**, a restartable job |
| 11 | **The run.** Fine-tune preferred: `finetune.py --init-modules "encoder,encoder_embed" --base-lr 0.0045 --lr-batches 100000 --lr-epochs 100 --causal 1 --use-fp16 1`. From scratch: `train.py --base-lr 0.025`. **Both: `--use-validated-set 1`, `--keep-last-k 8`, and leave `--chunk-size`/`--left-context-frames` at their DEFAULT LISTS** | **A** | icefall | `epoch-N.pt`, `checkpoint-<batch>.pt`, tensorboard. Watch `grad_scale` and GPU clocks at hour 0 vs hour 4 |
| 12 | Pick the checkpoint | **H** | `streaming_decode.py --chunk-size 16 --left-context-frames 128 --decoding-method greedy_search` over an `--epoch/--avg` grid | an `(epoch, avg)` pair and a **greedy @320 ms** WER. **⚠ Never quote a beam-search or simulated-streaming number — the app decodes greedily, chunk-wise** |
| 13 | **Export + int8, one command** | **A** | `export-onnx-streaming.py --causal 1 --chunk-size 16 --left-context-frames 128` (int8 is on by default, **no calibration data needed**) | three `.int8.onnx` files. **⚠ The script takes `--tokens` as an INPUT and never writes one out — copy `tokens.txt` by hand** |
| 14 | Verify the export **before the app ever sees it** | **A/H** | `onnx_check.py`; a metadata dump; `PackTokenFacts` over `tokens.txt` | the exact literals a catalogue row will pin |
| 15 | **Publish to our own HF repo at an immutable commit, with our own LICENSE and model card** | **H** | git + HF | the `resolve/<sha>/` `baseUrl`. **Not optional** — see below |
| 16 | The app-side pack | **H** + **A** | §6.2 | a green pack on the internal track |

**Step 15 is not a nicety and is easy to skip.** `StreamingPack.baseUrl` is a **commit-pinned
`resolve/<sha>/` URL and is not dead code** — debug builds, sideloads and Play-refused installs have
no asset pack to fetch. Publishing our own weights at an immutable commit is a **required pipeline
step**, and it is also the act that makes the licence position ours.

### 6.1 The one flag that decides whether this is the same feature

`--chunk-size 16` → `decode_chunk_len = chunk_size × 2 = 32` → `T = decode_chunk_len + (7 + 2×3) = 45`
→ `padMs = padMsFor(45) = 500`, the one measured pad value. **`--chunk-size 32` instead gives
`decode_chunk_len 64`, `T 77`, cadence 640 ms, `padMs 820` — a *different pack class*, with a
different word lag, a copy rule that forbids quoting 0.4 s, and owner ruling O7 still open on
whether it ships at all.** One flag. (And note `chunk_size[0]` takes the **first** element of the
list, so passing the training list `"16,32,64,-1"` at export is benign but `"32,16"` would silently
give you 640 ms. **Pass a single value.**)

**One training run yields BOTH packs from the same weights.** RESULTS.md quantifies the trade from
identical weights: 320 ms greedy 3.06/7.81 vs 640 ms 2.81/7.15 — **~8 % relative better test-clean at
640 ms.** So the cadence decision stays open for free, and O7 can be tested without retraining.

### 6.2 The app's own gates, as they exist in this checkout

| gate | what the pipeline must deliver |
|---|---|
| **Four files, pinned digests** | `PackFile(name, bytes, sha256)` × 4 — three `.int8.onnx` + `tokens.txt`. The shipping row's total is **72,654,782 B** exactly. The sha256 pinning is what catches forgetting to copy `tokens.txt` |
| **`modelType = "zipformer2"`** | written as a **literal** by the export script; cannot be mis-set. `PreviewPackMetadataTest` asserts the catalogue literal **and** re-reads it off the encoder file |
| **`decodeChunkLen = 32`** | ⇒ `cadenceMs = 320`. From `--chunk-size 16` (§6.1) |
| **`encoderT = 45`** | ⇒ `padMs = padMsFor(45) = 500`. **Not** a function of `decodeChunkLen` — READ off the file, because v1 exports write `T = decodeChunkLen + 7` |
| **The head-dims keys** | `query_head_dims` / `value_head_dims`, written **unconditionally** by the v2 export. sherpa-onnx's `InitEncoder()` reads exactly ten keys and dies on their absence; the v2 export writes a **superset** |
| **`tokens.txt` whose shape drives the copy** | `PackTokenFacts` derives `caseFold`, `emitsPunctuation`, `emitsDigits` from the file — never typed. Our normalizer yields uppercase, digit-free, one punctuation piece (`'`), no byte fallback ⇒ `Fold / false / false`, the shipping row's values. **⚠ Do NOT hard-code 502 lines: `max_disambig` is a property of the corpus lexicon, so a new language can yield 501 or 503. `vocab_size` stays 500 either way.** (`wc -l` after stage 9 settles it, free) |
| **A canary with a mechanically checkable expectation** | A per-pack clip in main assets (**81,998 B** for English) + a `PreviewCanaryRule(expected, minMatches, maxTokens)`. **Because our vocabulary has no digit pieces at all, the model *cannot* emit numerals** — so the Italian rule is `[{uno},{due},{tre},{quattro},{cinque}], minMatches 4, maxTokens 20`: one alias per position, no `one`/`1` ambiguity, verifiable by a non-speaker against `tokens.txt`. **Grep `tokens.txt` for the five `▁`-prefixed pieces before recording the clip** — a five-second check that makes the canary a formality instead of a coin flip. Kokoro already ships in the app and has Italian and Spanish voices |
| **`RTF ≈ 0.12`** | measured 0.054 at max pace / **0.116 at real-time pace**, 2 threads, provider cpu. **>0.12 at real-time pace is a kill** |
| **`+169 MB RSS`** | the measured delta. A pack that keeps every model-shape default reproduces the 66,110,931-parameter graph, so this transfers by construction — but it must be *measured*, not assumed |
| **Zero retractions** | across 62 runs plus a ten-minute thermal run of 1,210 words. **Any retraction on the Tab over ≥20 utterances is a kill** |
| plus | `PreviewPackLayoutTest` (9 tests: a new `com.android.asset-pack` module, `deliveryType on-demand`, no `deviceTargetingConfig`, the `.gitignore` wall, the payload dir with only four files + `.gitkeep`, `verifyPreviewPack` byte-literal rows, `sourcePinnedInputs`), `tools/build_asset_packs.py`'s restated triples, and `StreamingPackCopyTest` (the literal `"typed transcript"`, no banned speed words, no `\d+ms` claim, size from the pack's own `totalBytes`) |

**The module + row + pins is mechanical — well under a day, because it is a copy of `preview_en`
with four numbers changed.** What is *not* mechanical is the canary and the copy. And the Play
arithmetic: one new asset-pack slot (4 of 100 used today) and **≈ +60 MB of AAB after deflate**
(73 MB × 0.815), per language.

---

## 7. THE MINIMUM FIRST EXPERIMENT — under a week, kill lines stated in advance

**The refuters reshaped this.** The `train-pipeline` lane's six-day plan was built to prove the loop
closes and price the run. That is no longer the first question, because **the loop's existence is
now well established from source and the thing that decides go/no-go is accuracy — and accuracy is
testable for almost no GPU.** So the week leads with three desk experiments that can kill the
programme before a single multi-day run starts.

### Phase 1 — three desk-days, ~1 GPU-hour. This can end the programme.

| # | experiment | cost | what it settles |
|---|---|---|---|
| **A** | **The German dry run, with the shape script bolted on.** Take `daniel-dona/…-streaming-de` (four int8 files, `decode_chunk_len 32`, loads with the app's hard-coded `"zipformer2"`) through the **existing rung-1 harness** against **whisper-small q5_1 on the identical clips** — FLEURS-de + one long-form German clip + one flatline-gated clip. Classify **both sides** with `shapecurve.py`. Run turbo over the same clips while you are there | **1 desk-day, no GPU, no device** | **The entire hypothesis.** The only test that puts a CV-trained streaming Zipformer and this app's own finalizer on the same audio in the same harness — **and it finally gives G1/G2 a denominator.** If 1,344 h of German cannot clear G0/G1/G2 on neutral ground, nothing trained on 126–595 h will |
| **B** | **Decode the Spanish checkpoint that already exists.** `daniel-dona/…-streaming-es` holds `exp/epoch-30.pt` (1,058,856,997 B) — same author, same 66 M recipe, epoch 30, Spanish, **no WER published anywhere.** Decode at `--chunk-size 16` and `32`, greedy, on CV-es test; run `shapecurve.py` over its `recogs` against its own `words.txt` | **0.5 desk-day + ~1 GPU-hour** | **The first Spanish number and the first 320 ms Common Voice number in existence** — *and* a real **converged low-resource** point that replaces the refuter's epoch proxy with the thing it proxies. Also pins the 640→320 penalty on Common Voice instead of borrowing LibriSpeech's +9 %. **⚠ That repo's `train.sh`/`decode.sh` are LibriSpeech boilerplate (`--full-libri 1`, `test_wavs/1089-*`) — they record the template, not what was run** |
| **C** | **Run `shapecurve.py` on every candidate pack that publishes a `recogs` file**, before downloading any weights. It needs only `recogs-*` + `words.txt` | **15 min per model, no GPU** | Turns the qualification table's WER column into a **shape** column for free. It is the gate this project actually ships on, and it can now be read off the internet |
| **D** | **Send the two checkpoint-licence emails** and confirm the MDC CC0 line for your language | **1 hour** | Whether the fine-tune shape (2.8–4.6 GPU-days) exists at all, or the bill is 28.8–68.5 |

### Phase 2 — one day of toolchain, then one overnight run

| # | experiment | cost | what it settles |
|---|---|---|---|
| **M0** | `pip install k2 -f https://k2-fsa.github.io/k2/cuda.html`; `python -m k2.version`; `torch.cuda.get_device_capability()`; `torch.cuda.is_bf16_supported()` | **5 min** | Whether the wheel's fatbin embeds `sm_89`/`sm_75` or falls back to PTX JIT. **Discover this in five minutes, not on hour nine of feature extraction** |
| **M1** | Prepare one language's fbank, then `train.py --world-size 1 --causal 1 --use-fp16 1 --num-epochs 1 --valid-interval 50 --max-duration N`, bisecting N over {300, 500, 700, 900}. Read `Maximum memory allocated so far is XMB` | **~30 min/card** | The real per-card ceiling, including fragmentation, which no arithmetic can predict. `scan_pessimistic_batches_for_oom` deliberately feeds the largest batches first |
| **M2** | The same run logs a timestamped line every 50 batches. Take the delta between batch 50 and batch 150, divide by 100 | **~20 min** | **Collapses every GPU-day figure in this document from ±2× to ~±10 %.** Highest value per minute in the programme. Watch `nvidia-smi --query-gpu=utilization.gpu -l 1`: **below ~85 % means dataloader-bound and `--num-workers` is free speed** |
| **M3** | 90 min of `nvidia-smi --query-gpu=clocks.sm,clocks_throttle_reasons.active,temperature.gpu,power.draw -l 10`; compare median step time of batches 50–150 against 400–500, and SM clock at minute 5 vs minute 80 | **90 min, unattended** | The MS-02's sustained-load derate. The reference run is **flat at 68–70 min/epoch for 30 straight epochs**, so throttling shows as monotonically rising per-epoch time within the first hour |
| **E-nl** | **The pilot: de→nl fine-tune, 14 epochs**, `--init-modules "encoder,encoder_embed"`, `--use-validated-set 1`, `--base-lr 0.0045`. Reboot the box on purpose once and confirm `--start-batch` restores the sampler **and** the grad scaler | **~0.4–0.9 GPU-days** | Whether a close-language fine-tune at **126 hours** clears the bar. **This is the number nobody in the world has measured, and it is the number the whole programme turns on** |
| **E-div** | *(optional, +0.8 Ada-days)* The paired diversity run: CV-nl 96.6 h / 1,884 spk vs MLS-nl 1,554 h / 40 spk, both chunk-32, both evaluated on CV-nl test **and** FLEURS-nl | **~0.8 GPU-days** | "Does diversity beat hours at chunk-32", for all six languages, from one language that holds both extremes |

### Phase 3 — the pack, and the device gate

Decode at chunk-16 greedy → export + int8 → `onnx_check.py` → metadata dump → `PackTokenFacts` →
HF publish at a pinned commit → `preview_nl` module + catalogue row + `build_asset_packs.py` triples
+ Kokoro canary → `:app:testDebugUnitTest` → sideload via the HF-fallback path → **measure on the
Tab S10+: RTF at 2 threads, word lag p50/p95, RSS delta, retraction count over ≥20 utterances.**
~1.5 desk-days.

### THE KILL LINES, written before the run so they cannot be negotiated afterwards

- **K1 — day 0, licence.** No readable licence permitting training weights we redistribute in a paid
  app, for the chosen language's Common Voice release → **STOP.** (The corpus lane read CC0-1.0 on
  all 402 MDC datasets, so this is a **confirm**, not a discovery. The *checkpoint* licence is the
  live one.)
- **K2 — day 1, THE BIG ONE.** The German pack, at 1,344 hours, cannot clear **G1 ≤33 %
  content-error and G2 ≤13 %** on FLEURS-de against `multi` on the same clips → **STOP THE
  PROGRAMME.** Nothing trained on 126–595 h will clear it. This kill costs one desk-day.
- **K3 — day 1, inversion.** The German pack's WER on that audio is **below 0.75×** whisper-small's
  → the preview premise inverts for that whole language class → **re-scope to a finalizer feature,
  do not train a previewer.**
- **K4 — day 2, Spanish.** The existing Spanish checkpoint at chunk-16 greedy scores **worse than
  WER 20 on CV-es test** — its own corpus, the friendliest read that exists → **the best-resourced
  language in the set is dead, and so is the programme's Spanish arm.**
- **K5 — day 3, toolchain.** CUDA `k2` will not import on the MS-02 within one day → **re-scope to
  WSL2 or a rented GPU. Do NOT start compiling k2 from source inside a one-week experiment.**
- **K6 — day 3, hardware.** The largest `--max-duration` surviving 200 real steps on the 16 GB card
  is **below ~100** (the reported convergence floor) → **STOP.** That is a hardware answer, not a
  tuning answer. *(Expected to pass comfortably: the measured German point is 6,462 MB at md 300.)*
- **K7 — day 4, stability.** `grad_scale` collapses below 0.01 twice **after** applying PR #1955 and
  `base_lr 0.025`/`0.0045` → **stop this shape on these cards** and report; the run dies loudly at
  1e-5 with a saved bad model, so this costs ~2 GPU-hours to discover, not a week.
- **K8 — the app's contract, non-negotiable.** The exported encoder's metadata is not
  `zipformer2` / `decode_chunk_len 32` / `T 45` with **both** head-dims present, **or** the pack
  shows **any retraction** on the Tab over ≥20 utterances, **or** RTF at 2 threads exceeds **0.12**
  at real-time pace, **or** the RSS delta materially exceeds the shipping **+169 MB** → **STOP and
  report.** A pack that misses these is not a slower pack, it is a **different feature.**
- **K9 — the ship line.** The pack's WER at 320 ms greedy on **neutral out-of-domain** audio exceeds
  **20** → **do not ship it.** Between 18 and 20 it is a schedule question (more epochs, more
  corpus); above 20 it is a no.

**What a pass proves, stated narrowly so nobody over-reads it.** That data → manifests → features →
BPE → a resumable causal run → a chunk-16 int8 export → a pack satisfying every committed app gate
and keeping the measured feel, is a **closed loop on the owner's own hardware, with a measured
per-language cost in days** — and that a close-language fine-tune at 126 hours clears the shape bar
in one language. **It proves nothing about the other five.**

---

## 8. VERSUS BUYING KROKO AT $150/mo

**$150/mo is $1,800/yr. That is not the comparison, and the money is not the question.**

### The decisive question is unanswered and free to ask

> **Does a `decode_chunk_len = 32` (320 ms) Kroko build exist for any language at any tier?**

Every fact we have says no, and nobody has asked. LANE-READ, from the qualification table:

- Every Kroko community drop is `-64-L-Streaming-001` (**640 ms**) or `-128-L-Streaming-001`
  (**1,280 ms**) — and the `-L` number is **left context, not chunk**, so the "128-L" builds read
  `decode_chunk_len = 256` = **2,560 ms**.
- **There is no Kroko community build below 1,280 ms for any language**, and **no int8 sherpa export
  of any 64-L build exists** that two independent hunts could find.
- kroko.ai's only statement is that the commercial models *"add lower-latency options."* Their site
  cites "320 ms" **partial latency** — which is not the same claim as `decode_chunk_len 32`.
- **The app's word lag is cadence-derived.** sherpa emits at most once per encoder chunk, so a
  chunk-128 pack **cannot** have a word lag below 1.28 s. That is not word-for-word; it is
  short-phrase-by-short-phrase, **4× the experience the owner validated.**

**If the answer is no, $150/mo does not buy the feature the owner validated, and es/it/pt/nl/pl
close for a reason no money fixes.** That question is worth more than the decision it might cancel.
**Ask it before spending anything, and before starting any training run.**

### And the licence is not readable

`Banafo/Kroko-ASR`'s card front matter is `license: other`, **`license_name: "test"`**,
`license_link: LICENSE` — and **`LICENSE` is 0 bytes** (HTTP 200, `size_download=0`, confirmed twice
independently). The prose says *"CC-BY-SA licensed community models"* with **no version anywhere**.
The `es-kroko` mirror's entire README is **55 bytes**: *"See license at …/Banafo/Kroko-ASR"* — a
pointer to an empty file. And Kroko's own card scopes community models to *"hobby projects,
research, or free tiers"* while directing production to *"commercial models … Licensed for
professional and closed-source products."*

### The honest scoreboard

| | **Buy Kroko** | **Train our own** |
|---|---|---|
| **Cadence** | **640 / 1,280 / 2,560 ms** in every readable artefact. 320 ms **UNSTATED at any tier** | **320 ms by construction** — the recipe default, at zero premium, and 640 ms is a re-export of the same weights |
| **Licence** | 0-byte `LICENSE` under `license_name: "test"`; unversioned "CC-BY-SA"; community tier scoped away from production | **CC0 corpus + our own LICENSE on our own weights at our own immutable commit.** One open counsel question, low stakes |
| **Token shape / app work** | Mixed-case, punctuated, digit-bearing token files (nl has **all ten ASCII digits**; two mirrors carry a **4-byte length-prefix defect**) ⇒ pays the whole §4.1 flag matrix | **Uppercase, digit-free, one punctuation piece** by construction ⇒ derives `Fold / false / false`, the shipping row's values. **Deletes that column of work** |
| **Accuracy** | **NOT PUBLISHED for any Kroko community model, in any language.** Only an aggregate "Open-ASR 5.66 %" | **Predicted 1.86×–3.68× whisper-small on neutral audio from CV alone** — i.e. probably not good enough, by §5 |
| **Cost** | $1,800/yr, recurring, plus a dependency | ~1–2 GPU-days/language on hardware already owned + ~1 desk-week/language |
| **Coverage** | es/it/pt/nl (and **pl does not exist at all** — exhaustive negative across 8 indexes) | the six, in principle; **three closed on evidence** in §5 |

**SYNTHESIS.** Training **dominates Kroko on cadence, clearance and app work** — the three things
this app's contract actually asserts. **It does not dominate on accuracy**, and that is the honest
asymmetry: Kroko's WER is unpublished, and their community models might well be *better* than a
130–595-hour self-trained pack while being unusable at 4× the cadence. **The two options fail for
different reasons, which is why the cheap experiments in §7 and the Kroko email should both happen
in the same week.** Neither is a substitute for the other, and if both fail the answer is that these
six languages stay off — which is a legitimate outcome and was already the status quo.

One thing the Kroko route cannot do at any price, worth naming: **the two byte-level defects in the
`kouhxp` mirrors' `tokens.txt` (a 4-byte LE length prefix, so line 0 reads `\xdb\x17\x00\x00<blk> 0`)
are somebody else's bug in an artefact we would pin the sha256 of.** We would be pinning a corrupt
file and writing a workaround. A self-trained pack's `tokens.txt` is the lang-dir file copied
verbatim, and its shape is what derives the copy.

---

## 9. WHAT WOULD MAKE ME WRONG

Ordered by how much each would change the verdict.

1. **The de→nl fine-tune at 126 h clears G0/G1/G2 on neutral audio.** My whole "no" on four
   languages is a scaling-law extrapolation plus an epoch-proxy curve. **A measured cross-lingual
   Zipformer fine-tune below ~250 hours has never been done, by anyone, for this architecture.** If
   Dutch lands at WER ~15 on FLEURS-nl (whisper-small nl FLEURS is 16.4), Dutch ships and my
   reasoning was too pessimistic by a wide margin. **This is why experiment E-nl exists and why it
   is one overnight, not a commitment.**
2. **The transfer gain widens more than 8.7 % as hours shrink.** The only measurement is at **864
   hours** (French: 10.90 → 10.57 → 9.95). If the gain at 126 h is 2× rather than 1.09 %, four of my
   six "no"s flip. The finetune lane infers it should widen; **nobody has measured it.** The
   ~20-GPU-hour hours-ablation (25/50/100/130 h × 14 epochs from the German checkpoint) is the one
   cheap experiment that could overturn the Turkish and Polish verdicts — **but note the bar it
   would have to clear: on the German curve, Turkish must move from the epoch-4 row to the epoch-30
   row, a 2.5× WER improvement, not an 8.7 % one.**
3. **The error-shape curve is an epoch proxy.** An under-trained checkpoint at WER 26 is not a
   converged data-starved model at WER 26. The refuter argues the bias runs against the plan and I
   find the argument sound, but it *is* a proxy. **Experiment B replaces it with a real converged
   low-resource point for the price of a download**, and if the real point is materially kinder than
   the proxy, Spanish and Italian move.
4. **G0/G1/G2/G3 are a reasoned proposal calibrated on this project's own accepted precedent, not a
   measurement — and they have no denominator.** whisper scores 0.000 on every clip this project has
   measured. If whisper-small's own content-error rate on FLEURS-de turns out to be 15–20 %, the
   ratio picture changes materially in our favour. **Experiment A closes this for 0.25 extra
   desk-days, and nothing in §5 should be treated as settled until it does.**
5. **The in-domain flattery factor rests on ONE model, whose corpus list is contaminated.** The
   ×1.5–2.3 correction comes from `duxx/turkish-stt-zipformer`, and the refuter established that its
   training data includes the refused ISSAI corpus, an NC corpus, and **FLEURS train**. If the real
   flattery factor is 1.1 rather than 1.5, Italian and Polish move back inside the line.
6. **No primary per-language large-v3-turbo table exists anywhere the lanes could reach.** The Ultra
   bar is INFERRED from large-v2's rows. turbo is a distilled 4-layer-decoder model; if it is
   materially worse than large-v2 multilingually, the flagship bar is closer than §5.1 says. **And
   turbo is the steered default on capable devices**, so this gap matters more than any single
   prediction. Closing it is free on the side of experiment A.
7. **Speaker diversity might not matter as much as the corpus lane infers.** The whole "400 h from
   thousands beats 1,500 h from forty" recommendation rests on a mechanism argument with **no
   chunk-32 ablation behind it.** If MLS-nl's 1,554 hours from 40 readers trains a good chunk-32
   previewer, Dutch *and* Spanish open up cheaply and the Tier-A ranking inverts back. **E-div
   settles it in one paired run in one language.**
8. **The clearance argument for adding MLS may be stronger than I treated it.** I wrote "adding MLS
   reopens the clearance conversation the prize was supposed to close" — but MLS is **CC BY 4.0 over
   public-domain LibriVox audio**, which is arguably *cleaner* than a CC0 corpus that now sits behind
   an account and a Data Consumer License. If counsel says MLS + VoxPopuli + MOSEL pseudo-labels is
   fine, the accuracy picture improves for **five of six** and only the compute bill grows
   (28.5 Ada-days for the set). **That reframes the programme from "refuted" to "expensive", and it
   is one counsel paragraph away.**
9. **"One language at a time" may be the wrong shape, and nobody costed the alternative.** A single
   multilingual pretrain over all 1,580 CV hours plus per-language fine-tunes dominates on compute
   (~10 Ada-days once, then ~0.4 d each), probably dominates on accuracy at 126–188 h, **and** — via
   a shared BPE — would let `decoder` and `joiner` transfer *between target languages*, not just the
   encoder. It also sidesteps the third-party-checkpoint licence question entirely, because the
   pretrain would be ours. **No lane tested or costed it. It is the most promising unexplored shape
   in the programme.**
10. **A smaller model is unexplored and would change every number.** The Zipformer-**S** overrides
    (23.3 M params) are published only for `--causal 0`, but they compose mechanically with
    `--causal 1` and would cut VRAM and training time substantially while shrinking the pack from
    ~72 MB to perhaps ~26 MB. **No upstream streaming-S result exists**, so nothing is assertable
    about its WER — but at 126–364 hours a 23 M model may well be the *right* capacity, and a
    66 M model on 130 hours may be part of why the predictions are bad.
11. **I read the lanes, not the primaries.** Every external fact here is a second-hand read. Two
    places where that bites hardest: **(a)** the German checkpoint's licence, which one lane read
    through `resolve/main` (getting apache-2.0) and another read through the API (getting nothing) —
    the routes disagree and **the API route is the one that matches how this project was burned
    before**; **(b)** the MDC CC0 finding, which came from a REST-API sweep of `licenseAbbreviation`
    fields plus one language's dataset page — **nobody read the Data Consumer License text for the
    language you would actually download.** Both are an hour of the owner's own reading and both sit
    upstream of everything else.
12. **The lanes' search coverage was crippled and they said so.** The session's WebSearch budget was
    exhausted (200/200) before three of the six lanes began, so discovery was API-, tree- and
    index-driven. **The k2-fsa Zulip and the entire non-GitHub open web went unsearched.** Any
    single-GPU icefall training report, any Turing datapoint, any per-language Kroko number living
    on a forum, a blog or Reddit is missing from this evidence base. **Re-run the discovery with a
    real search budget before treating the four-datapoint throughput table or the "no Turing report
    exists" finding as complete.**

---

## 10. WHAT TO DO ON MONDAY, in order

1. **Two emails and one licence read (1 hour).** `Zengwei` and `daniel-dona`, asking for an explicit
   grant on `exp/pretrained.pt` / `exp/epoch-30/epoch-30.pt`. And register on Mozilla Data
   Collective, open the Common Voice Dutch dataset page **and** the Data Consumer License, and quote
   the licence line yourself.
2. **The third Kroko question (10 minutes).** *"Does a `decode_chunk_len = 32` build exist for any
   language at any tier?"* It is free and it can cancel a $1,800/yr decision.
3. **Experiment A — the German dry run (1 desk-day, no GPU).** This is the day that decides the
   programme. Run turbo over the same clips while you are there.
4. **Experiment B — decode the unmeasured Spanish checkpoint (0.5 desk-day + 1 GPU-hour).**
5. **Experiment C — `shapecurve.py` over every published `recogs` file (15 min each).**
6. **Only if A and B pass: M0 → M1 → M2 → M3 on the MS-02 (one day), then the de→nl fine-tune
   pilot (one overnight).**
7. **Apply PR #1955 to `egs/commonvoice/ASR/zipformer/asr_datamodule.py` before the first run.** One
   line, upstream, merged, free, and it addresses the one divergence trigger left unfixed in the
   exact recipe directory we would use.

---

## 11. THE NUMBERS THAT DO NOT EXIST, AND WHAT EACH COSTS TO GET

| unknown | cheapest measurement | cost |
|---|---|---|
| **From-scratch CV-only streaming WER for a causal zipformer2 in ANY language below 1,344 h** — the programme's real accuracy risk | Experiment B (decode the existing Spanish checkpoint) | 0.5 d + 1 GPU-h |
| **Whisper's own error shape on the same audio** — G1/G2 have no denominator | Experiment A, + `shape.py` over whisper's output | +0.25 d |
| **Any 320 ms Common Voice streaming WER, in any language** — does not exist; de was decoded at 640 ms, fr at 64, tr at 32 | Experiments B and X3 (re-decode German at chunk-16) | 0.75 d |
| **Per-card `--max-duration` and per-card throughput** — every GPU-day figure hangs on an unmeasured factor | M1 + M2 | 50 min |
| **A per-language large-v3-turbo table** — the steered default on capable devices, and inferred from large-v2 | Run turbo over experiment A's clips | free |
| **The minimum target hours a cross-lingual transducer fine-tune needs** — no published ablation for any Zipformer recipe | 4 fine-tunes at 25/50/100/130 h × 14 epochs from the German checkpoint, on Turkish (the binding case) | ~20 ref GPU-h ≈ 1–2 d |
| **Whether the epoch-12–14 loss floor is a WER floor** — the entire 2.1× compute claim | `--epoch 14 --avg 9` vs `--epoch 29 --avg 9`, same dev set | <1 h |
| **Does diversity beat hours at chunk-32** — the corpus lane's central recommendation, with no measurement behind it | E-div (paired Dutch run) | ~0.8 GPU-d |
| **MS-02 sustained clocks under multi-day load** — no measurement exists for this card in any small chassis | M3 | 90 min |
| **`prepare.sh` wall time / the real lilcom ratio** | Stage 4 (dev/test only), timed, divide `.lca` bytes by frames × 320 | 5 min |
| **Whether prebuilt k2 CUDA wheels embed `sm_89`/`sm_75`** — `K2_BUILD_FOR_ALL_ARCHS` defaults OFF | M0 | 5 min |
| **`max_disambig`, hence the `tokens.txt` line count (501/502/503)** | `wc -l data/<lang>/lang_bpe_500/tokens.txt` after stage 9 | free |
| **Digit-bearing sentence fraction per language** — mislabeled audio is the worst training signal for a never-retracting strip | `awk -F'\t' 'NR>1{print $3}' validated.tsv \| grep -c '[0-9]'` | 1 min *(a lane measured ZERO in all four sentence pools, so this is expected to be a no-op)* |
| **CGN — the ~900 h spontaneous Dutch corpus** — `taalmaterialen.ivdnt.org` returned 403, terms UNREAD. Dutch is the language most improved by a yes | One email to Instituut voor de Nederlandse Taal: fee, commercial use, derived-model redistribution | 1 email |
| **A native-reader spontaneous-dictation reference set** — the gate the English pack actually passed, and the one this project cannot currently reproduce for any other language | ~20 min of dictation per language, transcribed, outsourced | ~1 d/language |

---

## 12. WHERE THE LANES DISAGREED, AND HOW I ADJUDICATED

| disagreement | resolution |
|---|---|
| *"17.6 GB VRAM against an 11 GB card"* (`train-recipe`) vs *"11 GB is not a problem, 6,462 MB"* (`train-hardware`) | **Same fact at two batch sizes.** The refuter fit both points to `F = 1,707 MB + 15.85 MB/s`, cross-checked by a third route. Ceilings ≈520–560 (11 GB) and ≈800–870 (16 GB) against a working value of 300. **No wall.** The recipe lane's headline should be reworded |
| **209** vs **623** h-audio/GPU-hour as the reference throughput | **623.** `train-finetune` §7 omitted `compute_fbank_librispeech.py --perturb-speed`, which **defaults to True** and triples cuts per epoch to 2,880 h; `train-recipe` cross-checks from the other direction (2,730 steps × 4,000 s ≈ 3,033 h). Its *"10–24 days from scratch"* is **~3× pessimistic** → 3.2–7.9 days. **The two lanes only agree after the fix** |
| Dutch first (`train-hardware`) vs Italian first (`train-pipeline`) vs de→nl (`train-finetune`) | **de→nl.** Dutch is simultaneously the cheapest, the one with the best available pretrain, the one a close pretrain helps most, and the natural diversity experiment. **Italian is the right *from-scratch* pilot** if the German checkpoint's licence does not clear — 364 h, 10.5 GB, no third-party dependency |
| German = **10.58** (qualification table) vs **11.5** at our cadence (`train-accuracy`) | **11.5.** The published number is a 640 ms decode (`--chunk-size 32`) while the pack is 320 ms. INFERRED via LibriSpeech's same-checkpoint +9 % penalty; **experiment X3 replaces the borrow with a measurement for 0.25 d.** German's ratio is therefore 0.89× not 0.81× against CV9 |
| *"Common Voice licence UNREADABLE — blocking"* (`train-pipeline` D1/K1) vs *"CC0-1.0 on all 402 MDC datasets"* (`train-data`) | **The data lane read it properly and the premise survives.** It paged MDC's REST API 12×100 (1,113 datasets, 402 Common Voice, every one CC0-1.0 / `isPaid: false`) and read the CV-Turkish dataset page's licence text. The pipeline lane's K1 becomes a **confirm**, not a discovery. The **checkpoint** licence is the live gate now |
| es **GREEN** on data (`train-data` §7) vs es **loses** (`train-refute-quality` §3) | **Both are right and they are answering different questions.** Spanish has the data (26,940 speakers, 1,679 Tier-A hours, 0.72× the German anchor's text, the lowest type/token ratio of the four). It loses on the **denominator** — whisper-small is 5.6 on FLEURS-es. *"Spanish is the language where a home-trained pack would look good and still lose, and it loses on Common-Voice-only"* |
| *"`duxx` 13.09 = 0.55× whisper-small, so 130 h Turkish might be enough"* (`train-data` §6.3) | **DEAD.** The refuter read that card's own corpus list: Common Voice 17 **+ the refused ISSAI youtube-dl corpus + a scripted daily-use corpus + pseudo-labelled speech + synthetic speech + WorldSpeech (CC-BY-NC-4.0) + FLEURS train**, with every number modified-beam-12 and dev-selected, and the card itself saying *"selection metrics, not sealed test results."* **The anchor does not transfer to a CV-only model, and the Turkish arm has zero supporting evidence once it is deleted** |
| *"Ultra = `ggml-large-v3-turbo-q5_0.bin`"* (`train-accuracy` §1) | **Corrected against the code.** That row is `retired = true, unsupported = true`. The turbo bar arrives via gated `npu-turbo` with onboarding steering. **The conclusion holds; the route and scope were wrong** |
| Spanish validated hours: **456** (`train-accuracy`, from the live stats API) vs **595.5** (`train-data`, `train-hardware`, `train-pipeline`, from Mozilla's own `cv-corpus-26.0` release JSON) | **595.5 for costing, 456 flagged for the predictions.** Every other language agrees within ~2 %; only Spanish disagrees, by 30 %. The release JSON is a dated, citable snapshot and was **byte-cross-validated against MDC's `sizeBytes`**, so I cost on it. But the accuracy lane's predictions were *computed* on 456, which is the pessimistic direction, so I left them and showed the recomputation inline (16.4 / 2.93× at 595.5). **Nobody reconciled the two sources and the discrepancy is unexplained — a live API reporting 30 % *fewer* hours than a three-month-old release snapshot is backwards.** One `wc -l` + duration sum over `validated.tsv` after the first download settles it, and that is a training prerequisite anyway |
| Compute refuter's *"WEAKENED"* vs quality refuter's *"REFUTED"* | **Not a conflict — they attacked different claims.** Compute feasibility survives robustly (and its refuter says so, having tried six ways and failed five). **Accuracy does not.** The synthesis verdict follows the quality refuter, because accuracy decides whether it ships and compute only decides when |

---

## 13. PROVENANCE OF THIS DOCUMENT

**I read, in full:** `train-recipe.md` (978 lines), `train-hardware.md` (801),
`train-data.md` (721), `train-finetune.md` (853), `train-accuracy.md` (674),
`train-pipeline.md` (869), `train-refute-compute.md` (456), `train-refute-quality.md` (475) — all
under
`C:/Users/bastr/AppData/Local/Temp/claude/C--Users-bastr-OneDrive-Desktop-whisper-Everywhere/f0fe6e9c-f5ba-497d-8286-004288122e7c/scratchpad/review/`.

**I also read on disk today:**
`app/src/main/java/com/whispereverywhere/transcription/stream/StreamingPackCatalog.kt` (the
`StreamingPack` field list, the EN row's `modelType = "zipformer2"` / `decodeChunkLen = 32` /
`encoderT = 45`, the derived `cadenceMs`/`padMs`/`totalBytes`, `markerText`), and
`docs/superpowers/research/2026-09-11-language-qualification-table.md` §3 (the Kroko cadence and
licence findings, the per-language picker order, the whisper-small CV9 denominators, and the three
questions the Kroko email needs).

**I did NOT re-fetch any external URL.** Every external fact above is a second-hand read of a lane's
first-hand read, and §9 item 11 names the two places where that matters most. Lane scripts kept for
re-derivation without a network call: `shape.py`, `shapecurve.py`, `oov.py`, `pool.py`,
`numwords.py`, `norm_probe.py`, plus the extracted primaries, all in the same scratchpad directory.

**Authority precedence used throughout:** for any fact about an **existing** model,
`docs/superpowers/research/2026-09-11-language-qualification-table.md` is the authority and the lanes
correct it only where they show their read (the German cadence correction and the `ultra`-retired
correction are the two corrections I accepted). For facts about **recipes**, a recipe file beats a
RESULTS.md beats a README. For facts about **cost**, a published training log beats an estimate, and
an estimate is labelled as one.
