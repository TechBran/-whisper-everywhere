package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LITERT RUNTIME'S PACKAGING (P2-6, the MediaTek APU tier; design §2.6), pinned the way
 * `NpuSkelPackagingTest` pins the skels': no JVM test can run a Gradle task, but the mechanism is a
 * handful of spellings in `app/build.gradle.kts` that must agree, and any one drifting is a tier
 * that dies on the tablet with nothing naming why.
 *
 *  - **`libLiteRt.so` in `lib/arm64-v8a/`**: taken out of the litert 2.1.1 AAR by
 *    `extractLiteRtRuntime` through the `litertRuntime` configuration (non-transitive, and the AAR
 *    NEVER an implementation dependency — its manifest would merge `libneuron_sys_util.mtk.so`
 *    back, the 5 s cold-arm wait of sheet §4b), into a GENERATED jniLibs dir registered with
 *    `jniLibs.srcDir` and ordered before `merge*JniLibFolders`. Pinned by sha256 and length.
 *  - **The dispatch as an ASSET**: `extractLiteRtDispatch` takes the v2.1.1 MediaTek dispatch out
 *    of LiteRT's release zip into generated assets, held to the zip member's digest — the one
 *    `LiteRtRuntime` stages against — because a `lib/` copy is stripped into other bytes.
 *  - **The two coordinates agree**: the Maven coordinate and the release zip's tag are one LiteRT
 *    release, `LiteRtRuntime.VERSION`, and so are the headers `liblitertasr.so` compiles against.
 *  - **The device gate's own pins**: the probe that measured the tier staged the same library and
 *    the same dispatch, and its scripts' literals are held equal to these.
 *
 * Every file read here is in the test task's `sourcePinnedInputs`.
 */
