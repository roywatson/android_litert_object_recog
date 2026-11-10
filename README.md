# Vision Lab - On-Device Vision with LiteRT

An Android-only Jetpack Compose application demonstrating live on-device image classification with CameraX and LiteRT (formerly TensorFlow Lite), with CPU, GPU, and verified Qualcomm NPU execution and a benchmark that exports its raw data.

This project is milestone A1 of a series on on-device machine learning. The goal is to make LiteRT behavior visible without hiding the hard parts behind a wrapper library. The model's tensor contract, camera frame strides and rotation, GPU thread affinity, accelerator selection, explicit CPU fallback, native NPU offload verification, release-build optimization, and the difference between inference speed and frame rate are all represented directly in the code.

The app is a single screen:

- **Live classification** - the rear camera's center square is classified against 1,000 ImageNet classes, with the top three predictions and confidence scores.
- **Backend selection** - CPU and GPU in every build; Qualcomm NPU in the opt-in Qualcomm build. The app shows both the requested and the actually active backend.
- **Live metrics** - inference latency, total frame processing time, and real analysis throughput over a rolling 60-frame window.
- **Benchmark capture** - 30 warmup frames, then 300 measured frames, exported as CSV through Android's document picker with every per-frame sample and full device/runtime metadata.

The model and its labels ship inside the APK. The app requests camera permission and has **no Internet permission**. Camera images are never stored.

There is no dependency injection framework, no persistence layer, and no ML wrapper library. The project keeps those things out of the way so the LiteRT code itself is easy to follow.

[Watch the 30-second airplane-mode demo](docs/demo/vision-lab-offline-demo-take4-30s.mp4): release build, live recognition, CPU → GPU → NPU switching with confirmed full NPU offload, benchmark capture, and CSV export. [Demo notes](docs/demo/README.md) describe the edit.

---

## Running the project

Clone the repository and open it in Android Studio.

This is an Android application for phones with a rear camera. A physical device is strongly recommended. The emulator can run the CPU path and the unit tests, but camera behavior, GPU timing, and NPU execution are hardware behaviors.

To verify the bundled model and build from the command line:

```bash
python3 scripts/verify_model.py
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Launch **Vision Lab**, allow camera access, and point the rear camera at a single object in the center square. Choose CPU or GPU. Use **Capture benchmark**, wait for completion, then **Export CSV**.

To run the tests:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
# Requires a connected phone or emulator:
./gradlew :app:connectedDebugAndroidTest
```

The portable build supports Android 7.0 (`minSdk 24`) and targets SDK 37. Gradle needs network access to resolve dependencies; the installed app works fully offline, including first launch.

### Qualcomm NPU build (optional)

The default build is portable and reports NPU as unavailable, with an explicit CPU fallback. The opt-in Qualcomm build adds the Qualcomm AI Runtime (QAIRT) and a small C++/JNI bridge. It requires Android 12 (API 31+), arm64, NDK 28.2.13676358, and CMake 3.22.1, and is configured for the SM8450 (Snapdragon 8 Gen 1) and SM8750 (Snapdragon 8 Elite).

```bash
python3 scripts/prepare_qairt.py --download
./gradlew -PqualcommNpuDir=.local/qairt/2.50.0.260828 :app:assembleDebug
python3 scripts/validate_npu.py --serial DEVICE_SERIAL
```

Vendor binaries are fetched into ignored `.local/`, are never committed, and are not covered by this project's license. To make the Qualcomm build the default for Android Studio (including the signing wizard), put `qualcommNpuDir=.local/qairt/2.50.0.260828` in ignored `.local/build.properties`. An explicitly empty `-PqualcommNpuDir=` selects the portable build.

For a signed release, copy `scripts/release-signing.properties.example` to ignored `.local/release-signing.properties`, fill in your own keystore details, and run `:app:assembleRelease`. The release app installs as **Vision Lab Release** with a `.release` application ID, alongside the debug app. Validate it with `python3 scripts/validate_npu.py --serial DEVICE_SERIAL --build-type release`.

Full setup, vendor provenance, and validation details: [docs/NPU.md](docs/NPU.md).

---

## Permissions

The manifest requests exactly one permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

There is no Internet permission and no storage permission. Benchmark CSVs leave the phone only when the user chooses a destination in the system document picker. If camera access was permanently denied, the app offers a shortcut to its settings page.

---

## What this demonstrates

### Bundled LiteRT with `CompiledModel`

The app uses the standalone LiteRT 2.3.0 runtime and its `CompiledModel` API rather than ML Kit, MediaPipe Tasks, or the Play services runtime. Each of those is a valid choice for many products. Here, bundling supports offline first launch, and `CompiledModel` exposes the environment, compiled model, and tensor buffers as explicit objects so ownership, cleanup, and timing boundaries are clear.

