// The streaming previewer's Russian model pack (4.5.0 Task 2; owner ruling 2026-09-12, "let's set
// up all 6 languages"): the four
// `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` files —
// encoder/decoder/joiner int8 + tokens.txt, 28,572,945 B in total (29 MB) — delivered by Play on
// demand, exactly like `preview_en`.
//
// THE SMALLEST PACK IN THE CATALOGUE: 29 MB, 21 MB under the next one up, because it is a `small`
// export whose six encoder stacks are two layers each. Its decoder is the one in the catalogue
// upstream ships UNQUANTIZED (`decoder.onnx`, 2,093,080 B), which is a fact about the bytes and
// not about this module: the pack carries what upstream published.
//
// EVERY RULE THIS MODULE FOLLOWS IS STATED ONCE, IN preview_en/build.gradle.kts, AND NOT REPEATED
// HERE: why the pack is on-demand rather than install-time, why it is NOT device-targeted (the
// same bytes on every device, so ONE untargeted directory and `app/device_targeting_config.xml`
// untouched), why the asset directory is named after the PACK (4.2 F8's entry-clash rule), and why
// it is a standard asset pack rather than an AI pack. Seven modules restating one rationale is
// seven places for it to drift; the file that owns it is the first one written.
//
// What IS this module's own: the pack name below, and the payload it carries. The payload is a
// BUILD artifact — `python tools/build_asset_packs.py preview` places the four files against the
// byte counts and sha256s `StreamingPackCatalog.RU` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_ru")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
