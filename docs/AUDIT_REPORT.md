# Payload-Dumper-Compose — Phase 1 Audit Report

- **Fork audited:** `github.com/zeroedx/Payload-Dumper-Compose`
- **Upstream:** `github.com/rcmiku/Payload-Dumper-Compose`
- **Audit commit:** `1727785` ("app: Bump version to 1.5"), branch `main`
- **Date:** 2026-10-12
- **Scope of this phase:** read-only audit, risk list, dependency-upgrade advice, phased plan. **No source code was modified.**
- **Method:** full source read (23 Kotlin files, ~1.5k LOC), build/resource/manifest review, GitHub API comparison vs upstream, and a review of the reference Rust/Go dumper implementations for the delta-OTA question.

> **Baseline fact (verified, not assumed):** the fork is **byte-identical to upstream** (`compare main...rcmiku:main` → `status: identical`, `ahead_by: 0`, `behind_by: 0`). Upstream itself has been dormant since **2025-02-10**. There is no hidden fork work; an "established baseline" means *building this exact commit*.

> **Environment fact (verified):** this container has **no JDK, no Android SDK, and no Gradle distribution or cache**, and `git` here cannot complete a TLS handshake to `github.com` for a clone (network egress appears restricted to allow-listed hosts). Therefore **no build or test was executed in this phase**, and none is claimed. Phase 0 (below) establishes the build baseline.

---

## 1. Repository & module structure

Single-module Android app (Gradle wrapper 8.10.2):

```
Payload-Dumper-Compose/
├── build.gradle.kts            # plugin aliases only
├── settings.gradle.kts         # :app, FAIL_ON_PROJECT_REPOS
├── gradle/libs.versions.toml   # version catalog
├── gradle/wrapper/             # 8.10.2
└── app/
    ├── build.gradle.kts        # compileSdk 35 / minSdk 30 / targetSdk 35, R8 on release
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── proto/update_metadata.proto      # AOSP, proto2, java+kotlin "lite"
        │   ├── java/com/rcmiku/payload/dumper/compose/
        │   │   ├── MainActivity.kt
        │   │   ├── model/Payload.kt             # Payload / PayloadHeader / PartitionInfo / ArchiveInfo
        │   │   ├── viewModel/PayloadDumperViewModel.kt
        │   │   ├── ui/PayloadDumperApp.kt       # single screen
        │   │   ├── ui/theme/Theme.kt
        │   │   ├── ui/component/*.kt            # 9 dialogs/cards/items
        │   │   └── utils/*.kt                   # 11 util files
        │   └── res/                             # en + zh-rCN strings, themes, xml rules
        ├── test/…/ExampleUnitTest.kt            # placeholder only (2+2)
        └── androidTest/…/ExampleInstrumentedTest.kt  # placeholder only
```

There is **no CI workflow** (`.github/` is absent) and **no lint/ktlint/spotless config**. The only "tests" are the two IDE-generated placeholders.

## 2. Build configuration & dependencies

| Item | Value | Comment |
|---|---|---|
| AGP | 8.8.0 | |
| Gradle | 8.10.2 | |
| Kotlin | 2.1.0 | |
| compileSdk / targetSdk / minSdk | 35 / 35 / 30 | minSdk 30 (Android 11) |
| Java source/target | 21 | |
| Compose BOM | 2025.01.01 | Material3 via BOM |
| Miuix | **0.3.4** | UI toolkit, used as the *only* UI layer |
| Haze | 1.3.1 | top-app-bar blur |
| OkHttp | 4.12.0 | |
| Protobuf (plugin) | 0.9.4 / protoc 4.29.1 | `java`+`kotlin` **lite** |
| protobuf-kotlin-lite | 4.29.1 | |
| commons-compress | 1.27.1 | XZ/BZip2 streams; also pulls `commons-io` (**undeclared** direct use) |
| xz | 1.10 (org.tukaani) | |
| Accompanist | 0.37.0 (`drawablepainter`) | |
| Lifecycle | 2.8.7 (runtime-ktx only) | **no `lifecycle-viewmodel-compose`, no `runtime-compose`** |
| activity-compose | 1.10.0 | |

