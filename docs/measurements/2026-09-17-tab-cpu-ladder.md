# Tab S10+ CPU ladder: finalize wall time per commit, 2026-09-17

**Device:** Samsung Galaxy Tab S10+ (SM-X828U, MediaTek Dimensity 9300+, 12 GB). **Build:** versionCode 95 (4.6.0) plus the native finalize timing line, commit `b54fc1b`, sideloaded with `adb install -r` (the tablet never had the Play copy). **Source audio:** the same YouTube talk ("How to talk to the worst parts of yourself", TEDxKC) captured as DEVICE AUDIO through the app's own screen-share consent (entire screen), so every rung heard the same speaker. **Configuration:** `threads=4`; the English streaming previewer ARMED (2 threads, +169 MB, running concurrently); English session language. **Who ran it:** Claude Fable 5.1, driving the tablet remotely over wireless adb at Brandon Slacum's instruction ("you can do all you like on there") while he was away from the device; the logcat capture is `adb logcat -v time -s WE-DIAG`. **Metric:** `wallMs` = wall time of one `whisper_full` call for one VAD-cut chunk, compared against that rung's commit floor (`CommitCadencePolicy.minCommitIntervalMs`: 6,000 ms for the small rungs, 8,000 ms for every other CPU rung). The queue grows iff wallMs exceeds the floor; below ~8.96 s of audio the `audio_ctx` floor (512) binds, so cost per commit is constant and RTF-against-audio is the wrong metric. At versionCode 95 `small-q8` actually paced on the 8,000 ms row (`CommitCadencePolicy` placed every 4.6 instrument on the LARGE row via `else`, so its worst commit was 0.25 of the floor it ran at); 4.7.0 moves it to the 6,000 ms row on this evidence, which is the floor the table's `small-q8` duty is computed against. `medium-q8` likewise paced on the 8,000 ms row at 95 and through 4.8.x, and moves to the 6,000 ms row in 4.9.0 on the owner's ruling of 2026-09-17 after testing medium Q8 on this tablet ("six seconds for medium, since I can handle it"): the table's medium max/floor (0.31) was computed at 8,000 — at 6,000 it is 0.42 (2,508/6,000), and F/floor + m is ~0.26 against the 0.70 rule. `ultra-q8` stays at 8,000 by his same-day ruling ("keep it the way it is"); its 7,930 ms worst commit would not support 7,000.

## Summary

| rung | file | chunks | wallMs median | mean | max | ctx=512 median | commit floor | max/floor | outcome |
|---|---|---|---|---|---|---|---|---|---|
| `ultra-q8` Ultra (large-v3-turbo, Q8_0) | 874 MB | 24 | 4,849 | 5,194 | 7,930 | 4,659 | 8,000 | 0.99 | KEPT_UP, no margin |
| `medium-q8` Multilingual (medium, Q8_0) | 823 MB | 38 | 1,341 | 1,470 | 2,508 | 1,140 | 8,000 | 0.31 | KEPT_UP |
| `multi` Multilingual (small, Q5_1) - the shipped default | 190 MB | 20 | 2,618 | 2,711 | 3,802 | 2,522 | 6,000 | 0.63 | KEPT_UP |
| `small-q8` Multilingual (small, Q8_0) | 264 MB | 12 | 1,217 | 1,324 | 1,993 | 1,116 | 6,000 | 0.33 | KEPT_UP |
| `medium-q5` Multilingual (medium, Q5_0) | 539 MB | 20 | 9,294 | 9,202 | 11,782 | 7,575 | 8,000 | 1.47 | NEVER_CAUGHT_UP (queue grew; chunks lengthened to 9-14 s) |

**Readings.** Q8_0 is on ggml's ARM i8mm repack path and Q5_0/Q5_1 are not; both quantisation pairs show it: small Q8 is 2.2x faster than small Q5_1 (2,618 vs 1,217 ms), and medium Q8 is 6.9x faster than medium Q5_0 (9,294 vs 1,341 ms; 6.6x on the ctx=512 chunks alone, 7,575 vs 1,140). Medium Q8 and small Q8 land within 10% of each other on this tablet (1,341 vs 1,217 ms). Ultra Q8 keeps up on this flagship with no margin (0.99). Medium Q5_0 cannot keep up: once wallMs exceeded the floor the endpointer handed over longer chunks and each cost more, which is the runaway regime. The Ultra Q5_0 and large-v3 Q5_0 rungs were not measured; both sit on the slow path and the owner retired every Q5 rung on 2026-09-17 ("Q5 is definitely off the table").

