# audio_ctx floor bench — recorded verdicts (3.6.0 Workstream G)

**Rule (executable: `WerMath.floorQualifies`, gate = 0.10):** a candidate floor QUALIFIES only if
EVERY **binding** `BENCH audioctx ... wer=` line for that floor, on EVERY benched tier, is <= 0.100
— the bench prints a per-floor `verdict=PASS|FAIL` line applying exactly this rule.
The production floor (`whisper_jni.cpp` `g_audio_ctx_floor` default, 768) changes ONLY if a
candidate qualifies on BOTH 190 MB tiers (pro AND multi), and then to the LOWEST qualifying
candidate (Task G4). If nothing qualifies, 768 stands and this record is the ship artifact.

**The `verdict=` token is authoritative over the printed numbers.** `wer=` and `maxWer=` are
rounded to three decimals for reading; the verdict is computed from the unrounded doubles. A
printed `0.100` can be 0.1004 (qualifies) or 0.1001 (does not). Never overrule, or "correct", a
`verdict=` token by eyeballing the rounded figures — copy the token as printed.

**`binding=false` lines are NOT evidence about that floor, and never enter the verdict.** The
encoder context is `max(samples/320 + 64, floor)`, so a floor only does anything when it is
strictly greater than that first term (1 s -> 114, 2 s -> 164, 3 s -> 214, 8 s -> 464 frames).
At the 8 s slice, floors 384 and 256 are below 464 and are therefore never applied — that
transcribe ran at its natural 464 frames, identically for both candidates, and a low WER there
says nothing about a low floor being safe. The bench excludes those lines from
`floorQualifies` and reports how many remain as `bindingSlices=N` on the verdict line. Record
`binding=false` rows in the log paste, but do not carry them into the table's `maxWer`.

**Two readings that need the owner, not the gate:**
- `verdict=FAIL` with `bindingSlices=0` means NO EVIDENCE was collected for that floor, not a
  measured regression. It cannot occur with the shipped slice/candidate lists; if it appears,
  the lists were edited — say so in Results rather than recording a FAIL.
- A `binding=false` line with a BAD wer (> 0.100) is worth reporting even though it is excluded.
  The binding flag is computed from the PRE-VAD sample count, which is an upper bound on the
  frames the native side actually counts, so a heavily VAD-trimmed slice could have hit the floor
  after all. jfk.wav is continuous speech, so this should not happen — flag it if it does.
- The 8 s `binding=false` rows at floors 384 and 256 are the ONLY measurement of what lowering
  the floor does to 8 s audio: that slice encodes at 768 frames today and at its natural 464
  under any floor <= 464. They are excluded from `floorQualifies` because they do not exercise
  the candidate VALUE — not because they are irrelevant. **Before recording `RESULT: PASS
  floor=384` or `floor=256`, read those two rows: if either `wer` is > 0.100, lowering the floor
  hurts 8 s tails — record `RESULT: FAIL`.** `floorQualifies` is `all { it <= gate }`, so
  excluding a slice can only make a floor MORE likely to qualify — this eyeball check closes the
  bench's one optimistic direction.
