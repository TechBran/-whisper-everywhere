// The streaming previewer's Indonesian model pack (4.5.0 Task 2; owner ruling 2026-09-12, "let's
// set up all 6 languages"): the four `spacewave/sherpa-onnx-streaming-zipformer2-id` files —
// encoder/decoder/joiner int8 + tokens.txt, 70,908,694 B in total (71 MB) — delivered by Play on
// demand, exactly like `preview_en`.
//
// A `decode_chunk_len = 64` pack, like Russian: one forward pass is 640 ms of audio, so the strip
// repaints half as often as English's and the commit pad is derived from its own T rather than the
// measured 500 ms (StreamingPreviewTuning.padMsFor). Delivery is unaffected — four files, one
// untargeted directory — and the cadence is the catalogue's business, recorded here only so the
// next reader does not read 71 MB as "the same pack as German".
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
// byte counts and sha256s `StreamingPackCatalog.ID` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_id")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
