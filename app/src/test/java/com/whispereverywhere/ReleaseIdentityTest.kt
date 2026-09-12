package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 4.2.0 build still carrying versionCode 80 is rejected only AFTER the upload,
 * and a 4.2.0 build still NAMED "4.1.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * **versionCode 82 — the plain successor to 81.** 81 was consumed by the first internal-track
 * upload (the fleet-onboarding build the owner installed and validated on 2026-08-30), so it is
 * spent: Play will refuse a second upload at the same code. 4.3 takes the next integer, and its
 * NAME moves with it — the trunk now carries the one-tier-per-device lineup, and a build named for
 * the release before the one it contains is the exact silent mismatch this test exists to catch.
 *
 * **versionCode 83 — the plain successor to 82.** 82 went to PRODUCTION on 2026-08-30 as 4.3.0,
 * so it is spent twice over: Play refuses a second upload at the same code, and every installed
 * phone already carries it. 4.3.1 is a patch (three field reports, no new surface), so the NAME
 * moves by one in the last place and the code by one integer; 83 > 82 is what lets the track
 * install replace the production build on the owner's own phone.
 *
 * **versionCode 84 — the plain successor to 83.** 83 went to the INTERNAL TRACK on 2026-09-03
 * (the owner installed it and confirmed the 350 ms hangover "is doing a better job") and was never
 * promoted, so it is spent on the track exactly as 81 was: Play refuses a second upload at the
 * same code. 84 supersedes it there carrying the flatline cut. The NAME stays 4.3.1: a name is
 * spent by a RELEASE, and 4.3.1 has not been released — 83 and 84 are two candidates for the same
 * one. 84 > 83 is what lets the track install replace 83 on the owner's phone. Every bump re-arms
 * GpuPolicy's canary latches (below); still by design, still inert with the GPU toggle off.
 *
 * **versionCode 85 — the plain successor to 84.** 84 went to PRODUCTION on 2026-09-03 (the flatline
 * cut; E8 passed on the owner's phone), so it is spent twice over, as 82 was. 85 carries the
 * backpressure governor, the detect-margin line and the Auto copy. The NAME stays 4.3.1 for one
 * more candidate: nothing in 85 changes what a user sees except the onboarding sentence, and the
 * governor is the guard the 4.3.1 cadence ruling promised — the same release, made safe. If the
 * silence-hallucination fix that 85's margin line measures for lands next, THAT is 4.3.2.
 *
 * **versionCode 86 = 4.3.2 — the plain successor to 85, and the NAME moves.** 85 went to the
 * internal track on 2026-09-04 (spent there, as 81 and 83 were). 86 carries the silence fix —
 * speech evidence gates the encode, the stock-phrase blocklist — which changes what a user SEES
 * (no more "Thank you for watching" out of a quiet room), so the last place moves by one, exactly
 * as the previous paragraph promised. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 87 = 4.3.3 — the plain successor to 86.** 86 went to PRODUCTION on 2026-09-04 (the
 * silence fix). 87 makes the accessibility service optional: a user whose device forbids it (the
 * Galaxy XR's policy) or whose install Android restricts (Restricted Settings) can finish onboarding
 * and dictate to the clipboard instead of being locked out. What a user sees changes, so the name
 * moves. Every bump still re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 88 = 4.3.4 — the plain successor to 87.** 87 was built and uploaded as 4.3.3. 88 adds
 * a live Gemini provider (a settings surface and a BYOK mode a user can see), fixes the cloud-language
 * leak, and carries the sherpa-onnx runtime bump — a new surface, so the last place moves. The
 * streaming local tier, when it lands, is 4.4.0 (the owner's naming since 4.3.0). Every bump still
 * re-arms GpuPolicy's canary latches (below).
 *
 * **versionCode 89 — the plain successor to 88.** 88 went to the INTERNAL TRACK on 2026-09-10 and
 * the owner confirmed Gemini Live working in real time on it; it is spent on the track exactly as
 * 81, 83 and 85 were. 89 supersedes it there with three things 88 lacks: live by default (owner
 * ruling), the corrected turbo card, and the voice-archive gate fix. The NAME stays 4.3.4 — a name
 * is spent by a release, and 88 was not promoted. If 88 IS promoted before 89 uploads, 89 becomes
 * 4.3.5 and this paragraph is wrong: change both.
 *
 * **versionCode 90 = 4.4.0 — the MINOR moves, and this is the first time since 4.3.0.** 89 went to
 * the internal track as 4.3.4 and is spent there. 90 is not a patch: the app gains a second local
 * engine (the streaming previewer), two more asset packs (four in one bundle), and a rebuilt
 * start-up path. "4.4" has meant streaming in this project's own notes since 4.3.0 shipped, so the
 * name it was promised is the name it takes. Every bump still re-arms GpuPolicy's canary latches
 * (below) — and on this one the previewer's load-time canary is a SECOND, unrelated canary: it
 * guards the Zipformer against the FEAT_SME defect and is not persisted at all.
 *
 * **versionCode 91 = 4.4.1 — a PATCH, and the name says what it is.** 90 went to the internal
 * track as 4.4.0 and is spent there. 91 adds no engine, no pack and no payload: it makes the
 * previewer 90 already shipped reachable, by fetching a language's pack when that language is
 * picked and by telling the truth on the surfaces where 90 either said nothing or offered English
 * to someone who could never use it. One new input drives it — the selected language — and three
 * behaviours that were nearly true in 90 are literal in 91: Auto gets no live words, a "no" is
 * remembered per language, and nothing is loaded for a session that cannot arm it. Every bump
 * still re-arms GpuPolicy's canary latches (below); the previewer's own load-time canary is a
 * SECOND, unrelated canary and is not persisted at all.
 *
 * **versionCode 92 = 4.5.0 — the MINOR moves, because the previewer stops being English.** 91 went
 * to the internal track as 4.4.1 and is spent there. 92 adds six languages as on-demand packs and,
 * more to the point, the seam that makes a language a catalogue row instead of an architecture
 * change: per-pack model family, per-pack derived pad, per-pack canary and disabled latch, a strip
 * built from tokens, and one per-language observable both screens read. The previewer's canary is
 * now PER PACK, so a failed canary in one language can no longer refuse a load in another — the
 * process-wide latch this KDoc described at 90 is gone. Every bump still re-arms GpuPolicy's own
 * latches (below), unchanged.
 *
 * **What 82 buys, stated precisely.** It buys an upgrade over the 81 build now sitting on the
 * track AND on the owner's phone: 82 > 81, so the next track install replaces it — which is a real
 * change from 4.2's position, where u4 (uninstall before the track install) was MANDATORY because
 * the local build was 81-signed and Play offers no update path at equal versionCode. A local
 * bundletool `install-apks` set is still not a Play-managed install, so u4 remains mandatory
 * whenever a LOCAL 82 build has been installed by hand. If this KDoc and
 * docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md ever disagree, the sheet is the
 * instrument and the sheet is right.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 81 -> 82 clears
 * every recorded GPU verdict on every device — the same side effect 78 -> 80 and 80 -> 81 had, and it
 * recurs on every bump by design. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters
 * (docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md).
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_4_5_0_at_version_code_92() {
        assertEquals(
            "versionName must be 4.5.0 for this release (app/build.gradle.kts defaultConfig)",
            "4.5.0",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 92 for this release (app/build.gradle.kts defaultConfig)",
            92,
            BuildConfig.VERSION_CODE,
        )
    }
}
