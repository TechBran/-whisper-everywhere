package com.whispereverywhere.npu

import android.content.Context

/**
 * THE LITERT RUNTIME THE MEDIATEK TIER PACKAGES (P2-6; design
 * `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.6) — the Kotlin side's one
 * home for what the APK carries for `liblitertasr.so`, and the one call that stages the half of
 * it that must be a real file.
 *
 * Two libraries, two routes into the APK, and the reason each takes its own:
 *
 *  - **`libLiteRt.so`** (LiteRT 2.1.1, out of `com.google.ai.edge.litert:litert:2.1.1`) ships in
 *    `lib/arm64-v8a/` like every native library this app loads by name: the engine dlopens it by
 *    path from `nativeLibraryDir`, then by SONAME — which under this app's
 *    `extractNativeLibs="false"` packaging is the form that resolves, straight out of the APK
 *    (`litert_asr.cpp`, `loadRuntimeLocked`). `app/build.gradle.kts`'s `extractLiteRtRuntime`
 *    takes it out of the AAR — the AAR itself is never a dependency, so its manifest's MediaTek
 *    declarations are never merged — and asserts its length and digest.
 *  - **`libLiteRtDispatch_MediaTek.so`** (the v2.1.1 MediaTek dispatch) must be a FILE in the
 *    directory LiteRT is told to scan, so it ships as an ASSET and is staged into
 *    `filesDir/litert_dispatch/` ([stagedDispatchDir]). An asset is not stripped: AGP's strip
 *    turns the release zip's member into a different file of the same length, so a `lib/` copy
 *    would never hash to [DISPATCH_SHA256] — which is the zip member's, the design's pin, and
 *    what the stage verifies at arm time.
 *
 * `LiteRtPackagingTest` holds the build script's literals — the AAR coordinate, the release zip's
 * tag, the dispatch's length and digest — equal to these, the two coordinates to one version
 * ([VERSION]), and the merged manifest's MediaTek set to exactly the one adapter.
 *
 * NOTHING CALLS [stagedDispatchDir] YET (P2-6): the LiteRT engine's prepare stage is P2-7's, with
 * the selector's vendor switch. This object exists so the packaging and its identity are in place
 * and pinned before any code path loads them.
 */
object LiteRtRuntime {

    /**
     * The LiteRT release every half of the tier is built against: `libLiteRt.so` (the Maven
     * AAR), the MediaTek dispatch (the GitHub release zip — the last whose NPU zip ships one), and
     * the C headers `liblitertasr.so` compiles against (`third_party/litert-2.1.1`). The dispatch
     * only loads against the `libLiteRt.so` of its own release, so one version is the rule.
     */
    const val VERSION: String = "2.1.1"

    /** The MediaTek dispatch's file name — the asset, and the one file in the staged directory. */
    const val DISPATCH_ASSET: String = "libLiteRtDispatch_MediaTek.so"

    /** Its exact length: the v2.1.1 release zip's member (`mediatek_runtime/…/arm64-v8a/`). */
    const val DISPATCH_BYTES: Long = 409_728L

    /**
     * Its sha256 AS THE ZIP MEMBER — the design's pin. (The copy AGP's strip would package into
     * `lib/` is `f47bd9c0…`, the same length and different bytes, which is exactly why the
     * dispatch is an asset.)
     */
    const val DISPATCH_SHA256: String =
        "9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706"

    /**
     * Stages the dispatch into `filesDir/litert_dispatch/` — [NpuApuDriverCheck.dispatchDir], the
     * directory's one home, which the driver probe and the engine's environment take too — as
     * the only file there, with its working files in `filesDir/litert_dispatch.staged/`
     * ([NpuAssetStage.stageIntoDir]). Answers the directory's path, or null when it could not be
     * staged (the refusal is logged by the stage). BLOCKING file I/O: never on Main.
     */
    fun stagedDispatchDir(context: Context): String? =
        NpuAssetStage.stagedDirWithMarker(
            context,
            NpuApuDriverCheck.dispatchDir(context.filesDir),
            DISPATCH_ASSET,
            DISPATCH_BYTES,
            DISPATCH_SHA256,
        )
}