**Observations**
- Dependencies are ~20 months behind (2025-01 vintage). No Kotlin 2.2/2.3, no Compose 1.8+, no AGP 8.9+.
- Compose BOM 2025.01.01 + Kotlin 2.1.0 is a coherent, buildable set — **upgrade selectively in Phase 2**, not in bulk.
- `commons-io` is used directly (`org.apache.commons.io.IOUtils`) but only arrives transitively via commons-compress → must be declared explicitly before any dependency bump can drop it.
- `CountDown`/coroutine support: `kotlinx-coroutines` is used directly in code but is not declared (it comes transitively via Compose/lifecycle) — declare explicitly.

## 3. Runtime architecture & data flow

**Flow:** `MainActivity` → `PayloadDumperApp` (single screen) → `ParseUtil().parse(pathOrUrl)` → `PayloadUtil.initPayload` → `PayloadDumperViewModel.initPayloadDumper` → list rendered by `PartitionInfoItem` → `DumpUtil().dump()` → `PayloadUtil.extractPartition` → `HttpUtil` *or* `RandomAccessFile`.

**Global singletons (the central design smell):** `AppContextUtil` (application context), `RandomAccessFileUtil` (a single shared `RandomAccessFile` for the local ZIP), `HttpUtil` (a shared mutable **position cursor** for the remote ZIP), and `PayloadUtil.muteX`. State lives in `object`s and `ViewModel` `StateFlow`s; there is no repository abstraction.

**Parsing / ZIP handling** (`ZipFileUtil`, `PayloadUtil.getPayloadOffset`): the app does *not* unzip. It reads the last 4096 bytes, locates the ZIP End-Of-Central-Directory (with ZIP64 handling), finds the `payload.bin` central-directory entry, then the local-file header, and computes the absolute payload offset. `payload.bin` offsets are then read directly out of the ZIP and each `InstallOperation` blob decoded from `dataOffset + op.dataOffset`. This is the correct, efficient approach for a phone.

**Extraction**: operations are applied **sequentially** to `${outputDir}/<partition>.img`; `REPLACE` → raw, `REPLACE_BZ` → BZip2, `REPLACE_XZ` → XZ, `ZERO` → zeros; everything else throws `Unsupported operation type`.

**Remote**: one HTTP **Range** request per `read()` call (not one per operation), 30 s connect/read/write timeouts, custom/derived `User-Agent` interceptor.

## 4. Confirmed current functionality

1. Parse an **HTTPS** full-OTA `.zip` URL and a **local** `.zip`/`.bin`.
2. List partitions with name, image size, uncompressed size, and expected SHA-256.
3. Dump an individual partition (one at a time) with a progress spinner.
4. Search/filter partitions.
5. Custom `User-Agent`; custom output folder; About dialog.
6. Dark/light theme following the system; edge-to-edge.

## 5. Known defects & risks

### 5.1 Correctness / data-integrity

