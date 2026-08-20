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

RESULT: PENDING

**Partial run 2026-08-20 (Fold6, versionCode 77): MULTI ONLY.** Pro was not installed on the
device, so no pro rows exist and the RESULT line cannot be filled — the rule above requires a
qualifying floor on BOTH 190 MB tiers. Multi's half is complete and unambiguous: **512 PASS
(4/4 binding slices, maxWer 0.000), 384 FAIL, 256 FAIL** (both on the 1 s slice, wer=0.500).
Install pro in-app and re-run to finish the record — and per the comparability rule, that re-run
replaces this whole block (both tiers measured in one run).

Multi logcat paste (run of 14:47–14:54 local, `am instrument` OK, 469.8 s):

    BENCH audioctx tier=multi floor=768 slice=1s wallMs=2995 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=2s wallMs=3003 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=3s wallMs=27439 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=768 slice=8s wallMs=31838 wer=0.000 binding=true (reference)
    BENCH audioctx tier=multi floor=512 slice=1s wallMs=30725 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=2s wallMs=3084 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=3s wallMs=18303 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 slice=8s wallMs=40806 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=512 verdict=PASS maxWer=0.000 gate=0.10 bindingSlices=4
    BENCH audioctx tier=multi floor=384 slice=1s wallMs=38276 wer=0.500 binding=true
    BENCH audioctx tier=multi floor=384 slice=2s wallMs=5333 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=384 slice=3s wallMs=20766 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=384 slice=8s wallMs=32563 wer=0.000 binding=false
    BENCH audioctx tier=multi floor=384 verdict=FAIL maxWer=0.500 gate=0.10 bindingSlices=3
    BENCH audioctx tier=multi floor=256 slice=1s wallMs=45753 wer=0.500 binding=true
    BENCH audioctx tier=multi floor=256 slice=2s wallMs=37514 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=256 slice=3s wallMs=60405 wer=0.000 binding=true
    BENCH audioctx tier=multi floor=256 slice=8s wallMs=31793 wer=0.000 binding=false
    BENCH audioctx tier=multi floor=256 verdict=FAIL maxWer=0.500 gate=0.10 bindingSlices=3

Eyeball checks per the rules above: the 8 s `binding=false` rows at 384 and 256 both scored
wer=0.000 (no hidden 8 s-tail harm), and no `binding=false` row scored badly.

| tier  | floor | maxWer | bindingSlices | verdict (from the bench's own verdict line) |
|-------|-------|--------|---------------|---------------------------------------------|
| pro   | 512   |        |               | not run — tier not installed                |
| pro   | 384   |        |               | not run — tier not installed                |
| pro   | 256   |        |               | not run — tier not installed                |
| multi | 512   | 0.000  | 4             | PASS                                        |
| multi | 384   | 0.500  | 3             | FAIL                                        |
| multi | 256   | 0.500  | 3             | FAIL                                        |

**Timing observation (informational — the verdict above is WER-only, but this is
decision-relevant for 3.7's Gate 0):** lowering the floor bought NO wall-time on this device and
frequently cost an order of magnitude. The clean (no-fallback) measurements put the fixed
per-commit cost F at ~3.0 s regardless of floor (768/1s = 2995 ms, 768/2s = 3003 ms,
512/2s = 3084 ms), while every lowered floor triggered whisper.cpp's anti-repetition temperature
cascade on the 1 s slice (512 → 30.7 s, 384 → 38.3 s, 256 → 45.8 s vs 768 → 3.0 s clean). A
smaller encoder context makes short-fragment decoding UNSTABLE even where final accuracy
survives (512). The 3 s/8 s storms at every floor including the 768 reference are a bench
artifact of mid-word-truncated tiled audio, not floor evidence. Read: the floor is not a lever
for making short commits cheaper on multi; F reduction has to come from somewhere else (GPU —
see the gpu-ab record).

When filled, replace `RESULT: PENDING` with exactly one of:
- `RESULT: PASS floor=<512|384|256>` — the lowest floor with verdict=PASS on EVERY benched tier
- `RESULT: FAIL` — no candidate passed on every tier; 768 stands

## Decision

DECISION: PENDING (filled by Task G4)