The LiteRT `Environment` lives for the process, avoiding accelerator library reloads on backend switches. Each engine releases its buffers and model on close, including when construction fails part way through.

### A verified tensor contract

The bundled model is EfficientNet-Lite0 FP32 from Google's MediaPipe collection, unmodified. Its contract was verified from the file itself:

- input: float32 RGB `[1, 224, 224, 3]`, NHWC
- normalization: `(channel - 127) / 128`, from the model's embedded metadata
- output: float32 `[1, 1000]`, already probabilities, so no extra softmax
- labels: extracted byte-for-byte from the model's embedded associated file, with no background-class offset

EfficientNet exports differ in their normalization. Using values from a different export still runs and still produces plausible output, just less accurate output. `scripts/verify_model.py` checks the SHA-256, shapes, normalization, labels, and embedded license using only Python's standard library. The NPU path re-verifies the model hash on device. [Model provenance](docs/MODEL.md).

### CameraX and backpressure

`ImageAnalysis` uses `STRATEGY_KEEP_ONLY_LATEST`, so slow frames are dropped rather than queued and predictions never lag behind the camera. CameraX converts YUV to RGBA before the analyzer runs. That keeps the A1 preprocessing readable; a direct YUV path is planned for A3.

### One thread owns every native resource

LiteRT's GPU path requires that a model be created, invoked, and closed on the same thread. `VisionSession` owns a single-thread executor, and CameraX delivers frames on that same executor. Model creation, inference, backend switches, benchmark bookkeeping, and cleanup are all serialized structurally, with no locks around the model. Every `ImageProxy` is closed in `finally`. UI updates are throttled to about ten per second.

### Preprocessing

`FramePreprocessor` is a straightforward reference implementation. It honors RGBA row and pixel strides (camera buffers are often padded), rotates by `imageInfo.rotationDegrees`, center crops to a square, bilinearly resizes to 224 x 224, and normalizes. Rotation, crop, and resize are a single `Matrix` draw. Buffers are allocated once and reused. The preview is square, so what the user sees is what the model classifies.

### Requested versus active backend

A backend is never reported as active until its engine is successfully constructed. If GPU or NPU initialization fails, or an accelerator fails mid-stream, the session releases it, opens CPU explicitly, discards incomplete measurements, and shows the reason. Every CSV records both `requested` and `active`, plus `execution_evidence` and `fallback_reason`, so a fallback run can never be mistaken for an accelerator result.

### Verified Qualcomm NPU offload

The released Kotlin options implicitly allow CPU and do not expose a full-offload check. The NPU engine therefore uses LiteRT's public C API through a small JNI bridge (`npu_bridge.cpp`) that:

1. creates and owns its own environment, model, options, compiled model, and tensor buffers - it never touches the Kotlin wrapper's private native handles
2. resolves each C function from the packaged `libLiteRt.so` against version-pinned 2.3.0 headers
3. re-checks the model's tensor contract in native code
4. requests **only** `kLiteRtHwAcceleratorNpu`
5. requires `LiteRtCompiledModelIsNonCpuFullyAccelerated` to return true; partial or unknown offload is rejected with CPU recovery
6. converts every C++ exception into a Java exception at the JNI boundary

Only a passing candidate is reported as `NPU_ONLY_NON_CPU_FULLY_ACCELERATED`. This establishes runtime-reported placement of the model graph. Preprocessing and buffer transfers still use the CPU.

A runtime preflight checks the build flag, Android version, SoC manufacturer and model, and required libraries before any native work. `prepare_qairt.py` uses HTTP range requests to fetch only the needed files from the ~2.6 GB vendor SDK, verifying ZIP CRCs and pinned hashes; Gradle re-verifies them before every Qualcomm build.

### Honest measurement

All timing uses monotonic `SystemClock.elapsedRealtimeNanos()`:

- **Inference ms** - input tensor write through output tensor read, including host copies
- **Frame ms** - analyzer entry through preprocessing, inference, validation, and ranking
- **Analysis FPS** - completed analyses over wall-clock time, never `1000 / inference_ms`

A capture is cancelled if the app leaves the foreground, and discarded on a backend switch. [Benchmark protocol](docs/BENCHMARKS.md).

### Release-build behavior

Two issues appeared only in optimized release builds. LiteRT's transitive WorkManager dependency registered a startup initializer that created an unused database at launch; it is removed through manifest merging. R8 removed APIs that the separately compiled instrumentation APK needed; targeted keep rules in `app/src/release/keepRules/tracing.keep` preserve those signatures while still optimizing method bodies, so the tested binary is the shipped binary.