**Confounds stated.** One device, one talk, one session per rung, minutes long; the previewer was armed throughout; YouTube ran in a picture-in-picture window during the medium Q5_0 run (hardware video decode, a small CPU share that does not account for a 6-8x gap); the first chunk of a fresh app process carries ~2 s of warm-up (the 06:51 and 08:20 stray chunks below were excluded for that reason and because they came from a microphone session, not device audio).


## `ultra-q8` - Ultra (large-v3-turbo, Q8_0)

```
06:54:55 pid=13007 finalize: wallMs=3780 audio_ctx=512 threads=4 audioMs=1217 segments=1
06:55:04 pid=13007 finalize: wallMs=4009 audio_ctx=512 threads=4 audioMs=7630 segments=4
06:55:12 pid=13007 finalize: wallMs=3857 audio_ctx=512 threads=4 audioMs=7120 segments=2
06:55:31 pid=13007 finalize: wallMs=7538 audio_ctx=748 threads=4 audioMs=13692 segments=5
06:55:42 pid=13007 finalize: wallMs=7100 audio_ctx=696 threads=4 audioMs=12640 segments=4
06:55:48 pid=13007 finalize: wallMs=4654 audio_ctx=512 threads=4 audioMs=6380 segments=2
06:56:00 pid=13007 finalize: wallMs=4924 audio_ctx=512 threads=4 audioMs=8910 segments=3
06:56:09 pid=13007 finalize: wallMs=4478 audio_ctx=512 threads=4 audioMs=7860 segments=1
06:56:28 pid=13007 finalize: wallMs=7930 audio_ctx=756 threads=4 audioMs=13844 segments=4
06:56:38 pid=13007 finalize: wallMs=6222 audio_ctx=607 threads=4 audioMs=10860 segments=2
06:56:45 pid=13007 finalize: wallMs=4948 audio_ctx=512 threads=4 audioMs=7230 segments=2
06:56:53 pid=13007 finalize: wallMs=4772 audio_ctx=512 threads=4 audioMs=7690 segments=2
06:57:04 pid=13007 finalize: wallMs=5357 audio_ctx=557 threads=4 audioMs=9860 segments=2
06:57:12 pid=13007 finalize: wallMs=4692 audio_ctx=512 threads=4 audioMs=7590 segments=4
06:57:22 pid=13007 finalize: wallMs=4632 audio_ctx=512 threads=4 audioMs=4370 segments=3
06:57:33 pid=13007 finalize: wallMs=5067 audio_ctx=530 threads=4 audioMs=9330 segments=4
06:57:42 pid=13007 finalize: wallMs=4814 audio_ctx=512 threads=4 audioMs=8230 segments=3
06:57:50 pid=13007 finalize: wallMs=4659 audio_ctx=512 threads=4 audioMs=7550 segments=1
06:57:59 pid=13007 finalize: wallMs=5040 audio_ctx=512 threads=4 audioMs=8310 segments=4
06:58:12 pid=13007 finalize: wallMs=6223 audio_ctx=627 threads=4 audioMs=11260 segments=3
06:58:21 pid=13007 finalize: wallMs=4659 audio_ctx=512 threads=4 audioMs=8180 segments=3
06:58:29 pid=13007 finalize: wallMs=4884 audio_ctx=512 threads=4 audioMs=7730 segments=2
06:58:42 pid=13007 finalize: wallMs=6108 audio_ctx=577 threads=4 audioMs=10270 segments=7
06:58:46 pid=13007 finalize: wallMs=4317 audio_ctx=512 threads=4 audioMs=2190 segments=1
```

## `medium-q8` - Multilingual (medium, Q8_0)