class LiteRtPackagingTest {

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val t = line.trimStart()
            !(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)
        }

    private fun lines(vararg text: String) = text.joinToString("\n")

    private val gradle: String by lazy { read("build.gradle.kts") }

    /** One top-level Gradle block: anchor to the first column-0 closing brace. */
    private fun gradleBlock(anchor: String): String {
        val start = gradle.indexOf(anchor)
        assertTrue("anchor \"$anchor\" is missing from app/build.gradle.kts", start >= 0)
        return gradle.substring(start).substringBefore("\n}\n")
    }

    private val runtimeTask: String by lazy { gradleBlock("val extractLiteRtRuntime = tasks.register(\"extractLiteRtRuntime\")") }
    private val dispatchTask: String by lazy { gradleBlock("val extractLiteRtDispatch = tasks.register(\"extractLiteRtDispatch\")") }

    /** `409728` -> `"409_728L"` — the Kotlin literal spelling the gradle file uses. */
    private fun kotlinLongLiteral(value: Long): String =
        value.toString().reversed().chunked(3).joinToString("_").reversed() + "L"

    private val libSha256 = "6ddc1b3df38f3f0e039558c023f3bd9ec2f7bec55b67cfd6be4131130481a5d5"

    // ------------------------------------------------------------------ libLiteRt.so in lib/

    @Test
    fun theLitertAarIsResolvedThroughItsOwnConfigurationAndNeverDependedOn() {
        assertEquals(
            "the configuration is declared once, non-transitive — the AAR's graph (lifecycle 2.10, " +
                "guava, ai-delivery) never rides in",
            1,
            count(
                gradle,
                lines(
                    "val litertRuntime: Configuration by configurations.creating {",
                    "    isTransitive = false",
                ),
            ),
        )
        assertEquals(
            "the AAR's ONE coordinate is the litertRuntime line",
            1, liveLineCount(gradle, "litertRuntime(\"com.google.ai.edge.litert:litert:2.1.1\")"),
        )
        assertEquals(
            "…and the litert group appears on no other live line — an implementation/api/runtimeOnly " +
                "dependency on it would MERGE its manifest, re-adding libneuron_sys_util.mtk.so (a 5 s " +
                "binder wait on every cold arm, sheet §4b), .9 and mgvi",
            1, liveLineCount(gradle, "com.google.ai.edge.litert"),
        )
    }

    @Test
    fun theExtractionTakesLibLiteRtAloneAtItsPinnedLengthAndDigest() {
        assertEquals("the entry is read by its exact path", 1, liveLineCount(runtimeTask, "zip.getEntry(\"jni/arm64-v8a/libLiteRt.so\")"))
        assertEquals("…into the generated jniLibs' arm64-v8a", 1, liveLineCount(runtimeTask, "val lib = File(outDir, \"arm64-v8a/libLiteRt.so\")"))
        assertEquals("the AAR is the one published at 2.1.1", 1, liveLineCount(runtimeTask, "check(aar.length() == 7_569_479L)"))
        assertEquals("the library's exact length", 1, liveLineCount(runtimeTask, "check(lib.length() == 5_104_832L)"))
        assertEquals("and its sha256", 1, liveLineCount(runtimeTask, "check(digest == \"$libSha256\")"))
        assertEquals(
            "the generated dir is cleared first, so it holds exactly the one library — no OpenCL " +
                "accelerator, no compiler plugin (design §2.6)",
            1, liveLineCount(runtimeTask, "outDir.deleteRecursively()"),
        )
        for (absent in listOf("libLiteRtOpenClAccelerator", "libLiteRtCompilerPlugin", "libLiteRtClGlAccelerator")) {
            assertEquals("no live line of the build names $absent", 0, liveLineCount(gradle, absent))
        }
    }

    @Test
    fun theGeneratedJniLibsDirIsRegisteredAndOrderedBeforeTheJniLibMerge() {
        assertEquals(
            "the dir is declared once, in the BUILD directory",
            1, liveLineCount(gradle, "val litertJniLibDir = layout.buildDirectory.dir(\"generated/litertRuntime/jniLibs\")"),
        )
        assertEquals(
            "registered as a main jniLibs srcDir — packaged into lib/arm64-v8a/ with the app's own",
            1, liveLineCount(gradle, "getByName(\"main\") { jniLibs.srcDir(litertJniLibDir) }"),
        )
        assertEquals(
            "ordered against merge*JniLibFolders, the task that CONSUMES the source — not merely " +
                "preBuild, the order lesson extractQnnSkel records",
            1,
            count(
                gradle,
                lines(
                    "tasks.matching { it.name.startsWith(\"merge\") && it.name.endsWith(\"JniLibFolders\") }",
                    "    .configureEach { dependsOn(extractLiteRtRuntime) }",
                ),
            ),
        )
        assertEquals("and preBuild too", 1, liveLineCount(gradle, "tasks.named(\"preBuild\") { dependsOn(extractLiteRtRuntime) }"))
        assertEquals("the task's input is the configuration itself", 1, liveLineCount(runtimeTask, "inputs.files(litertRuntime)"))
    }

    @Test
    fun theProductPackagesTheLibraryTheDeviceGateMeasured() {
        val stage = read("tools/mtk-apu/stage_litertasr_into_probe.py")
        // (Corrected at P2-7, the P2b review's small 3: this message said "byte for byte". AGP
        // strips native libraries on the way into lib/, in the product AND in the probe, so the
        // pinned digest is the EXTRACTED file's, never the packaged one's.)
        assertEquals(
            "P1b's device gate staged libLiteRt.so under the same pin — the pin covers the file " +
                "EXTRACTED from the 2.1.1 AAR, which the product and the probe both package; AGP " +
                "strips it into lib/ in both, so what ships is the measured file through the same " +
                "strip, not the pinned bytes as such",
            1, count(stage, "LITERT_SO_SHA256 = \"$libSha256\""),
        )
        assertEquals(1, count(stage, "LITERT_SO_BYTES = 5104832"))
    }

    // ------------------------------------------------------------------ the dispatch as an asset

    @Test
    fun theDispatchShipsAsAnAssetPinnedToTheZipMembersDigest() {
        assertEquals(
            "the release zip is fetched by its exact URL — v2.1.1, the last whose NPU zip ships the " +
                "MediaTek pair",
            1,
            liveLineCount(
                gradle,
                "\"https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries_jit.zip\"",
            ),
        )
        assertEquals("held to its exact length", 1, liveLineCount(dispatchTask, "check(zip.length() == 2_847_687L)"))
        assertEquals(
            "and its sha256",
            1, liveLineCount(dispatchTask, "check(zipDigest == \"4d6433eceb0e9c97388e5d10af9c71a1f97cf93f4a0f21acc492b97a342d45c3\")"),
        )
        assertEquals(
            "the member is read by its exact path",
            1, liveLineCount(dispatchTask, "z.getEntry(\"mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_MediaTek.so\")"),
        )
        assertEquals(
            "into the generated assets under the asset name the stage opens",
            1, liveLineCount(dispatchTask, "val dispatch = File(outDir, \"${LiteRtRuntime.DISPATCH_ASSET}\")"),
        )
        assertEquals(
            "held to LiteRtRuntime.DISPATCH_BYTES — the length the stage verifies at arm time",
            1, liveLineCount(dispatchTask, "check(dispatch.length() == ${kotlinLongLiteral(LiteRtRuntime.DISPATCH_BYTES)})"),
        )
        assertEquals(
            "and to LiteRtRuntime.DISPATCH_SHA256, the ZIP MEMBER's digest",
            1, liveLineCount(dispatchTask, "check(digest == \"${LiteRtRuntime.DISPATCH_SHA256}\")"),
        )
        assertEquals(
            "the stripped copy's digest (f47bd9c0…) appears on no live line — an APK lib/ copy of the " +
                "dispatch would be that file, which the stage would refuse",
            0, liveLineCount(gradle, "f47bd9c02a6a5830e4c78c67236494b96d7ae20f53fe09f8a6f15cd38dc28b5e"),
        )
        assertEquals(
            "the generated assets dir is declared once, in the BUILD directory",
            1, liveLineCount(gradle, "val litertDispatchAssetDir = layout.buildDirectory.dir(\"generated/litertDispatch/assets\")"),
        )
        assertEquals(
            "registered as a main assets srcDir",
            1, liveLineCount(gradle, "getByName(\"main\") { assets.srcDir(litertDispatchAssetDir) }"),
        )
        assertEquals(
            "ordered before merge*Assets, the task that consumes it",
            1,
            count(
                gradle,
                lines(
                    "tasks.matching { it.name.startsWith(\"merge\") && it.name.endsWith(\"Assets\") }",
                    "    .configureEach { dependsOn(extractLiteRtDispatch) }",
                ),
            ),
        )
        assertEquals("and preBuild too", 1, liveLineCount(gradle, "tasks.named(\"preBuild\") { dependsOn(extractLiteRtDispatch) }"))
        assertEquals(
            "the dispatch is NOT a jniLibs source anywhere — lib/ would strip it",
            0, liveLineCount(gradle, "jniLibs.srcDir(litertDispatchAssetDir)"),
        )
    }

    /**
     * THE P2b REVIEW'S SMALL 2 — the release zip is cached OUTSIDE `build/`. In `build/` every build
     * after a `clean` needed GitHub, and `--offline` could not help (the fetch is this task's own, not
     * a dependency Gradle caches). Under the user's home it survives `clean`, is shared by every
     * worktree, and an offline build passes whenever the cache holds the pinned bytes; the length and
     * sha256 pins above apply to the cached copy on every run, a copy that fails them is deleted by
     * the check that refuses it, a download is renamed in only once complete, and an offline build
     * with no cached copy says where to put one.
     */
    @Test
    fun theReleaseZipIsCachedOutsideBuildAndAnOfflineBuildUsesIt() {
        assertEquals(
            "the zip's one home is under the user's home, beside the machine's other build caches",
            1,
            liveLineCount(
                gradle,
                "val litertDispatchZip = File(System.getProperty(\"user.home\"), \".androidbuild/litert-cache/litert_npu_runtime_libraries_jit-2.1.1.zip\")",
            ),
        )
        assertEquals(
            "…never the build directory, and never declared as a build output (clean must not reach it)",
            listOf(0, 0),
            listOf(
                liveLineCount(gradle, "layout.buildDirectory.file(\"litertDispatch/"),
                liveLineCount(dispatchTask, "outputs.file(litertDispatchZip)"),
            ),
        )
        assertEquals(
            "offline with no cached copy is a named refusal — the network is never tried",
            listOf(1, 1),
            listOf(
                liveLineCount(dispatchTask, "val offline = gradle.startParameter.isOffline"),
                liveLineCount(dispatchTask, "check(!offline) {"),
            ),
        )
        val fetch = dispatchTask.indexOf("uri(litertDispatchZipUrl).toURL().openStream()")
        val guard = dispatchTask.indexOf("check(!offline) {")
        assertTrue("the offline guard ($guard) comes before the fetch ($fetch)", guard in 0 until fetch)
        assertEquals(
            "a download lands in a .part and is renamed in only once complete",
            listOf(1, 1),
            listOf(
                liveLineCount(dispatchTask, "val part = File(zip.parentFile, zip.name + \".part\")"),
                liveLineCount(dispatchTask, "check(part.renameTo(zip)) {"),
            ),
        )
        assertEquals(
            "and a cached copy that fails either pin is removed by the check that refuses it (plus " +
                "the one delete that makes room for the rename)",
            3,
            liveLineCount(dispatchTask, "zip.delete()"),
        )
    }

    @Test
    fun theProductShipsTheDispatchTheDeviceGateStaged() {
        val fetch = read("tools/probes/litertlm-probe/fetch_mediatek_runtime.py")
        assertEquals(
            "the probe fetched the same release zip",
            1, count(fetch, "URL = \"https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries_jit.zip\""),
        )
        assertEquals("…under the same zip digest", 1, count(fetch, "ZIP_SHA256 = \"4d6433eceb0e9c97388e5d10af9c71a1f97cf93f4a0f21acc492b97a342d45c3\""))
        assertEquals(
            "…and the same member digest the stage verifies",
            1, count(fetch, "\"${LiteRtRuntime.DISPATCH_SHA256}\""),
        )
    }

    // ------------------------------------------------------------------ the two coordinates agree

    @Test
    fun theMavenCoordinateTheReleaseZipAndTheHeadersAreOneLiteRtRelease() {
        val coordinate = Regex("""litertRuntime\("com\.google\.ai\.edge\.litert:litert:([0-9.]+)"\)""")
            .findAll(gradle).map { it.groupValues[1] }.toList()
        val tag = Regex("""releases/download/v([0-9.]+)/litert_npu_runtime_libraries_jit\.zip""")
            .findAll(gradle).map { it.groupValues[1] }.toSet()
        assertEquals("one coordinate", 1, coordinate.size)
        assertEquals(
            "THE TWO COORDINATES AGREE: libLiteRt.so's AAR and the dispatch's release zip are one " +
                "LiteRT release — the v2.1.1 dispatch loads only against its own release's runtime",
            setOf(coordinate.single()), tag,
        )
        assertEquals("…and it is LiteRtRuntime.VERSION", LiteRtRuntime.VERSION, coordinate.single())
        assertEquals(
            "…which is the release of the C headers liblitertasr.so compiles against",
            1, count(read("src/main/cpp/CMakeLists.txt"), "third_party/litert-${LiteRtRuntime.VERSION})"),
        )
        assertEquals("…and of the zip the build caches", 1, liveLineCount(gradle, "litert_npu_runtime_libraries_jit-${LiteRtRuntime.VERSION}.zip"))
    }

    // ------------------------------------------------------------------ the manifest (design §2.3)

    /** The MediaTek set: the one the product declares, then the three it must never. */
    private val adapter = "libneuronusdk_adapter.mtk.so"
    private val neverDeclared = listOf(
        "libneuron_sys_util.mtk.so", "libneuronusdk_adapter.9.mtk.so", "libneuron_adapter_mgvi.so",
    )

    @Test
    fun theSourceManifestDeclaresTheAdapterAndNothingElseFromTheMediatekSet() {
        val manifest = read("src/main/AndroidManifest.xml")
        val live = manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val appOpen = live.indexOf("<application")
        val appClose = live.indexOf("</application>")
        val elements = Regex("""<uses-native-library\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL).findAll(live).toList()
        val named = { lib: String ->
            elements.filter { Regex("""android:name\s*=\s*"${Regex.escape(lib)}"""").containsMatchIn(it.value) }
        }
        val declaration = named(adapter)
        assertEquals("the adapter is declared exactly once", 1, declaration.size)
        assertTrue(
            "…inside <application> — a top-level uses-native-library is silently ignored",
            declaration.single().range.first in appOpen until appClose,
        )
        assertTrue(
            "…and required=\"false\": this APK installs on every device, MediaTek or not",
            Regex("""android:required\s*=\s*"false"""").containsMatchIn(declaration.single().value),
        )
        for (lib in neverDeclared) {
            assertEquals("$lib is declared nowhere in the source manifest", 0, named(lib).size)
            assertEquals("…and not even named in its text, comments included", 0, count(manifest, lib))
        }
    }

    @Test
    fun theMergedManifestIsHeldToThatSetByATransformOfEveryVariant() {
        val task = gradleBlock("abstract class VerifyMediatekNativeLibraries : DefaultTask() {")
        val wiring = gradleBlock("androidComponents {")
        assertEquals(
            "the adapter is the declared one",
            1, liveLineCount(wiring, "declared.set(\"$adapter\")"),
        )
        assertEquals(
            "…and the three the litert AAR's manifest would add are the refused ones, exactly",
            1,
            count(
                wiring,
                lines(
                    "                listOf(",
                    "                    \"${neverDeclared[0]}\",",
                    "                    \"${neverDeclared[1]}\",",
                    "                    \"${neverDeclared[2]}\",",
                    "                )",
                ),
            ),
        )
        assertEquals(
            "a TRANSFORM of the MERGED manifest — the build output, which no APK or bundle can be " +
                "packaged without — registered for every variant",
            1, liveLineCount(wiring, ".toTransform(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)"),
        )
        assertEquals("…in onVariants", 1, liveLineCount(wiring, "onVariants { variant ->"))
        assertEquals(
            "comments are stripped before the scan: a commented-out element is not a declaration",
            1, liveLineCount(task, ".replace(Regex(\"<!--.*?-->\", RegexOption.DOT_MATCHES_ALL), \"\")"),
        )
        assertEquals(
            "the adapter must be declared exactly once",
            1, liveLineCount(task, "check(names.count { it == declared.get() } == 1) {"),
        )
        assertEquals("the refused ones must be absent", 1, liveLineCount(task, "check(present.isEmpty()) {"))
        assertEquals(
            "and the manifest is handed on byte for byte — the pin refuses, it never rewrites",
            1, liveLineCount(task, "checkedManifest.get().asFile.writeBytes(bytes)"),
        )
        assertEquals(
            "nothing in the build strips a declaration instead — there is nothing to strip, since " +
                "the product never merges the AAR's manifest",
            0, liveLineCount(gradle, "stripAarMediatekDeclarations"),
        )
    }

    @Test
    fun theFilesThisClassReadsAreInputsOfTheTestTask() {
        for (path in listOf(
            "\"build.gradle.kts\",",
            "rootProject.file(\"tools/mtk-apu/stage_litertasr_into_probe.py\"),",
            "rootProject.file(\"tools/probes/litertlm-probe/fetch_mediatek_runtime.py\"),",
            "\"src/main/cpp/CMakeLists.txt\",",
            "\"src/main/AndroidManifest.xml\",",
        )) {
            assertEquals("sourcePinnedInputs lists $path", 1, liveLineCount(gradle, path))
        }
    }
}
