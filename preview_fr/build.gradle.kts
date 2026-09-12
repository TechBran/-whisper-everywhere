// The streaming previewer's French model pack (4.5.0 Task 2; owner ruling 2026-09-12, "let's set
// up all 6 languages"): the four `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14` files —
// encoder/decoder/joiner int8 + tokens.txt, 128,227,451 B in total (128 MB) — delivered by Play on
// demand, exactly like `preview_en`.
//
// THE BIGGEST PACK IN THE CATALOGUE, and by a distance: 128 MB against English's 73, because
// upstream exports the encoder at 126,655,903 B. That is why on-demand is not a nicety here — an
// install-time pack would push 128 MB onto every device that will never pick French. It is also a
// `zipformer` V1 export, which changes nothing about DELIVERY (four files, one directory) and
// everything about loading: see StreamingPackCatalog.FR.
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
// byte counts and sha256s `StreamingPackCatalog.FR` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_fr")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
