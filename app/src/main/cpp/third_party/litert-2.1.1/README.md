# LiteRT 2.1.1 C/C++ SDK headers (vendored)

From `litert_cc_sdk.zip` of https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.1 (201,438 B), unpacked
2026-09-24, headers only. Apache-2.0 (the LICENSE file beside this README is the release's).

Why 2.1.1 and not newer: it is the last LiteRT release whose MediaTek dispatch library Google has published
(`libLiteRtDispatch_MediaTek.so`, v2.1.1, sha256 9e963c56…), and the dispatch's entry points changed at 2.1.4
and 2.1.5 under an unchanged 0.1.0 version number, so the runtime, the dispatch and these headers move together
or not at all. `liblitertasr.so` reaches every runtime entry point through dlopen/dlsym of `libLiteRt.so`
(2.1.1, sha256 6ddc1b3d…), so nothing here is linked at build time; the headers give the signatures and enums.
