// The streaming previewer's Chinese (bilingual zh-en) model pack (4.5.0 Task 2; owner ruling
// 2026-09-12, "let's set up all 6 languages"): the four
// `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t` files — encoder/decoder/joiner int8 + tokens.txt,
// 49,752,335 B in total (50 MB) — delivered by Play on demand, exactly like `preview_en`.
//
// THE BILINGUAL zh-en EXPORT, carried under the language it is SELECTED by. Two traps live in this
// row and both are closed by the digest rather than by a name: upstream keeps these files under
// `exp/32/` and `data/lang_char_bpe/` (PackFile.path), and its `exp/64/` and `exp/96/` encoders
// are ELEVEN and TWELVE bytes larger than `exp/32/`'s. A 198 MB sibling repo ships a
// byte-identical tokens.txt under a similar name; this is not that repo, and 49,752,335 B is not
// the 198 MB the first survey of it quoted.
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
// byte counts and sha256s `StreamingPackCatalog.ZH` pins (from the pinned Hugging Face commit, or
// from a local mirror it re-hashes), and :app's `verifyPreviewPack` gates every bundle build on
// what landed, for all seven packs. The committed tree carries only this file, the `.gitignore`
// that keeps the payload structurally uncommittable, and the payload directory's `.gitkeep`
// anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_zh")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