- Tier scope: the bench prints rows for EVERY installed tier, but the `RESULT:` line is decided
  on pro + multi only (the table's rows). Rows from any other installed tier (eco/base/retired)
  are informational — do not let them move the verdict either way.

**WERs are comparable only WITHIN a single run of this bench version.** The scoring tokenizer
changed during Workstream G (curly-apostrophe normalization, commit `6223591`), and every number
here is a candidate scored against a reference transcribed on the same device, same model file,
same run. Do not compare these figures against numbers from an earlier bench build, another
device, or a different tier — and if the bench is re-run, replace the whole Results block rather
than merging rows from two runs.

## How to run (owner device)

Build both APKs (never `:app:installDebug` / `:app:connectedDebugAndroidTest` — gradle install
tasks wipe app data and the downloaded models; `adb install -r` preserves them):

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest#bench_audio_ctx_floor_ab com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
    adb logcat -d -s WE-BENCH | findstr "BENCH audioctx"

(adb is not on PATH: use `& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.)

Bench each 190 MB tier by having it installed in-app (the test benches whatever is on disk).
Do NOT dictate or run batch jobs while the bench runs: the floor override is process-global, it is
read once at the start of each native transcribe, and the sweep is deliberately sequential — a
concurrent transcribe both corrupts the measurement and runs your own audio at a bench floor.

## Results

RESULT: PASS floor=512

**Definitive run 2026-08-20 17:14 local (Fold6, versionCode 77, both tiers, one run, 75.0 s,
`am instrument` OK). Backends are the PRODUCTION defaults** — the bench goes through
`WhisperNativeBackend.load`, so pro (.en) ran on **GPU** (allowlisted Adreno 750) and multi ran
on **CPU** (GPU-multilingual experiment toggle OFF, the shipped default). **512 qualifies on
BOTH tiers with maxWer 0.000 and 4/4 binding slices each; 384 and 256 fail on multi** (1 s
slice, wer=0.500 — the same cliff on CPU as on GPU, so the cliff is a property of the encoder
context, not the backend). Per the rule above, the default may change 768 → 512 (Task G4).

    BENCH audioctx tier=pro floor=768 slice=1s wallMs=960 wer=0.000 binding=true (reference)
    BENCH audioctx tier=pro floor=768 slice=2s wallMs=1479 wer=0.000 binding=true (reference)
    BENCH audioctx tier=pro floor=768 slice=3s wallMs=1500 wer=0.000 binding=true (reference)
    BENCH audioctx tier=pro floor=768 slice=8s wallMs=2498 wer=0.000 binding=true (reference)
    BENCH audioctx tier=pro floor=512 slice=1s wallMs=771 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=512 slice=2s wallMs=1077 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=512 slice=3s wallMs=1256 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=512 slice=8s wallMs=2379 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=512 verdict=PASS maxWer=0.000 gate=0.10 bindingSlices=4
    BENCH audioctx tier=pro floor=384 slice=1s wallMs=1694 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=384 slice=2s wallMs=1109 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=384 slice=3s wallMs=1238 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=384 slice=8s wallMs=2302 wer=0.000 binding=false
    BENCH audioctx tier=pro floor=384 verdict=PASS maxWer=0.000 gate=0.10 bindingSlices=3
    BENCH audioctx tier=pro floor=256 slice=1s wallMs=1601 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=256 slice=2s wallMs=1050 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=256 slice=3s wallMs=1165 wer=0.000 binding=true
    BENCH audioctx tier=pro floor=256 slice=8s wallMs=2382 wer=0.000 binding=false
    BENCH audioctx tier=pro floor=256 verdict=PASS maxWer=0.000 gate=0.10 bindingSlices=3
    BENCH audioctx tier=multi floor=768 slice=1s wallMs=3674 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=2s wallMs=3445 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=3s wallMs=3557 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=8s wallMs=3884 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=512 slice=1s wallMs=2323 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=2s wallMs=2257 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=3s wallMs=2376 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=8s wallMs=2542 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 verdict=PASS maxWer=0.000 gate=0.10 bindingSlices=4
    BENCH audioctx tier=multi floor=384 slice=1s wallMs=1822 wer=0.500 binding=true
    BENCH audioctx tier=multi floor=384 slice=2s wallMs=1655 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=384 slice=3s wallMs=1792 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=384 slice=8s wallMs=2151 wer=0.000 binding=false
    BENCH audioctx tier=multi floor=384 verdict=FAIL maxWer=0.500 gate=0.10 bindingSlices=3
    BENCH audioctx tier=multi floor=256 slice=1s wallMs=1316 wer=0.500 binding=true
    BENCH audioctx tier=multi floor=256 slice=2s wallMs=1165 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=256 slice=3s wallMs=1267 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=256 slice=8s wallMs=2381 wer=0.000 binding=false
    BENCH audioctx tier=multi floor=256 verdict=FAIL maxWer=0.500 gate=0.10 bindingSlices=3

Eyeball checks per the rules above: every `binding=false` 8 s row (pro and multi, 384 and 256)
scored wer=0.000 — no hidden 8 s-tail harm; no `binding=false` row scored badly.

| tier  | floor | maxWer | bindingSlices | verdict (from the bench's own verdict line) |
|-------|-------|--------|---------------|---------------------------------------------|
| pro   | 512   | 0.000  | 4             | PASS                                        |
| pro   | 384   | 0.000  | 3             | PASS                                        |
| pro   | 256   | 0.000  | 3             | PASS                                        |
| multi | 512   | 0.000  | 4             | PASS                                        |
| multi | 384   | 0.500  | 3             | FAIL                                        |
| multi | 256   | 0.500  | 3             | FAIL                                        |

**Timing (informational; decision-relevant for 3.7 Gate 0):** on the production backends the
floor IS a real lever. Multi-CPU fixed per-commit cost F: **768 → ~3.5 s, 512 → ~2.3 s**
(-35 % on every short commit and stop-tail). Pro-GPU: 768 → ~0.96 s, 512 → ~0.77 s. No decoder
instability anywhere in this run — all 32 transcribes clean.

**Superseded same-day GPU-backend runs (14:47 and 15:22 local), kept as a caution:** two earlier
sweeps unknowingly ran multi on the **GPU** (the experiment toggle was ON and the canary latch
armed the GPU inside the bench's production-seam load). Their WER verdicts MATCHED this run
(512 PASS / 384 FAIL / 256 FAIL — the cliff is backend-independent), but their timing was
poisoned: multi-GPU is ~9× slower than CPU (see the gpu-ab record) and every lowered floor
triggered 27–45 s anti-repetition temperature cascades on short slices — a GPU-decode
pathology that does NOT occur on CPU. Any future re-run of this bench must state the toggle
position and check WE-DIAG for which backend each tier's load actually took.

When filled, replace `RESULT: PENDING` with exactly one of:
- `RESULT: PASS floor=<512|384|256>` — the lowest floor with verdict=PASS on EVERY benched tier
- `RESULT: FAIL` — no candidate passed on every tier; 768 stands

## Decision

DECISION: PENDING — RESULT: PASS floor=512 satisfies Task G4's gate; the change is RECOMMENDED
and ready to execute as a post-3.6.0 commit (owner go/no-go): `whisper_jni.cpp`
`g_audio_ctx_floor` default 768 → 512, `WhisperBenchTest.PRODUCTION_FLOOR` 768 → 512 in the
SAME commit (per its KDoc), and prune `FLOOR_CANDIDATES` to below-512 values (384, 256) before
any post-change re-run. Measured payoff at 512: multi-CPU F 3.5 → 2.3 s on every short commit
and stop-tail; pro-GPU F 0.96 → 0.77 s. Accuracy: maxWer 0.000 on both tiers, 4/4 binding
slices each.
