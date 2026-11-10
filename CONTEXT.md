# Resume context — 2026-11-09

## Current objective and stopping point

Build Roy Watson's Android Track A Vision LiteRT portfolio project. Original brief: `/Volumes/G5V1/Work/DelaSystems/AI/Track_A_Vision_LiteRT.md`. A1 implementation, repeated two-phone release captures, automated release validation, and the accepted physical-device demo are complete. Next is repository cleanup and preparation for GitHub publication. No Git initialization, commit, remote creation, or publication has been performed in this chat. The workspace was not a Git repository at the last check.

The user explicitly said **“I think that demo is sufficient.”** Accept the latest demo as complete. Do not ask for more unavailable-accelerator recovery footage: fallback is already documented by earlier captures/tests. Some README/demo notes still describe that missing footage as pending; reconcile those statements with this decision during publication cleanup. The user asked to save context and stop here, not to start publishing.

## Implementation

- Vision Lab: CameraX, Kotlin, Compose, EfficientNet-Lite0 FP32, LiteRT 2.3.0.
- Offline operation; no Internet permission. CPU/GPU selection and explicit fallback handling; opt-in Qualcomm NPU via C API/JNI with full non-CPU offload requirement.
- Qualcomm SDK: ignored `.local/qairt/2.50.0.260828`. Vendor binaries must remain outside source control; provenance/notices are documented.
- `Vision Lab Release` uses `com.roywatson.android_litert_object_recog.release`, alongside debug. UI has RELEASE BUILD / DEBUG BUILD labels.
- Earlier release startup problems were fixed by removing unused transitive WorkManager initialization and replacing template package-scoped optimization with whole-app R8.
- Separate instrumentation APK exposed R8-removed shared APIs. `app/src/release/keepRules/tracing.keep` preserves tracing, Kotlin public API, and specific application/LiteRT test entry points while allowing method-body optimization.
- `scripts/validate_npu.py` requires the whole `OK (6 tests)` suite as well as a passing NPU report.

## Benchmark results

Both phones have three release captures per CPU/GPU/NPU backend: 18 runs / 5,400 measured samples. Each capture has 30 warmup frames then 300 measured frames. All summaries were recomputed from samples; no fallback occurred; all NPU captures report `NPU_ONLY_NON_CPU_FULLY_ACCELERATED`.

Median of three run-level means / FPS values:

| Phone | Backend | Inference ms | Analysis FPS |
| --- | --- | ---: | ---: |
| S22 SM-S908U / SM8450 | CPU | 23.46 | 28.50 |
| S22 | GPU | 6.72 | 29.98 |
| S22 | NPU | 4.96 | 30.00 |
| S25 SM-S938U / SM8750 | CPU | 22.41 | 26.92 |
| S25 | GPU | 2.06 | 30.02 |
| S25 | NPU | 4.05 | 30.00 |

Reports and raw data:
- `docs/results/README.md`
- `docs/results/samsung-s22-ultra-sm-s908u/RELEASE_RESULTS.md`
- `docs/results/samsung-s25-ultra-sm-s938u/RELEASE_RESULTS.md`

Environmental conditions (thermal status, charging, lighting etc.) were not fully recorded or independently verified. The target was the same coffee mug. Do not describe the data as a rigorously controlled comparison. Cup/coffee-mug label switching does not invalidate timing captures and is not an accuracy evaluation.

Six intervening S22 exports accidentally used debug (3 CPU, 2 GPU, 1 NPU); preserved in `DEBUG_ADDITIONAL_RESULTS.md`, excluded from release statistics. Historical NPU-requested/CPU-active CSVs remain labeled as fallback. Demo capture is excluded from performance comparisons.

## Release validation

Both physical phones passed the identical signed app/test APK pair: all six instrumented tests (startup, CPU probability/label checks, RGBA rotation/padding/normalization, crop, NPU selection, NPU numerical parity/lifecycle/invalid-input checks). Release JVM unit tests (3 tests) and lint also passed.

- S22 max absolute error 0.00189842; max L1 error 0.02614530; top-one agreement 3/3 fixtures.
- S25 max absolute error 0.00245377; max L1 error 0.01687185; top-one agreement 3/3 fixtures.
- Gates: absolute error <= 0.02; L1 <= 0.10. Full NPU offload confirmed on both.
- Evidence: `docs/results/npu/README.md`, `sm-s908u-release-*`, `sm-s938u-release-*`. Public provenance omits USB serials; raw evidence is under ignored `.local/validation/`.
- Validated app SHA-256: `380201ab3f313309466d2e0ba53d6f04807fb905784b9c15123cc9c991845ab5`.
- Validated test APK SHA-256: `8e650a0dec1933ceedeabdd68bd9c1d7a4a77b2a2eec63d82cfa5bf2f9660761`.
- Benchmark APK SHA-256: `18890faab06d8a61912a15de0156d5ae295557570bec0df273f994182dc92273`.

The benchmark APK predates the instrumentation compatibility keep rules. Keep performance data attributed to its original binary; do not claim the newer binary was performance-benchmarked.

Current signed outputs: `app/build/outputs/apk/release/app-release.apk` and `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`. Copies archived in `.local/apks/vision-lab-qualcomm-release-validated-s25.apk` and corresponding `...-tests-...apk`. `app/release/app-release.apk` is an older Android Studio artifact; do not accidentally use it as the newly validated build.

Signing: user explicitly selected `/Users/roywatson/keystores/android.jks`, alias `delasystemsandroidkey`; do not use wanted-upload.jks. Credentials were entered by the user in ignored `.local/release-signing.properties` (mode 600). Never print, copy into public artifacts, or commit signing secrets.

## Accepted demo

Latest accepted cut: `docs/demo/vision-lab-offline-demo-take4-30s.mp4` (30 seconds). Original: `docs/demo/vision-lab-offline-demo-20261109-take4.mp4` (50.2958 seconds). README links the latest cut. Earlier takes/edits are preserved. Take 3 was stopped because the user was not ready and was not copied to the project.

Shows airplane mode/Wi-Fi off, release badge, live mug recognition, CPU -> GPU -> NPU switching, full NPU offload, benchmark completion and CSV export. Edit joins take 4 intervals 12–29s and 35–48s, omitting opening delay and part of the benchmark wait. Editing is disclosed in `docs/demo/README.md`; do not present it as an uninterrupted timing measurement. No recovery demonstration is needed per the user's acceptance.

## Next session

1. Read this file, README, and relevant existing engineering notes; preserve completed work.
2. Reconcile demo acceptance in README and demo notes without making unsupported claims about video coverage.
3. Review publishable files, secret exclusion, vendor/model licensing, generated artifacts, and demo media size/hosting. Multiple raw MP4s are in docs/demo; decide what belongs in the public repo while preserving local originals.
4. Prepare a clean Git repository and initial commit when work resumes. Publication has not yet been authorized as an external action; prepare a concrete reviewable result before requesting any final publication approval.
5. Do not restart benchmarks or request additional demo footage unless a new substantive reason arises.

ADB: `/Users/roywatson/Library/Android/sdk/platform-tools/adb`. S22 serial `RFCT60YMZRZ`; S25 serial `RFCY20BF3XH`. S22 was last connected, but verify current devices. When recording, wait for an explicit ready/start instruction, confirm screenrecord is running before telling the user to begin, use a 180-second window, and stop on “done.”
