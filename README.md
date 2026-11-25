# PrismFiles — Offline Mobile File Manager & Format Converter

**PrismFiles** is a high-performance, 100% offline file manager and multi-format conversion suite. Built for Android (pure native Kotlin + Rust NDK) and command-line automation. Featuring an **OLED Pitch Black (`#000000`) theme**, native conversion engines, 3D Cushion Treemap disk analyzer, and the signature **Donut radial menu**.

---

## Website & Download Android APK

- **Official Website:** [https://prismfiles.pages.dev](https://prismfiles.pages.dev)
- **Install PrismFiles directly on any Android device (Android 7.0+ / API 24–35+):**
  - **Direct Repository Download:** [PrismFiles.apk](PrismFiles.apk) or [release/PrismFiles.apk](release/PrismFiles.apk)
  - **GitHub Releases:** [Download PrismFiles APK v1.0.0](https://github.com/sohamisOPP/Prismfiles-/releases/tag/v1.0.0)

---

## Core Architecture & Features

- **Pure Offline Architecture:** Zero network trackers, zero telemetry, 100% offline local processing.
- **Pitch-Black AMOLED Interface:** Pure `#000000` AMOLED surfaces with high-contrast typography, zero emojis, and battery optimization.
- **Rust NDK High-Performance Core:** Integrated native Rust engine for cryptographic hashing (SHA-256, MD5, CRC32), file shredding, and high-throughput processing.
- **Interactive Cushion Treemap Disk Analyzer:** 3D pseudo-shaded cushion treemap algorithm visualizing recursive disk and folder usage. Direct tap-to-drill, hierarchical path breadcrumbs, file sorting, and "Open in Files" navigation.
- **Donut Radial Menu & Format Converter:** 17 specialized conversion profiles with targeted format wheels for Images, Video, Audio, Documents, Archives, and Code. Auto-saves outputs into dedicated `converted/` subdirectories.
- **Embedded Media Previews:** Real-time thumbnail and preview rendering for image and video assets directly in file lists.
- **Advanced Archive Management:** High-performance archive extraction, viewing, and creation.
- **Overflow & Utilities Hub:** Direct access to Storage Analyzer, Cushion Treemap, and Batch Operations from the top-right menu.

---

## Headless CLI Companion

PrismFiles includes a standalone command-line companion (`./bin/prismfiles` or `node cli.js`) for terminal workflows, automated scripts, and cron jobs:

```bash
# Convert image, document, data, or archive
./bin/prismfiles convert photo.png --to webp

# Compute cryptographic hashes
./bin/prismfiles checksum document.pdf --algo sha256

# Securely wipe sensitive files (DoD 5220.22-M 3-pass)
./bin/prismfiles shred secret.key --passes 3

# Display visual directory tree
./bin/prismfiles tree ./my_folder

# Export metadata catalog to JSON, CSV, or Markdown
./bin/prismfiles catalog ./photos --format csv

# Launch local Wi-Fi transfer server with QR code
./bin/prismfiles serve --port 8080
```

---

## Documentation

- [Architecture & Feature Walkthrough](docs/walkthrough.md): Comprehensive breakdown of the pure native Android architecture, Rust NDK core, 17 Donut Wheel profiles, Cushion Treemap analyzer, and release verification.
- [Implementation Plan](docs/implementation_plan.md): The technical plan and milestone roadmap.

---

## License

MIT License. Open source and free for everyone.
