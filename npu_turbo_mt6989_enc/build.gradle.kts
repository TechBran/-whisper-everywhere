// The mt6989 turbo pair's ENCODER pack (P2-5, the MediaTek APU tier; design
// docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md §2.7): large-v3-turbo's encoder,
// compiled ahead of time for the MT6989's APU — 1,302,606,488 B, part 1 of a pair that is
// 1.88 GB against Play's 1.5 GB per-pack cap — and the pair's metadata.json (version 2), which
// lists BOTH entries, so it rides here in part 1. npu_turbo_mt6989_dec carries part 2.
//
// UNTARGETED, AND THIS FAMILY'S OWN. The Qualcomm packs (npu_small, npu_turbo) ship one #group_
// variant per census family. bundletool's DeviceGroupParityValidator requires every module with
// device-group targeting to support the same set of groups, and a MediaTek pair can never share
// the Qualcomm modules' set — so this module carries NO #group_ folder at all: one payload
// directory, assets/npu_turbo_mt6989_enc/, delivered as itself to whatever device fetches it.
// No device fetches it but an mt6989 one: NpuPackFetch.packsFor names this pack for the mt6989
// census row alone, and the census gate — not Play — is the authority over who asks. An
// untargeted module cannot hold a per-family variant, so a second MediaTek family gets two
// modules of its own; nothing of it can ride in these.
//
// The payload is a BUILD artifact: `python tools/build_asset_packs.py build-local` copies the
// encoder out of the private artefact store (the compile's mirrored pair and its SHA256SUMS)
// under the tier's catalog name, gated on the pinned length and digest, the file's own
// LiteRtStamp, the bytecode's compiler self-description and the IO census against
// NpuModelSpec.TURBO; :app's verifyNpuPacks re-checks what landed before every bundle build. The
// committed tree carries only this file and the .gitignore that keeps the whole packaged tree,
// src/main/assets/, uncommittable. (P3a) No anchor file: this plugin zips src/main/assets whole,
// with no filter and no DSL to add one, so the tracked .gitkeep the payload directory carried
// until P3a shipped as a zero-byte asset (sheet §8 of 2026-09-24-tab-apu-turbo-encoder.md); the
// delivered pack holds exactly its payload files.
//
// A standard asset pack, not an AI pack — the same decision as npu_turbo's, stated there.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("npu_turbo_mt6989_enc")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
