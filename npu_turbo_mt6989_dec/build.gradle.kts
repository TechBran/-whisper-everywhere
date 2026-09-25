// The mt6989 turbo pair's DECODER pack (P2-5, the MediaTek APU tier; design
// docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md §2.7): large-v3-turbo's decoder
// step, compiled ahead of time for the MT6989's APU — 584,862,184 B, part 2 of the pair whose
// part 1 (the encoder, with the pair's metadata.json) is npu_turbo_mt6989_enc.
//
// UNTARGETED, AND THIS FAMILY'S OWN, for the reason npu_turbo_mt6989_enc/build.gradle.kts states
// in full: bundletool's DeviceGroupParityValidator requires every group-targeted module to carry
// the same set of groups, so this module has no #group_ folder — one payload directory,
// assets/npu_turbo_mt6989_dec/ — and the census gate alone decides who fetches it.
//
// The payload is a BUILD artifact placed by `python tools/build_asset_packs.py build-local` and
// re-checked by :app's verifyNpuPacks before every bundle build. The committed tree carries only
// this file, the .gitignore that keeps the payload uncommittable, and the payload directory's
// .gitkeep anchor.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("npu_turbo_mt6989_dec")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
