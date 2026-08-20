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

(paste every `BENCH audioctx` logcat line here — including the `binding=false` ones and the
`floor=768 ... (reference)` arm — then fill the table from the `verdict=` lines)

| tier  | floor | maxWer | bindingSlices | verdict (from the bench's own verdict line) |
|-------|-------|--------|---------------|---------------------------------------------|
| pro   | 512   |        |               |                                             |
| pro   | 384   |        |               |                                             |
| pro   | 256   |        |               |                                             |
| multi | 512   |        |               |                                             |
| multi | 384   |        |               |                                             |
| multi | 256   |        |               |                                             |

When filled, replace `RESULT: PENDING` with exactly one of:
- `RESULT: PASS floor=<512|384|256>` — the lowest floor with verdict=PASS on EVERY benched tier
- `RESULT: FAIL` — no candidate passed on every tier; 768 stands

## Decision

DECISION: PENDING (filled by Task G4)