| # | Sev | Finding | Location |
|---|---|---|---|
| C1 | **High** | **No SHA-256 verification is ever performed.** The expected hash in `newPartitionInfo.hash` is stored in `PartitionInfo.sha256` and displayed, but never compared to the extracted `.img`. `rawSize` is likewise cosmetic. "Dump" can silently produce a wrong/short file. | `PayloadUtil.extractPartition` (260-303), `model/Payload.kt` |
| C2 | **High** | **Delta payloads are silently mis-dumped.** For a delta payload, `SOURCE_COPY`/`SOURCE_BSDIFF`/`PUFFDIFF`/`BROTLI_BSDIFF`/`ZUCCHINI`/`LZ4DIFF_*` operations throw `Unsupported operation type` only *after earlier `REPLACE_*` operations have already been written* — leaving a partial, corrupt `.img` and no cleanup. There is **no upfront detection** that `minor_version != 0`. | `PayloadUtil.extractFromLocal/Http` (`else -> throw`) |
| C3 | **High** | **`getPartitionInfoList` can crash or report wrong sizes.** It reads `operationsList[size-1]` and `operationsList[0]` with no empty check (partitions with zero operations, or `REPLACE`-only ordering assumptions, break `rawSize`), and `it.newPartitionInfo.size` throws if `new_partition_info` is absent. | `PayloadUtil.kt:145-154` |
| C4 | **High** | **Local ZIP is opened `"rw"`** for a read-only operation. Fails on read-only media/SAF-sourced files; grants needless write handle. | `ParseUtil.kt:27` |
| C5 | Med | **Concatenated `dst_extents` are ignored.** Only `dstExtentsList[0].startBlock` is used as the write position. This is correct for the common single-extent-after-merge case but wrong in general; also `op.dstExtentsList[0]` throws `IndexOutOfBounds` for a `ZERO`-only op with no dst extent. | `extractFromLocal/Http` |
| C6 | Med | **`REPLACE` writes the compressed length, not the padded-to-block size.** `payload.deltaArchiveManifest.blockSize.toInt()` blocks must be zero-padded; direct `partOutput.write(data)` skips padding. | `extractFromLocal/Http` |
| C7 | Med | **Progress is `partOutput.channel.position()`** (a byte count of data written to the file), then divided by `partitionInfo.size` in the UI. Because of `dst_extents[0]` seeks and skipped padding, position is **not** monotonic or equal to "bytes of the partition produced" → progress bar can jump backwards/overshoot. | `extractPartition:299`, `PayloadDumperApp.kt:406` |
| C8 | Med | **Only `https://` URLs work.** `getPayloadOffset` tests `startsWith("https://")`, but `isValidUrl()` accepts `http://` too. An `http://` OTA takes the *local-file* branch and fails confusingly. | `PayloadUtil.kt:311,328` |
| C9 | Low | `MiscUtil.sizeIn()` mixes decimal (`< 1000` → B/KB/MB) and binary (`/1024`) thresholds; boundaries are inconsistent (`999 B` vs `1 KB`=1024). | `MiscUtil.kt:44-51` |
| C10 | Low | `getRealPathFromUri` only resolves `primary` volume `document`/`tree` URIs; entries on SD/secondary volumes or non-`DocumentContract` providers (some cloud/`content://` pickers) return `null` and are silently dropped. `OpenDocumentTree` also returns `content://` roots, so `customFolder` can be a `content://` path that `File`/`Paths.get` cannot use. | `MiscUtil.kt:83-106`, `PayloadDumperApp.kt:143-154` |

### 5.2 Concurrency / lifecycle / resource management

| # | Sev | Finding | Location |
|---|---|---|---|
| L1 | **High** | **Extraction runs in `rememberCoroutineScope()`** (composition-scoped). A configuration change or navigation away **cancels the dump mid-write**, leaving a truncated `.img` and a stuck "downloading" flag. | `PayloadDumperApp.kt:111,398`, `ViewModel` |
| L2 | **High** | **No true cancellation, no cancel UI.** `HttpUtil.read`/`IOUtils.copy` are blocking calls with no `ensureActive()`; cancelling the scope stops the coroutine at suspension points but not the blocking I/O, and the partly-written `.img` + `partOutput` may be left behind. There is no way for the user to cancel a dump. | `PayloadUtil`, `HttpUtil`, `DumpUtil` |
| L3 | **High** | **Cross-directory `.use` defect.** `extractFromLocal` passes `FileOutputStream(partOutput.fd)` into `IOUtils.copy`; when `IOUtils.copy` closes that stream it **closes the shared `partOutput` file descriptor**. Subsequent operations on the same partition then write to an invalid FD. The `"REPLACE"` path (direct `partOutput.write`) is the only reason simple payloads still work — this is latent corruption for `REPLACE_XZ`/`REPLACE_BZ`-heavy payloads. Same pattern in `extractFromHttp`. | `PayloadUtil.kt:169,217` |
| L4 | Med | **`Mutex` is a lazy global shared across all partitions** and wraps *all* I/O including the long `IOUtils.copy`. Fine for today's one-at-a-time flow, but it will deadlock/serialize any future parallel or batch extraction, and it guards no shared mutable state that the two extraction functions actually share. | `PayloadUtil.kt:37-39,163,210` |
| L5 | Med | **Global `HttpUtil` position is mutable shared state**; `PayloadUtil` seeks it (`httpUtil.seek(...)`) inside a mutex, but the header read in `initPayloadFromHttp` seeds it. Any future concurrency or a second parse reuses stale `url`/`fileName`/`fileLength`. | `HttpUtil`, `PayloadUtil` |
| L6 | Med | **`RandomAccessFileUtil` is never closed.** Parsing a new file overwrites the singleton reference → leaked FD. Same for `HttpUtil` (OkHttp client is a singleton, fine, but response bodies are per-call). | `RandomAccessFileUtil`, `ParseUtil.kt:28` |
| L7 | Med | **`parse()` opens the ZIP RAF inside `withContext(Dispatchers.IO)`, but `handleParsing` runs `parseLogic()` on the caller's dispatcher** (`rememberCoroutineScope` = Main). `PayloadUtil.getPayloadOffset` wraps its own IO, but `initPayloadLocal` does raw `raf.readFully` on Main. Blocking disk I/O on the main thread. | `ParseUtil`, `PayloadUtil.initPayloadLocal`, `PayloadDumperApp.kt:349` |
| L8 | Low | `DumpUtil` writes `PreferencesUtil().perfGet(...)` and `Environment.getExternalStoragePublicDirectory` on whatever dispatcher the caller used (Main) — minor. | `DumpUtil.kt` |
| L9 | Low | `ViewModel.updateBySearch()` launches a `combine(...).collect` that never completes and is re-collected only once (in `init`); fine but means the flow stays hot for the VM lifetime with no `stateIn`/`distinctUntilChanged`. | `PayloadDumperViewModel.kt:63-77` |
| L10 | Low | `PayloadDumperApp` keeps `payload.value!!` non-null assertions for dump; a recomposition race after re-parse could throw. | `PayloadDumperApp.kt:401-402` |

