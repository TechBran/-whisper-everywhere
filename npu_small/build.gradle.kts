// The npu (whisper-small) tier's asset pack (4.2 F4): the per-SoC QAIRT context binaries, one
// #group_ variant per census family, delivered by Play against app/device_targeting_config.xml.
//
// The payload is a BUILD artifact — `tools/build_asset_packs.py build` assembles the four
// variants into src/main/assets/npu_small#group_<packGroup>/ from the measured vendor zips as
// RAW bins (Play deflates in transit and delta-patches across app updates; pre-zipping would
// break both and double on-device disk for zero win). The committed tree carries ONLY this
// file, the EMPTY default variant (src/main/assets/npu_small#group_other/.gitkeep) and the
// .gitignore that keeps the payload dirs structurally uncommittable; :app's verifyNpuPacks task
// gates every bundle build on the payload matching the census and the default staying empty.
//
// (4.2 F8) Both directory spellings above changed at the first real bundleRelease, and both for
// bundletool rules no APK build can express: the directory is named after the PACK because two
// modules may not ship the same entry path with different bytes, and the fallback names the
// implicit `other` group because an unsuffixed sibling of `#group_` dirs is refused outright.
//
// A standard asset pack, not an AI pack — the same decision as npu_turbo's, stated there.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("npu_small")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
