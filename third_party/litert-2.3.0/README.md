# LiteRT 2.3.0 public C API headers

Unmodified headers from Google's `v2.3.0` tag, under Apache 2.0, used only by the opt-in Qualcomm JNI bridge. Original per-file copyrights and license notices are retained. `SOURCES.json` records upstream paths and SHA-256 hashes. `build_config.h.in` is the upstream CMake template; the app generates its feature configuration at build time. The runtime binary comes from Maven, not from this directory.

The JNI bridge owns its C API handles. It does not inspect the layout or private handle of Kotlin's `CompiledModel`. Missing C ABI symbols reject NPU setup and trigger explicit CPU recovery. Runtime/header upgrades must repeat native ABI, offload, parity, lifecycle and release checks.