```
07:04:52 pid=13007 finalize: wallMs=901 audio_ctx=512 threads=4 audioMs=1559 segments=1
07:05:03 pid=13007 finalize: wallMs=1600 audio_ctx=633 threads=4 audioMs=11380 segments=4
07:05:12 pid=13007 finalize: wallMs=1129 audio_ctx=515 threads=4 audioMs=9020 segments=3
07:05:28 pid=13007 finalize: wallMs=2266 audio_ctx=816 threads=4 audioMs=15058 segments=3
07:05:36 pid=13007 finalize: wallMs=1031 audio_ctx=512 threads=4 audioMs=8190 segments=2
07:05:46 pid=13007 finalize: wallMs=1073 audio_ctx=512 threads=4 audioMs=8670 segments=2
07:05:56 pid=13007 finalize: wallMs=1237 audio_ctx=541 threads=4 audioMs=9550 segments=3
07:06:11 pid=13007 finalize: wallMs=1716 audio_ctx=705 threads=4 audioMs=12829 segments=3
07:06:21 pid=13007 finalize: wallMs=1456 audio_ctx=590 threads=4 audioMs=10520 segments=3
07:06:29 pid=13007 finalize: wallMs=1099 audio_ctx=512 threads=4 audioMs=8080 segments=2
07:06:38 pid=13007 finalize: wallMs=1152 audio_ctx=512 threads=4 audioMs=8940 segments=2
07:06:50 pid=13007 finalize: wallMs=1563 audio_ctx=614 threads=4 audioMs=11000 segments=3
07:06:59 pid=13007 finalize: wallMs=1066 audio_ctx=512 threads=4 audioMs=8680 segments=2
07:07:07 pid=13007 finalize: wallMs=1098 audio_ctx=512 threads=4 audioMs=8170 segments=3
07:07:18 pid=13007 finalize: wallMs=1322 audio_ctx=562 threads=4 audioMs=9970 segments=3
07:07:29 pid=13007 finalize: wallMs=1360 audio_ctx=568 threads=4 audioMs=10080 segments=2
07:07:43 pid=13007 finalize: wallMs=1989 audio_ctx=718 threads=4 audioMs=13090 segments=4
07:07:51 pid=13007 finalize: wallMs=1129 audio_ctx=512 threads=4 audioMs=8280 segments=1
07:08:00 pid=13007 finalize: wallMs=1174 audio_ctx=512 threads=4 audioMs=7270 segments=2
07:08:11 pid=13007 finalize: wallMs=1427 audio_ctx=526 threads=4 audioMs=9240 segments=3
07:08:19 pid=13007 finalize: wallMs=1159 audio_ctx=512 threads=4 audioMs=7440 segments=2
07:08:32 pid=13007 finalize: wallMs=1928 audio_ctx=658 threads=4 audioMs=11880 segments=3
07:08:39 pid=13007 finalize: wallMs=1309 audio_ctx=512 threads=4 audioMs=7610 segments=2
07:08:52 pid=13007 finalize: wallMs=1767 audio_ctx=643 threads=4 audioMs=11590 segments=3
07:09:00 pid=13007 finalize: wallMs=1168 audio_ctx=512 threads=4 audioMs=8450 segments=1
07:09:10 pid=13007 finalize: wallMs=1189 audio_ctx=512 threads=4 audioMs=8710 segments=1
07:09:24 pid=13007 finalize: wallMs=2108 audio_ctx=684 threads=4 audioMs=12410 segments=3
07:09:33 pid=13007 finalize: wallMs=1464 audio_ctx=546 threads=4 audioMs=9650 segments=3
07:09:42 pid=13007 finalize: wallMs=1286 audio_ctx=512 threads=4 audioMs=8720 segments=2
07:09:54 pid=13007 finalize: wallMs=1665 audio_ctx=602 threads=4 audioMs=10770 segments=2
07:10:05 pid=13007 finalize: wallMs=1762 audio_ctx=603 threads=4 audioMs=10790 segments=4
07:10:18 pid=13007 finalize: wallMs=1933 audio_ctx=654 threads=4 audioMs=11800 segments=4
07:10:34 pid=13007 finalize: wallMs=2348 audio_ctx=750 threads=4 audioMs=13737 segments=3
07:10:43 pid=13007 finalize: wallMs=1723 audio_ctx=613 threads=4 audioMs=10980 segments=4
07:10:53 pid=13007 finalize: wallMs=1449 audio_ctx=561 threads=4 audioMs=9950 segments=2
07:11:09 pid=13007 finalize: wallMs=2508 audio_ctx=818 threads=4 audioMs=15098 segments=3
07:11:17 pid=13007 finalize: wallMs=1280 audio_ctx=512 threads=4 audioMs=8510 segments=2
07:11:20 pid=13007 finalize: wallMs=1042 audio_ctx=512 threads=4 audioMs=2928 segments=1
```

## `multi` - Multilingual (small, Q5_1) - the shipped default

