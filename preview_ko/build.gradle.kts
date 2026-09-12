// The streaming previewer's Korean model pack (4.5.0 Task 2; owner ruling 2026-09-12, "let's set
// up all 6 languages"): the four `kangkyu/icefall-asr-ko-streaming-zipformer-72m` files —
// encoder/decoder/joiner int8 + tokens.txt, 72,969,700 B in total (73 MB) — delivered by Play on
// demand, exactly like `preview_en`.
//
// A 2,460-piece vocabulary, which is where the bytes went: the decoder is 1,544,210 B and the
// joiner 1,270,777 B against English's 1,307,236 and 259,335, on an encoder a megabyte SMALLER
// than English's. Same delivery — and the upstream repo also publishes chunk-32 and chunk-64
// exports whose decoder and joiner are BYTE-IDENTICAL to these three, so the digest in the
// catalogue is the only thing that tells the exports apart. That is why the placement script
// verifies digests and not names.
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
// byte counts and sha256s `StreamingPackCatalog.KO` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_ko")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
