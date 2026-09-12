pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhisperEverywhere"
include(":app")
// The two on-demand NPU asset-pack modules (4.2 F4). Their payload is a BUILD artifact —
// tools/build_asset_packs.py build assembles the SoC #group_ variants from the measured vendor
// zips — so each committed module tree carries only its build file, the EMPTY default variant
// (model/.gitkeep) and the .gitignore that keeps the payload dirs out.
include(":npu_turbo", ":npu_small")
// The streaming previewer's on-demand pack (4.4.0, the 2026-09-10 amendment) — its own statement
// so the NPU pair's line above stays exactly what it was: a rewritten include list is how a pack
// silently stops shipping. Same payload discipline (tools/build_asset_packs.py preview places the
// four files; the committed tree carries the build file, the wall and the .gitkeep anchor), but
// NOT device-targeted: one untargeted variant, every device.
include(":preview_en")
// The read-aloud voice's on-demand pack (4.4.0, the 2026-09-10 amendment, Task 2b) — its own
// statement for the same reason: it carries kokoro-multi-lang-v1_0.tar.bz2 AS-IS, so
// TtsModelManager.verifyExtractInstall runs unchanged on the pack's copy. Same payload discipline
// (tools/build_asset_packs.py tts places the archive; the committed tree carries the build file,
// the wall and the .gitkeep anchor) and NOT device-targeted: one untargeted variant, every device.
include(":tts_kokoro")
// The six 4.5.0 LANGUAGE packs (Task 2; owner ruling 2026-09-12, "let's set up all 6 languages").
// Identical terms to :preview_en — four raw files each, on-demand, NOT device-targeted, payload
// placed by tools/build_asset_packs.py preview and gated by :app's verifyPreviewPack — so the
// rationale is stated once, above, and not six times here.
//
// SIX STATEMENTS, not one line with six names, for the reason the three blocks above are separate
// statements: a rewritten include list is how a pack silently stops shipping, and one statement per
// pack is what makes "this language is in the build" a claim a single line can be checked against.
// PreviewPackLayoutTest reads them that way, one `include(":<packName>")` per catalogue row.
include(":preview_fr")
include(":preview_de")
include(":preview_ru")
include(":preview_id")
include(":preview_ko")
include(":preview_zh")