---

## Results

Both phones were measured with a signed release build: three 300-frame captures per backend, 18 runs and 5,400 measured frames in total. Every run reports the requested backend as active, and every NPU run reports full offload. Values are the median of the three run-level means.

| Phone | Backend | Inference ms | Frame ms | Analysis FPS |
| --- | --- | ---: | ---: | ---: |
| Galaxy S22 Ultra SM-S908U (SM8450) | CPU | 23.46 | 31.97 | 28.50 |
| Galaxy S22 Ultra SM-S908U (SM8450) | GPU | 6.72 | 21.74 | 29.98 |
| Galaxy S22 Ultra SM-S908U (SM8450) | NPU | 4.96 | 19.63 | 30.00 |
| Galaxy S25 Ultra SM-S938U (SM8750) | CPU | 22.41 | 30.25 | 26.92 |
| Galaxy S25 Ultra SM-S938U (SM8750) | GPU | 2.06 | 20.07 | 30.02 |
| Galaxy S25 Ultra SM-S938U (SM8750) | NPU | 4.05 | 21.94 | 30.00 |

Accelerators reduced inference time substantially, but throughput stays near the camera's 30 FPS analysis rate, and most accelerated frame time is now outside the model. Both phones run Android 16 / API 36. Environmental conditions (thermal state, charging, lighting) were not fully recorded, so treat these as observations on two flagship phones, not a controlled comparison.

Per-run tables, ranges, raw CSVs, and file hashes: [results overview](docs/results/README.md), [S22 release results](docs/results/samsung-s22-ultra-sm-s908u/RELEASE_RESULTS.md), [S25 release results](docs/results/samsung-s25-ultra-sm-s938u/RELEASE_RESULTS.md). Earlier uncontrolled captures, including runs that requested NPU from a portable build and correctly fell back to CPU, are kept and labeled separately.

---

## Incorporating this into your project

The screen is a sample workflow. The reusable pieces are in `app/src/main/java/com/roywatson/android_litert_object_recog/vision` and `app/src/main/cpp`:

- **`InferenceEngine.kt`** - the engine interface, the Kotlin CPU/GPU `CompiledModel` engine, the Qualcomm preflight, and the native NPU engine
- **`VisionSession.kt`** - single-thread ownership of model lifecycle, inference, backend switching with fallback, and benchmark capture
- **`FramePreprocessor.kt`** - stride-aware RGBA to normalized float tensor with rotation and center crop
- **`Benchmark.kt`** - percentile and throughput math and CSV escaping
- **`npu_bridge.cpp`** - LiteRT C API bridge with NPU-only selection and full-offload verification

If you use a different model, start with its tensor contract: shape, channel order, normalization, output type, and label alignment. If you bring the code into a production app, review camera lifecycle, battery and thermal behavior, model delivery and updates, accuracy on your own data, and the vendor license terms for any accelerator runtime you distribute.

---

## Running the tests

The JVM unit tests cover deterministic logic:

- throughput from wall-clock time rather than reciprocal latency
- single-frame edge case
- CSV escaping of commas, quotes, and newlines

The instrumented suite (six tests) runs on device:

- Activity startup
- CPU model execution, 1,000 finite probabilities, label alignment
- RGBA row padding, RGB order, normalization, and all four rotations
- center crop
- NPU selection reports verified offload or explicit fallback
- CPU/NPU numerical parity, native recreation, and invalid-input rejection

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Both target phones pass all six tests on the identical signed release app/test APK pair, with full NPU offload, maximum absolute score error of 0.0019 (S22) and 0.0025 (S25) against a 0.02 gate, and matching top-one predictions. These are numerical smoke tests on synthetic frames, not accuracy measurements. [Validation evidence](docs/results/npu/README.md).

---

## Project structure