### 5.3 API / platform compatibility

| # | Sev | Finding |
|---|---|---|
| A1 | **High** | **`requestLegacyExternalStorage` is gone on API 30+**, and the app relies entirely on `MANAGE_EXTERNAL_STORAGE` (All-Files-Access) to write to `Downloads` and to read arbitrary local ZIPs. This triggers a Play **"All files access" policy review** and is hostile UX. Almost all of the "local file" and "custom folder" flows can be re-done with **SAF** (`OpenDocument`/`OpenDocumentTree`) + app-scoped or MediaStore outputs, no privileged permission. |
| A2 | Med | `getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` is deprecated on API 29+; should use `MediaStore`/SAF. |
| A3 | Med | `targetSdk 35` (Android 15) mandates edge-to-edge; the app uses `enableEdgeToEdge()` + Miuix scaffold insets so it is broadly OK, but the deprecated `window.isNavigationBarContrastEnforced = false` and lack of predictive-back handling should be revisited. |
| A4 | Med | Miuix **0.3.4** is an old, moving-target Compose toolkit. Upstream Miuix is now 0.9.x (memory corroborates 0.9.4 across sibling forks). 0.3.4 → 0.9.x is a **large, breaking** jump; treat as a separate, optional workstream, not a prerequisite. |
| A5 | Low | No `queries`/`FileProvider`; sharing an extracted `.img` isn't possible. |
| A6 | Low | Release `signingConfig` registers a config with **no keystore** (v1-v4 flags only). A release build without `signingConfigs`/env secrets will be **unsigned** (or fail on a strict config). Local `assembleRelease` will produce an unsigned APK — must be documented, and the app id must **not** change. |
| A7 | Low | `allowBackup="true"` with empty backup-rule files: minor privacy surface (SharedPreferences with URLs are backed up). |
| A8 | Low | `about` hard-codes the upstream URL `rcmiku/Payload-Dumper-Compose`; a fork should show its own source link (cosmetic, but currently misleading). |
| A9 | Info | No `INTERNET`-less mode / no proxy handling beyond OkHttp defaults. |

### 5.4 Testing, quality, CI