```
07:11:57 pid=13007 finalize: wallMs=2102 audio_ctx=512 threads=4 audioMs=2808 segments=1
07:12:06 pid=13007 finalize: wallMs=2393 audio_ctx=512 threads=4 audioMs=8510 segments=2
07:12:17 pid=13007 finalize: wallMs=3134 audio_ctx=591 threads=4 audioMs=10550 segments=5
07:12:24 pid=13007 finalize: wallMs=2524 audio_ctx=512 threads=4 audioMs=7380 segments=5
07:12:38 pid=13007 finalize: wallMs=3802 audio_ctx=706 threads=4 audioMs=12850 segments=4
07:12:43 pid=13007 finalize: wallMs=2299 audio_ctx=512 threads=4 audioMs=5870 segments=1
07:12:50 pid=13007 finalize: wallMs=2521 audio_ctx=512 threads=4 audioMs=6840 segments=3
07:13:02 pid=13007 finalize: wallMs=3281 audio_ctx=608 threads=4 audioMs=10880 segments=4
07:13:12 pid=13007 finalize: wallMs=3020 audio_ctx=568 threads=4 audioMs=10090 segments=2
07:13:19 pid=13007 finalize: wallMs=2497 audio_ctx=512 threads=4 audioMs=7200 segments=3
07:13:28 pid=13007 finalize: wallMs=2597 audio_ctx=512 threads=4 audioMs=8000 segments=2
07:13:37 pid=13007 finalize: wallMs=2723 audio_ctx=512 threads=4 audioMs=8510 segments=2
07:13:44 pid=13007 finalize: wallMs=2496 audio_ctx=512 threads=4 audioMs=7470 segments=1
07:13:50 pid=13007 finalize: wallMs=2506 audio_ctx=512 threads=4 audioMs=5990 segments=1
07:14:01 pid=13007 finalize: wallMs=2965 audio_ctx=541 threads=4 audioMs=9550 segments=4
07:14:07 pid=13007 finalize: wallMs=2609 audio_ctx=512 threads=4 audioMs=5730 segments=2
07:14:13 pid=13007 finalize: wallMs=2628 audio_ctx=512 threads=4 audioMs=6370 segments=2
07:14:21 pid=13007 finalize: wallMs=2748 audio_ctx=512 threads=4 audioMs=6820 segments=2
07:14:27 pid=13007 finalize: wallMs=2651 audio_ctx=512 threads=4 audioMs=6040 segments=1
07:14:36 pid=13007 finalize: wallMs=2724 audio_ctx=522 threads=4 audioMs=9170 segments=2
```

## `small-q8` - Multilingual (small, Q8_0)

```
07:15:42 pid=13007 finalize: wallMs=901 audio_ctx=512 threads=4 audioMs=3730 segments=1
07:15:52 pid=13007 finalize: wallMs=1196 audio_ctx=529 threads=4 audioMs=9310 segments=2
07:16:08 pid=13007 finalize: wallMs=1993 audio_ctx=718 threads=4 audioMs=13098 segments=6
07:16:16 pid=13007 finalize: wallMs=1218 audio_ctx=550 threads=4 audioMs=9720 segments=3
07:16:25 pid=13007 finalize: wallMs=1223 audio_ctx=512 threads=4 audioMs=8380 segments=2
07:16:35 pid=13007 finalize: wallMs=1216 audio_ctx=542 threads=4 audioMs=9560 segments=4
07:16:49 pid=13007 finalize: wallMs=1728 audio_ctx=682 threads=4 audioMs=12360 segments=4
07:16:58 pid=13007 finalize: wallMs=1116 audio_ctx=512 threads=4 audioMs=8920 segments=3
07:17:08 pid=13007 finalize: wallMs=1149 audio_ctx=512 threads=4 audioMs=8890 segments=2
07:17:23 pid=13007 finalize: wallMs=1809 audio_ctx=709 threads=4 audioMs=12900 segments=4
07:17:34 pid=13007 finalize: wallMs=1303 audio_ctx=587 threads=4 audioMs=10470 segments=2
07:17:42 pid=13007 finalize: wallMs=1041 audio_ctx=512 threads=4 audioMs=7560 segments=2
```

## `medium-q5` - Multilingual (medium, Q5_0)