```
app/src/main/java/com/roywatson/android_litert_object_recog/
  MainActivity.kt              # Compose screen, permissions, CameraX binding, CSV export

  vision/
    VisionSession.kt           # Single-thread owner: models, inference, fallback, capture
    InferenceEngine.kt         # CPU/GPU CompiledModel engine, Qualcomm preflight, NPU engine
    FramePreprocessor.kt       # RGBA strides, rotation, crop, resize, normalization
    Benchmark.kt               # Percentiles, throughput, CSV escaping

app/src/main/cpp/
  npu_bridge.cpp               # LiteRT C API / JNI NPU bridge with offload verification

app/src/main/assets/
  efficientnet-lite0-fp32.tflite
  labels.txt

app/src/release/keepRules/
  tracing.keep                 # R8 rules for release instrumentation testing

scripts/
  verify_model.py              # Model checksum and tensor-contract verification
  fetch_model.py               # Restore the pinned model and labels
  prepare_qairt.py             # Fetch and verify selected QAIRT vendor files
  validate_npu.py              # Build, install, run, and collect device validation evidence

third_party/litert-2.3.0/      # Version-pinned LiteRT C API headers

docs/
  MODEL.md                     # Model provenance and tensor contract
  NPU.md                       # Qualcomm build, offload rules, validation
  BENCHMARKS.md                # Timing definitions and capture protocol
  NOTES.md                     # Engineering decisions and history
  results/                     # Raw CSVs, release results, NPU validation evidence
  demo/                        # Demo video and notes
```

---

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.4.21, C++ (NPU bridge) |
| UI framework | Jetpack Compose with Material 3 |
| Camera | CameraX 1.6.2 |
| ML runtime | LiteRT 2.3.0 (`CompiledModel`; C API for NPU) |
| NPU runtime (optional) | Qualcomm AI Runtime 2.50.0.260828 |
| Model | EfficientNet-Lite0 FP32 (MediaPipe), 18.6 MB |
| Android Gradle Plugin | 9.5.1 |
| Gradle | 9.6.0 |
| Android min SDK | 24 (31 for the Qualcomm build) |
| Android target SDK | 37 |
| NDK / CMake (Qualcomm build) | 28.2.13676358 / 3.22.1 |
| Tests | JUnit 4, AndroidX Test |

---

## Roadmap

- **A1 - this project:** live classification, CPU/GPU/NPU selection with verified offload and explicit fallback, CSV benchmarks on two phones
- **A2:** multi-object detection with rotation- and aspect-correct bounding boxes
- **A3:** Kotlin versus C++/NEON YUV preprocessing, measured end to end
- **A4:** fine-tuned model on a licensed custom dataset, with FP32/FP16/int8 accuracy and latency comparison
- **A5:** a separate Compose Multiplatform project adding Core ML on iOS

---

## License

Project code: [Apache 2.0](LICENSE), copyright 2026 Roy Watson. Model and third-party attribution: [NOTICE](NOTICE) and [model card](docs/MODEL.md). Optional QAIRT binaries have separate vendor terms, stay outside source control, and have their notices bundled in the Qualcomm APK.

---

## About

Before Roy Watson wrote a line of Android code, he was programming the propellant management system for NASA's Delta launch vehicles, building radiation detection software for Los Alamos and Sandia National Laboratories, and developing embedded vision systems for IBM's autonomous vehicle research. After 30+ years as a systems developer, he approaches Android differently than most.

Over the past 17 years he has specialized in Android, staying current with every major evolution of the platform: Kotlin, Jetpack Compose, and for the last two years Kotlin Multiplatform and Compose Multiplatform for iOS, Desktop, and Web. Recent clients include the New York Public Library, Auddia, Bechtel, and Goodyear. When performance work requires dropping into C/C++, SIMD intrinsics, or custom memory management, he does not hand it off.

His personal published apps have accumulated 70,000+ Android downloads (4.5-star) and 207,000+ iOS downloads (4.6-star). He studied Physics and Mathematics at Purdue University and is a member of Mensa International.

This project is part of a series of public examples demonstrating current Android and Compose Multiplatform architecture and patterns. The series begins with [Android Custom Theme](https://github.com/roywatson/android_custom_theme) and [Compose Multiplatform Custom Theme](https://github.com/roywatson/cmp_custom_theme), which build a complete custom theming system from first principles. Subsequent projects introduce [Dependency Injection with Koin](https://github.com/roywatson/cmp_di_koin), [DataStore Preferences](https://github.com/roywatson/cmp_di_koin_datastore_prefs), [HTTP networking with Ktor](https://github.com/roywatson/cmp_ktor), [Custom JVM Titlebars](https://github.com/roywatson/cmp_jvm_custom_titlebar), [Compose Multiplatform WebView](https://github.com/roywatson/cmp_webview), and [Android BLE Tools](https://github.com/roywatson/android_ble_tools). This project begins a track on on-device machine learning, where the most important behavior is close to the hardware.

Questions, corrections, or suggestions are welcome. Reach out through [roywatson.app](https://www.roywatson.app) or directly at rwatson@roywatson.com.

Available for contract and full-time engagements - [roywatson.app](https://www.roywatson.app)

[roywatson.app](https://www.roywatson.app) | [linkedin.com/in/roywatson3](https://linkedin.com/in/roywatson3) | [github.com/roywatson](https://github.com/roywatson)