| # | Sev | Finding |
|---|---|---|
| T1 | **High** | **Zero meaningful tests.** Only `2+2` and a package-name check. Core logic (`getPayloadOffset`, `ZipFileUtil` EOCD/ZIP64 parsing, `toLong/toInt`, `sizeIn`, header parsing, `getPartitionInfoList`, extraction) is completely untested. |
| T2 | **High** | **No CI.** No build/lint/test automation for the fork. |
| T3 | Med | No static analysis (ktlint/spotless/android-lint baseline). |
| T4 | Low | `PayloadDumperViewModel` is only 77 lines; `PayloadDumperApp` is 424 lines doing UI + orchestration + dialogs — the classic "everything in one screen" shape. |
| T5 | Low | Mixed log/no-log: `ParseUtil.showToast` surfaces raw exception messages (`exception.message ?: "Unknown error"`) to users; some may be non-localizable/internal. |

### 5.5 Notable *good* findings (reusable code — do not throw away)

- The **ZIP central-directory + ZIP64 + local-header offset math** (`ZipFileUtil`) is a genuinely valuable, self-contained piece; it should be **kept and unit-tested**, not rewritten.
- The **payload header parser** (`CrAU` magic, format version 2, manifest + metadata signature layout) is correct.
- **HTTP Range** access with a single reused OkHttp client and a custom UA is the right shape for remote dumping.
- The **proto2 `update_metadata.proto`** is the canonical AOSP file and already models deltas (`minor_version`, `src_extents`, `source_sha256_hash`, all op types) — **the data model already supports everything Phase 2 needs.**

---

## 6. Incremental (delta) OTA feasibility

**Current state: not implemented, and — importantly — not *detected*.** Today a delta payload produces a partial/corrupt image plus a thrown exception (C2).

**What the payload format requires:**
- `minor_version != 0` ⇒ delta. Requires **old images** as input.
- Operations: `SOURCE_COPY` (copy from old), `SOURCE_BSDIFF`/`BSDIFF` (bspatch), `BROTLI_BSDIFF`, `PUFFDIFF` (puffin), `ZUCCHINI`, `LZ4DIFF_BSDIFF`, `LZ4DIFF_PUFFDIFF`. The proto already declares all of them.
- Delta payloads typically **omit the dm-verity hash tree and FEC parity** for `system`/`vendor`-type partitions; a bit-exact image needs those recomputed (hash tree + `fec_roots` Reed-Solomon). This is a significant subsystem.

**Reference implementations reviewed:**

| Implementation | Language | Delta support | Notes |
|---|---|---|---|
| `ssut/payload-dumper-go` | Go | **Yes** (SOURCE_COPY/BSDIFF/BROTLI_BSDIFF), **no** PUFFDIFF/ZUCCHINI/LZ4DIFF; regenerates verity hash tree + FEC; verifies op/source/final SHA-256; `-old` base-image dir | Closest reference for a mobile-friendly design; single native `xz` dep. |
| `rhythmcache/payload-dumper-rust` | Rust | **Experimental**, everything except `ZUCCHINI`; `--source-dir`; parallel; URL prefetch; metadata JSON; `--no-verify` | The Rust option the brief names. Pure-ish Rust, but a full new parser + a Rust↔Kotlin FFI boundary. |
| `5ec1cff/payload-dumper` (credited upstream) | Python | Full (uses `bsdiff4`, `protobuf`, `six`) | Python not shippable on Android here. |

**Assessment / recommendation:**
- **Do NOT swap the bottom parser for a Rust implementation.** That contradicts the brief's explicit "禁止未经验证直接替换底层解析器", and it would add an FFI/JNI layer, an NDK toolchain, and a second dependency ecosystem for a single-module app whose parsing already works for the full-OTA case.
- **Port the *algorithm*, not the codebase.** The pragmatic path is a Kotlin-native delta layer that reuses the existing parser/ZIP/HTTP stack:
  1. **Detect & refuse cleanly first** (Phase 2A, low risk): read `minor_version`; if `!= 0`, show a clear "incremental OTA not supported" state and never write a partial file. This alone removes a data-corruption class.
  2. **Phase 2B (experimental):** implement `SOURCE_COPY` + `SOURCE_BSDIFF` (bspatch is ~200 lines of Kotlin and public-domain algorithms) + `BROTLI_BSDIFF` (add a small brotli dep) with a user-selected "old images" directory. Validate bit-exactness against `payload-dumper-go -old` output for a known delta.
  3. **PUFFDIFF / ZUCCHINI / LZ4DIFF:** explicitly **out of scope / unsupported**, surfaced as such. Even `payload-dumper-go` and `payload-dumper-rust` do not cover all of them.
  4. **Verity/FEC regeneration:** defer; treat as a separate, larger effort and label delta output as "not verity-complete" until done. Never claim "unsupported" features are implemented.