```
08:30:01 pid=19127 finalize: wallMs=5519 audio_ctx=512 threads=4 audioMs=2989 segments=1
08:30:11 pid=19127 finalize: wallMs=6098 audio_ctx=512 threads=4 audioMs=8810 segments=3
08:30:27 pid=19127 finalize: wallMs=9045 audio_ctx=686 threads=4 audioMs=12450 segments=3
08:30:44 pid=19127 finalize: wallMs=10972 audio_ctx=761 threads=4 audioMs=13954 segments=7
08:30:52 pid=19127 finalize: wallMs=7824 audio_ctx=549 threads=4 audioMs=9710 segments=2
08:31:07 pid=19127 finalize: wallMs=11653 audio_ctx=727 threads=4 audioMs=13270 segments=4
08:31:20 pid=19127 finalize: wallMs=11027 audio_ctx=701 threads=4 audioMs=12750 segments=5
08:31:27 pid=19127 finalize: wallMs=7575 audio_ctx=512 threads=4 audioMs=8750 segments=2
08:31:39 pid=19127 finalize: wallMs=9799 audio_ctx=621 threads=4 audioMs=11150 segments=4
08:31:46 pid=19127 finalize: wallMs=7447 audio_ctx=512 threads=4 audioMs=7970 segments=2
08:31:57 pid=19127 finalize: wallMs=9600 audio_ctx=578 threads=4 audioMs=10290 segments=3
08:32:09 pid=19127 finalize: wallMs=9947 audio_ctx=591 threads=4 audioMs=10540 segments=4
08:32:18 pid=19127 finalize: wallMs=8920 audio_ctx=533 threads=4 audioMs=9390 segments=2
08:32:36 pid=19127 finalize: wallMs=11782 audio_ctx=719 threads=4 audioMs=13116 segments=3
08:32:45 pid=19127 finalize: wallMs=9543 audio_ctx=600 threads=4 audioMs=10720 segments=5
08:32:57 pid=19127 finalize: wallMs=11657 audio_ctx=673 threads=4 audioMs=12180 segments=2
08:33:06 pid=19127 finalize: wallMs=8264 audio_ctx=512 threads=4 audioMs=8060 segments=2
08:33:16 pid=19127 finalize: wallMs=10478 audio_ctx=608 threads=4 audioMs=10890 segments=4
08:33:25 pid=19127 finalize: wallMs=8593 audio_ctx=512 threads=4 audioMs=8720 segments=3
08:33:33 pid=19127 finalize: wallMs=8310 audio_ctx=512 threads=4 audioMs=8190 segments=2
```

## Excluded stray chunks

```
06:51:19 pid=13007 finalize: wallMs=7224 audio_ctx=512 threads=4 audioMs=1431 segments=1   (microphone session / process warm-up; not part of any rung's sample)
06:51:23 pid=13007 finalize: wallMs=3721 audio_ctx=512 threads=4 audioMs=1967 segments=2   (microphone session / process warm-up; not part of any rung's sample)
08:20:14 pid=19127 finalize: wallMs=2931 audio_ctx=512 threads=4 audioMs=1100 segments=1   (microphone session / process warm-up; not part of any rung's sample)
08:20:31 pid=19127 finalize: wallMs=863 audio_ctx=512 threads=4 audioMs=1460 segments=1   (microphone session / process warm-up; not part of any rung's sample)
```

## Evening runs the same day - turbo Q8 in the owner's own use, and it fell behind

The owner dictated on `ultra-q8` three more times on the same tablet on the evening of 2026-09-17 (his own YouTube-over-device-audio sessions; captured with `adb logcat -v time -s WE-DIAG ggml whisper_jni`; the `loading model from ggml-large-v3-turbo-q8_0.bin` line at 19:29:28 identifies the model for every line below). Same build (versionCode 97), threads=4, previewer armed. Battery at 55-58 %, thermal status 0 throughout; the third run was plugged into the charger about a minute in. He reported the two power states "didn't feel any different", and the numbers agree: all three runs were in the falling-behind regime.

| run | power | commits | wallMs median | mean | worst | ctx=512 median | over the 8,000 ms floor | audio median | audio max |
|---|---|---|---|---|---|---|---|---|---|
| 19:57-19:59 | on battery (56%) | 13 | 7,225 | 7,804 | 13,583 | 5,085 | 6 of 13 | 10.9 s | 15.1 s |
| 20:00-20:02 | on battery | 9 | 7,784 | 7,802 | 12,580 | 6,065 | 4 of 9 | 10.4 s | 14.9 s |
| 20:04-20:07 | charger connected at 20:05:48, one minute into the run | 10 | 10,776 | 9,118 | 13,794 | 5,270 | 6 of 10 | 15.0 s | 15.1 s |

