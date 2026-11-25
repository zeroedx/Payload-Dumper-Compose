## Payload Dumper Compose

在手机上直接解析和提取 OTA 包分区镜像的简单工具

## 功能特性

支持从完整 OTA URL 直接下载并解析 payload.bin

支持从本地文件读取 OTA zip 或 payload.bin

自动解析分区列表，展示分区名称、大小等信息

选择需要的分区，导出为 .img 文件

支持多选分区并行提取，展示总进度与分区进度，可单独取消某个分区

## 输出目录

镜像自动保存到内部存储的 `/storage/emulated/0/Download/<机型代号> <系统版本>/`，例如 `Download/byron os4.0.5.0/`。应用优先读取 OTA 的目标版本，也能识别包名里的 HyperOS 或 MIUI 版本。OTA 包列出多个机型时使用第一个有效代号。缺少版本时只使用机型代号，缺少机型信息时保存到 `Download/PayloadDumper/`。

只有校验通过后才会把镜像发布到输出目录；取消、空间不足、同名冲突或写入失败都会清理半成品，并保留输出目录中已有的同名镜像。

## 提取方式

从完整 OTA 包提取：下载并解析 payload.bin 后选择分区提取。

从本地导入：直接选择本地 OTA zip 或 payload.bin 文件提取。

从增量（delta）OTA 提取：当 payload 为增量包时，界面上需要源镜像的分区会显示“增量”标记，请先通过 SAF 导入对应的旧分区镜像（例如 `system.img`），程序会按清单校验源镜像后再读取源 extent。本轮增量实现支持 `SOURCE_COPY` 以及全部受支持的全量操作（`REPLACE`、`REPLACE_BZ`、`REPLACE_XZ`、`REPLACE_ZSTD`、`ZERO`、`DISCARD`）。其余差分操作（`SOURCE_BSDIFF`、`BROTLI_BSDIFF`、`PUFFDIFF`、`ZUCCHINI`、`LZ4DIFF_*`）以及 `MOVE` 暂不支持，会在写入前明确提示。

## 平台说明

仅支持 Android（minSdk 30 / targetSdk 35）。提取引擎、解压与校验均在有界缓冲下流式进行，不会把整个分区或整个操作块读入内存。

## 软件界面:

<img src="screenshot/screenshot.jpg" alt="Screenshot">

### Credits:

- [Accompanist](https://github.com/google/accompanist)
- [Apache Commons Compress](https://github.com/apache/commons-compress)
- [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)
- [Okhttp](https://github.com/square/okhttp)
- [Protobuf](https://github.com/protocolbuffers/protobuf)
- [Payload Dumper](https://github.com/5ec1cff/payload-dumper)
- [Haze](https://github.com/chrisbanes/haze)
- [payload_extract_rs](https://github.com/YuKongA/payload_extract_rs)
- [payload_extract_gui](https://github.com/YuKongA/payload_extract_gui)
