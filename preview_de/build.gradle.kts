// The streaming previewer's German model pack (4.5.0 Task 2; owner ruling 2026-09-12, "let's set
// up all 6 languages"): the four `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de`
// files — encoder/decoder/joiner int8 + tokens.txt, 70,938,534 B in total (71 MB) — delivered by
// Play on demand, exactly like `preview_en`.
//
// THE ROW WHOSE UPSTREAM LAYOUT IS NOT FLAT. Its four files live at `exp/epoch-30/` and
// `lang_bpe_500/` upstream, and the three ONNX filenames carry COMMAS
// (`…-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx`). `PackFile.path` holds that spelling for
// the download and `PackFile.name` holds the flat one, so what lands in this directory — and
// therefore what rides into the AAB as an asset entry — is four plain names with no comma and no
// subdirectory. The asset entry is the one of the three spellings this repo cannot test without a
// 5.5 GB bundleRelease, so the comma is kept out of it rather than bet on.
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
// byte counts and sha256s `StreamingPackCatalog.DE` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_de")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