**Reading.** Against the morning ladder run (median 4,849, worst 7,930, 0 of 24 over the floor, chunks 8-14 s), the evening commits were 1.5-2x slower, half of them exceeded the 8,000 ms pacing floor, and the chunks pinned at the endpointer's 15 s cap - the signature of a queue that has fallen behind and is feeding itself (longer chunks cost more, so each commit lands later). The short (ctx=512) chunks alone ran 10-30 % slower than in the morning, so the tablet was genuinely slower this evening and turbo had no headroom to absorb it. The charger did not recover the third run because it was already saturated. What the owner experiences as fast is the streaming previewer (0.4 s behind the voice); the committed text in these runs arrived 8-14 s per chunk behind. **Consequence for the record:** the morning's 0.99 was turbo's BEST case on this tablet, not its typical one. The owner's ruling to ship turbo as an offered tier stands ("we definitely wanna keep that one"; "this will be the best for long form video that you want accurate"); the card says "slower than the other two" and warns that the typed text can fall behind, and this section is why.


### 19:57:21-19:59:50, on battery (56%)

```
19:57:21 finalize: wallMs=3922 audio_ctx=512 threads=4 audioMs=1100 segments=1
19:57:43 finalize: wallMs=10737 audio_ctx=816 threads=4 audioMs=15058 segments=8
19:57:58 finalize: wallMs=10817 audio_ctx=814 threads=4 audioMs=15008 segments=5
19:58:04 finalize: wallMs=6684 audio_ctx=610 threads=4 audioMs=10930 segments=2
19:58:18 finalize: wallMs=8176 audio_ctx=688 threads=4 audioMs=12480 segments=6
19:58:24 finalize: wallMs=5037 audio_ctx=512 threads=4 audioMs=8650 segments=1
19:58:34 finalize: wallMs=5678 audio_ctx=538 threads=4 audioMs=9480 segments=5
19:58:49 finalize: wallMs=8120 audio_ctx=689 threads=4 audioMs=12510 segments=4
19:58:55 finalize: wallMs=5237 audio_ctx=515 threads=4 audioMs=9038 segments=3
19:59:09 finalize: wallMs=7225 audio_ctx=636 threads=4 audioMs=11450 segments=3
19:59:16 finalize: wallMs=5134 audio_ctx=512 threads=4 audioMs=8410 segments=4
19:59:37 finalize: wallMs=11103 audio_ctx=814 threads=4 audioMs=15008 segments=7
19:59:50 finalize: wallMs=13583 audio_ctx=512 threads=4 audioMs=8688 segments=2
```

### 20:00:56-20:02:29, on battery

```
20:00:56 finalize: wallMs=3687 audio_ctx=512 threads=4 audioMs=1496 segments=2
20:01:14 finalize: wallMs=8190 audio_ctx=700 threads=4 audioMs=12720 segments=4
20:01:31 finalize: wallMs=10252 audio_ctx=802 threads=4 audioMs=14778 segments=3
20:01:37 finalize: wallMs=5793 audio_ctx=536 threads=4 audioMs=9450 segments=2
20:01:58 finalize: wallMs=12580 audio_ctx=810 threads=4 audioMs=14938 segments=4
20:02:07 finalize: wallMs=9256 audio_ctx=700 threads=4 audioMs=12730 segments=2
20:02:16 finalize: wallMs=7784 audio_ctx=583 threads=4 audioMs=10386 segments=2
20:02:23 finalize: wallMs=6619 audio_ctx=512 threads=4 audioMs=8670 segments=2
20:02:29 finalize: wallMs=6065 audio_ctx=512 threads=4 audioMs=2506 segments=1
```

### 20:04:54-20:07:12, charger connected at 20:05:48, one minute into the run

```
20:04:54 finalize: wallMs=3659 audio_ctx=512 threads=4 audioMs=1100 segments=1
20:05:16 finalize: wallMs=10805 audio_ctx=814 threads=4 audioMs=15008 segments=4
20:05:31 finalize: wallMs=10747 audio_ctx=814 threads=4 audioMs=15008 segments=3
20:05:47 finalize: wallMs=11323 audio_ctx=818 threads=4 audioMs=15098 segments=3
20:05:53 finalize: wallMs=6241 audio_ctx=512 threads=4 audioMs=2368 segments=4
20:06:11 finalize: wallMs=4326 audio_ctx=512 threads=4 audioMs=1270 segments=1
20:06:33 finalize: wallMs=11298 audio_ctx=814 threads=4 audioMs=15008 segments=10
20:06:50 finalize: wallMs=12779 audio_ctx=814 threads=4 audioMs=15008 segments=6
20:07:06 finalize: wallMs=13794 audio_ctx=819 threads=4 audioMs=15108 segments=9
20:07:12 finalize: wallMs=6214 audio_ctx=512 threads=4 audioMs=7150 segments=3
```