**Honesty guardrail:** the app must distinguish **Implemented** (full OTA; Phase 2B SOURCE_COPY/SOURCE_BSDIFF), **Experimental** (BROTLI_BSDIFF, verity/FEC), and **Unsupported** (PUFFDIFF/ZUCCHINI/LZ4DIFF) in the UI — no silent partials.

---

## 7. Dependency upgrade recommendations (staged, verify each step)

Rules: **one axis per step; re-run `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` after each.** Prefer the newest patch that keeps Kotlin/Compose/AGP consistent.

| Step | Change | Risk | Notes |
|---|---|---|---|
| D0 | Add missing **direct** deps: `commons-io`, `kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-viewmodel-compose` + `lifecycle-runtime-compose`, `androidx.documentfile`. | Low | Stabilizes the graph before bumps; needed anyway for SAF work. |
| D1 | AGP 8.8.0 → **8.9/8.10** (still Gradle 8.x compatible), keep Kotlin 2.1.0. | Low-Med | Enables newer SDK tooling without a Gradle jump. |
| D2 | Gradle 8.10.2 → **8.11.x** if required by D1/AGP. | Low | Wrapper-only change. |
| D3 | Kotlin 2.1.0 → **2.1.20/2.2.x** + matching `kotlin-compose` plugin. | Med | Recompile all Compose; watch Miuix 0.3.4 compatibility. |
| D4 | Compose BOM → latest compatible with chosen Kotlin. | Med | Material3 API drift in dialogs/indicators. |
| D5 | OkHttp 4.12.0 → **4.12.x** (keep 4.x; don't jump to 5.x yet). | Low | API-stable. |
| D6 | commons-compress 1.27.1 → 1.27.x/1.28.x; declare `commons-io`. | Low | Check XZ/BZip2 stream APIs. |
| D7 | **Miuix 0.3.4 → 0.9.x** | **High** | Large breaking API change (0.9.x renamed/moved many widgets). **Separate workstream.** Evaluate first via a scratch branch; if too costly, stay on 0.3.4 and only fix bugs. Do **not** "整体重写". |
| D8 | Optionally add `com.squareup.okio`/coil if we import from SAF; not required. | — | Only if a feature needs it. |

Also recommended (not dependency changes): enable `android.experimental...`? no — instead add **ktlint/spotless** + a **GitHub Actions** workflow (JDK 21, Android SDK, `assembleDebug/Release`, `testDebugUnitTest`, `lint`) on the fork only.

## 8. Phased implementation plan (Phase 2+)

> Each phase: **build + test must pass**; record failures and root causes; commit to the fork's `main` (or a feature branch merged to `main`); **never touch upstream**; **never change `applicationId`**.

### Phase 0 — Baseline & safety net (do first)
- Install JDK 21 + Android SDK (platform 35, build-tools) in the dev container so builds are reproducible; or rely on CI.
- Add CI workflow (fork-only) running `assembleDebug`, `assembleRelease`, `testDebugUnitTest`, `lint`.
- Add **`testDebugUnitTest` scaffolding** and unit tests for the *existing* pure logic that need no Android: `MiscUtil.toLong/toInt/toHexString/sizeIn/isValidUrl`, `ZipFileUtil.locate*` (fixtures), `PayloadUtil.getPayloadOffset` (a tiny synthetic ZIP fixture), header parsing. **This is the regression net that makes every later change safe.**
- Document the unsigned-release signing caveat; introduce a local keystore via env only if the user asks.
- Deliverable: green build + first real tests; record exact commands.

### Phase 1 — Correctness hardening (no new features)
- C1: add **SHA-256 verification** of each extracted partition (and per-op `data_sha256_hash` where present); mark ✅/❌ in the UI; delete/quarantine failed outputs.
- C2: **detect `minor_version != 0`** upfront; refuse with a clear message instead of writing a partial image; clean up partial files.
- C3: guard `getPartitionInfoList` against empty operations / missing `new_partition_info`; compute `rawSize` correctly from `dst_extents` × blockSize.
- C4/L3: open local ZIP **`"r"`**; **stop closing `partOutput`** (use a non-closing wrapper or copy directly via `partOutput.write`), so multi-op partitions stay valid.
- C6: zero-pad `REPLACE` to `blockSize`; honor all `dst_extents`.
- C8: accept `http`/`https` consistently (and surface scheme errors).
- C9/C10: fix size formatting; make SAF folder/what-is-`content://` handling explicit.

### Phase 2 — Local import, selection, batch & progress
- Replace All-Files-Access with **SAF** where possible (`OpenDocument` for payload.bin/zip, `OpenDocumentTree` for output; use `DocumentFile`/`ParcelFileDescriptor` instead of `File` paths). Keep `MANAGE_EXTERNAL_STORAGE` only as a fallback if truly needed, and justify it.
- **Multi-select partitions** + **batch extraction** with a queue; per-item progress + overall progress.
- Move extraction into the **`ViewModel`** (`viewModelScope`, `SupervisorJob`) so config changes survive; add a **persistent foreground-service option** for very large dumps (and a `dataSync`/`shortService` declaration) with **real cancellation** (`ensureActive()` + interruptible streams + `Call.cancel()` for HTTP).
- Fix progress math (monotonic, based on produced bytes, not FD position).

### Phase 3 — Remote robustness
- Normalize to `okhttp` retries, exponential backoff, `If-Range`/`ETag` resume, 416/206 handling, redirects, and a user-agent setting already present.
- Handle servers that **ignore Range** (fall back to full download), and `Content-Length`/`Content-Range` divergence.
- Cancellable remote task wired to the same ViewModel/queue as local.

### Phase 4 — Metadata, verification UX, history, output
- Show **partition metadata**: `minor_version`, partition type, op-type histogram, `src_length`, verity/FEC presence, `version`, per-op `data_sha256_hash` presence.
- **SHA-256 verify** results surfaced as first-class status; option to auto-verify after dump.
- **History** (parsed payloads + dumps, timestamps, sizes, hashes) in a small Room/DataStore store.
- **Custom output directory** via SAF persisted URI (already half-present; make it correct and permission-light).

### Phase 5 — Incremental OTA (experimental, explicitly scoped)
- `SOURCE_COPY` + `SOURCE_BSDIFF` (+ optional `BROTLI_BSDIFF`), with a user-chosen **old-images** directory.
- Bit-exact validation against `payload-dumper-go -old` on a known delta.
- UI must label **Implemented / Experimental / Unsupported**; **PUFFDIFF/ZUCCHINI/LZ4DIFF explicitly unsupported**; verity/FEC regeneration deferred and labelled.

### Phase 6 — UI polish & modern Android
- Keep Jetpack Compose. Keep Miuix **unless** D7 proves viable; support phone + tablet (adaptive layouts/`WindowSizeClass`), dark mode (already works), predictive back, proper insets.
- Only if D7 is approved: migrate Miuix 0.3.4 → 0.9.x as an isolated PR with visual regression checks.

### Phase 7 — Tests across the board
- Unit: delta detection, op-type dispatch, bspatch, progress math, hash verify, ZIP/ZIP64 offsets, `getPayloadOffset` fixtures, cancellation.
- Integration: extract a **synthetic full-OTA** fixture end-to-end and assert image hash; a small delta fixture once Phase 5 lands.
- Keep an instrumentation smoke test for SAF flows.

## 9. Constraints acknowledged & how they're honored

| Constraint | Status |
|---|---|
| Build a buildable baseline first | Phase 0; **note: baseline not yet built here** (no toolchain) — will be established before any code change. |
| Don't upgrade all deps at once | §7 staged table, one axis per step. |
| Don't fake features/tests/compat results | This report marks everything unverified as such; no build/test is claimed. |
| Build + test each phase, log failures | Phase 0 CI + test harness; process rule stated. |
| No deleting features, no app-id change, no formal release without confirmation | Explicit rule in plan. |
| Commit only to the fork, never upstream | Explicit rule. |
| Submit audit first, wait for confirmation | This document; **Phase 2 will not start until you confirm.** |

## 10. Open questions for you (blocking Phase 2)

1. **Build environment:** may I install JDK 21 + Android SDK (cmdline-tools, platform 35, build-tools 35.x) in this container and set up a fork-only CI workflow, so I can actually build/test? No build has been possible yet.
2. **Miuix:** keep 0.3.4 (bug-fix only) or invest in the 0.3.4 → 0.9.x migration (Phase 6 / D7)? The brief says don't rewrite without reason; I recommend deferring.
3. **Storage permission:** are you OK moving off `MANAGE_EXTERNAL_STORAGE` to SAF (better Play story) — accepting that a few `content://` edge cases need new code?
4. **Delta OTA:** confirm the scoped target (SOURCE_COPY/SOURCE_BSDIFF experimental; PUFFDIFF/ZUCCHINI/LZ4DIFF unsupported) and that verity/FEC regeneration may be deferred.
5. **Release signing:** should I add a keystore-via-env setup, or leave release unsigned for now (no app-id change either way)?

---

## Appendix A — File-by-file risk index

| File | Lines | Role | Key risks |
|---|---|---|---|
| `MainActivity.kt` | 26 | Entry | Global context init; deprecated nav-bar flag (A3) |
| `model/Payload.kt` | 38 | Models | `sha256`/`rawSize` unused for verify (C1) |
| `viewModel/PayloadDumperViewModel.kt` | 77 | State | Hot flow, progress from FD position (L9/C7) |
| `ui/PayloadDumperApp.kt` | 424 | Screen + orchestration | Dump in `rememberCoroutineScope` (L1), `!!` (L10), content:// output (C10) |
| `ui/theme/Theme.kt` | 17 | Theme | OK (system dark/light) |
| `ui/component/AboutDialog.kt` | 98 | About | Upstream URL shown (A8) |
| `ui/component/CustomUserAgentDialog.kt` | 107 | Settings | OK |
| `ui/component/InfoTextView.kt` | 34 | Card | OK |
| `ui/component/InputDialog.kt` | 65 | URL input | OK |
| `ui/component/InputInfoCard.kt` | 40 | Card | OK |
| `ui/component/PartitionDialog.kt` | 27 | Dialog | Shows unverified sha (C1) |
| `ui/component/PartitionInfoItem.kt` | 118 | List item | Progress states (C7) |
| `ui/component/RequestPermissionDialog.kt` | 85 | Permission | All-files-access reliance (A1) |
| `ui/component/RomInfoCard.kt` | 37 | Card | OK |
| `utils/AppContextUtil.kt` | 12 | Singleton ctx | Leak suppressions; testability |
| `utils/DumpUtil.kt` | 74 | Dump orchestration | Toasts in logic; progress (L8) |
| `utils/HttpUtil.kt` | 128 | HTTP Range | Shared mutable position (L5); cancel (L2) |
| `utils/MiscUtil.kt` | 106 | Helpers | `sizeIn` (C9), SAF path (C10) |
| `utils/ParseUtil.kt` | 106 | Parse entry | `"rw"` (C4), Main-thread IO (L7), null-on-unknown-URI |
| `utils/PayloadUtil.kt` | 343 | **Core** | C1-C8, L3-L4 — the epicenter |
| `utils/PreferencesUtil.kt` | 29 | Prefs | OK |
| `utils/RandomAccessFileUtil.kt` | 11 | Singleton RAF | Never closed (L6) |
| `utils/UserAgentUtil.kt` | 36 | UA | OK |
| `utils/ZipFileUtil.kt` | 96 | ZIP math | Untested but valuable (T1) |

## Appendix B — Verified facts vs. open items

- **Verified:** fork == upstream (byte-identical); upstream dormant since 2025-02-10; single module; deps as tabled; no CI; only placeholder tests; no local toolchain in this container.
- **Not verified (requiring a build/device, deferred to Phase 0+):** actual build success on this commit; R8/ProGuard behavior; Miuix 0.9.x migration cost; real-world delta/OOM behavior on a full-size OTA; SAF edge cases on specific OEMs.
