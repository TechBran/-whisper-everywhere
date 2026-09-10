#!/usr/bin/env python3
"""Fetch the MediaTek NPU runtime libraries LiteRT needs for Accelerator.NPU / Backend.NPU on MT6989.

LiteRT's `litert_npu_runtime_libraries_jit.zip` is documented as carrying a `mediatek_runtime/` module
(https://developers.google.com/edge/litert/next/npu), but the v2.1.5, v2.1.6 and v2.2.0 zips on GitHub
contain only google_tensor_runtime/ and qualcomm_runtime_v*/ (listed 2026-09-09). The last release whose
zip still ships the MediaTek pair is **v2.1.1** (2026-01-27):
  mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_MediaTek.so        409,728 B
  mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so  492,784 B
Both are Apache-2.0 LiteRT release binaries; the compiler plugin's strings list mt6983/6985/6989/6991/6993
and the dispatch library dlopens libneuron_adapter_mgvi.so -> libneuronusdk_adapter.9.mtk.so ->
libneuronusdk_adapter.mtk.so -> libneuron_adapter.so and reads libneuron_sys_util.mtk.so.
They are not committed; this script drops them into app/src/main/jniLibs/arm64-v8a/ (gitignored).
"""
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

URL = "https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries_jit.zip"
ZIP_SHA256 = "4d6433eceb0e9c97388e5d10af9c71a1f97cf93f4a0f21acc492b97a342d45c3"
FILES = {
    "mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_MediaTek.so":
        "9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706",
    "mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so":
        "28335079bec01ab57ebcbe2c027bb07b9fce1d5eda0d0acb3826a0600b68c5ef",
}
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "app", "src", "main", "jniLibs", "arm64-v8a")


def main():
    cache = os.environ.get(
        "LITERT_ZIP_CACHE",
        os.path.join(os.path.expanduser("~"), ".androidbuild", "probe-deps", "npu_jit_v2.1.1.zip"),
    )
    if os.path.exists(cache):
        data = open(cache, "rb").read()
    else:
        print("downloading", URL)
        data = urllib.request.urlopen(URL).read()
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        open(cache, "wb").write(data)
    got = hashlib.sha256(data).hexdigest()
    if got != ZIP_SHA256:
        print("zip sha256 mismatch", got)
        sys.exit(1)
    os.makedirs(OUT, exist_ok=True)
    z = zipfile.ZipFile(io.BytesIO(data))
    for name, sha in FILES.items():
        b = z.read(name)
        h = hashlib.sha256(b).hexdigest()
        if h != sha:
            print("member sha256 mismatch", name, h)
            sys.exit(1)
        dst = os.path.join(OUT, os.path.basename(name))
        open(dst, "wb").write(b)
        print("ok", dst, len(b), h)


if __name__ == "__main__":
    main()
