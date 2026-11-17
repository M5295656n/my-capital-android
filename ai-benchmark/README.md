# MyCapital AI Benchmark

## Purpose

Standalone, offline-only test application. It is not part of the production MyCapital APK and has no access to the portfolio database.

## Recoverable baseline

See [checkpoint record](../docs/architecture/AI_BENCHMARK_CHECKPOINT_2026-11-17_RU.md).
Immutable recovery branch:
`backup/ai-benchmark-checkpoint-2026-11-17`
Commit:
`286625843515014e42544a55ebe0bd210584bbbd`

## Pinned llama.cpp source

The `llama.cpp` directory is a Git submodule/gitlink pinned to upstream commit:

`918076a6354c647304c33ca12ee71f80de1c91ae`

Upstream: https://github.com/ggml-org/llama.cpp

The pinned revision contains the official Android sample under:
- `examples/llama.android/lib`
- `examples/llama.android/app`

The upstream Android library currently declares:
- minSdk 33
- minCompileSdk 35
- compileSdk 36
- NDK 29.0.13113456
- CMake 3.31.6
- arm64-v8a and x86_64 ABI filters

This is compatible in principle with the isolated benchmark app's API floor, but the native runtime has **not yet been wired into this module or built**. The main MyCapital app remains minSdk 26.

## Current implemented harness

- Pick a GGUF document explicitly through Android's system picker.
- Calculate SHA-256 incrementally.
- Compare the calculated digest with a separately trusted expected digest.
- Display available/total system RAM, process PSS, low-memory status and free app-volume storage.
- No INTERNET permission, no Room dependency, no portfolio access.

## Next integration gates

1. Integrate the pinned upstream Android library without changing the production app's dependencies or minSdk.
2. Review upstream JNI lifecycle and cancellation semantics before exposing generation controls.
3. Start with a conservative context and token budget suitable for the Galaxy A34 (8 GB physical RAM); tune only from measured device results.
4. Add explicit model unload/cleanup and a single-flight generation guard.
5. Run Gradle/NDK CI build before asking for device installation.
6. Measure TTFT, generation tokens/sec, peak PSS/RAM, temperature/battery trend, cancellation latency and repeated-run stability on the actual SM-A346E.
7. Only then decide whether Qwen3 1.7B Q4 is suitable.

Do not add model weights to Git or APK. Do not fetch a model automatically. Do not add network permission to this module. A SHA-256 match only proves equality with the pinned expected digest; it does not establish model provenance or quality.
