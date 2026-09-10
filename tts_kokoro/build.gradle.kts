// The read-aloud voice's model pack (4.4.0; owner ruling 2026-09-10, the amendment pad, Task 2b):
// `kokoro-multi-lang-v1_0.tar.bz2` AS-IS — one 349,906,910 B archive — delivered by Play on demand.
//
// WHY THE ARCHIVE IS CARRIED WHOLE, not the extracted tree. `TtsModelManager.verifyExtractInstall`
// runs UNCHANGED on the pack's copy: the same ±5 % size gate, the same KNOWN_GOOD_TAR_SHA256 set,
// the same extract-to-temp + atomic swap, the same marker recording WHICH archive landed. One
// verification for both arrival routes is the amendment's rule, and the only way to keep it is for
// both routes to hand over the same artefact — a tar. An extracted payload would need a second
// verification (and a second thing to be wrong about) for the route that ships to every user.
//
// WHAT THIS CLOSES. The archive rode a ROLLING GitHub release tag (`tts-models`). k2-fsa
// re-uploaded it on 2026-09-08, the size stayed inside the band, the pinned sha256 stopped
// matching, and EVERY fresh voice install on every build — production included — failed
// "integrity verification" for two days. Riding the AAB makes a voice update a deliberate release
// instead of someone else's upload, and `verifyTtsPack` (in app/build.gradle.kts) refuses to
// package a bundle whose payload is not the pinned archive.
//
// NOT DEVICE-TARGETED, exactly like preview_en and unlike npu_small/npu_turbo: those ship one
// #group_<packGroup> variant per census family because the QAIRT context binaries differ per SoC.
// This is one archive, the same bytes on every device, so the pack carries ONE untargeted
// directory — delivered to every device, the way the app module's own assets/ ride the same bundle
// under the same `deviceGroup { enableSplit = true }` block — and app/device_targeting_config.xml
// is NOT touched here and must not be: a device group for this pack would only add a way for a
// device in no group to receive nothing.
//
// The directory is named after the PACK (assets/tts_kokoro/), the 4.2 F8 rule: an AAB may not
// carry the same entry path in two modules with different bytes, and naming every pack's directory
// after the pack retires that clash class instead of one instance. Play strips a group suffix on
// delivery, so the device sees assets/tts_kokoro/ — which is exactly what
// TtsModelManager.packTarIn opens.
//
// The payload is a BUILD artifact: `python tools/build_asset_packs.py tts` places the archive
// against the byte count and digest TtsModelManager pins (from the local mirror it re-hashes, or
// from the upstream release), and :app's verifyTtsPack gates every bundle build on what landed.
// The committed tree carries only this file, the .gitignore that keeps the payload structurally
// uncommittable, and the payload directory's .gitkeep anchor.
//
// A standard asset pack, not an AI pack — npu_turbo/build.gradle.kts states that decision and it
// is unchanged here: asset-delivery is long-GA, the bundle layout is identical if we ever migrate,
// and the model is used in-process only.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("tts_kokoro")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
