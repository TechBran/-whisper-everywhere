// The streaming previewer's model pack (4.4.0; owner ruling 2026-09-10, the amendment pad):
// the four `streaming-zipformer-en-2023-06-26` files — encoder/decoder/joiner int8 + tokens.txt,
// 72,654,782 B in total — delivered by Play on demand.
//
// NOT DEVICE-TARGETED, and that is the whole difference from npu_small/npu_turbo. Those packs
// ship one #group_<packGroup> variant per census family because the QAIRT context binaries differ
// per SoC; these four ONNX files are the same bytes on every device, so the pack carries ONE
// untargeted directory. An untargeted asset directory is delivered to every device — the app
// module's own assets/ ride the same bundle under the same `deviceGroup { enableSplit = true }`
// block — so app/device_targeting_config.xml is NOT touched here and must not be: a device group
// for this pack would only add a way for a device in no group to receive nothing.
//
// The directory is named after the PACK (assets/preview_en/), the 4.2 F8 rule: an AAB may not
// carry the same entry path in two modules with different bytes, and naming every pack's
// directory after the pack retires that clash class instead of one instance. Play strips a group
// suffix on delivery, so the device sees assets/preview_en/ — which is exactly what
// StreamingPackInstall.packSourceDir opens.
//
// The payload is a BUILD artifact: `python tools/build_asset_packs.py preview` places the four
// files against the byte counts and sha256s StreamingPackCatalog.EN pins (from the pinned Hugging
// Face commit, or from a local mirror it re-hashes), and :app's verifyPreviewPack gates every
// bundle build on what landed. The committed tree carries only this file, the .gitignore that
// keeps the payload structurally uncommittable, and the payload directory's .gitkeep anchor.
//
// A standard asset pack, not an AI pack — npu_turbo/build.gradle.kts states that decision and it
// is unchanged here: asset-delivery is long-GA, the bundle layout is identical if we ever
// migrate, and the models are used in-process only.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("preview_en")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
