# Zesto — Modular Video-Input & Camera-Virtualization Suite

Zesto is a modular video-input and camera-virtualization suite featuring real-time stream ingestion, live hardware and virtual frame pipelines, comprehensive multi-layer telemetry diagnostics, and a Stage 3 Truth-in-Engineering target compatibility matrix.

## Features

- **Stream Configuration & Ingestion**:
  - RTSP transport endpoints with TCP (reliable interleaved) and UDP modes.
  - Interactive RTSP connection probe tester with milestone diagnostics.
  - Multi-resolution (720p, 1080p, 480p) and target framerate (30 FPS, 60 FPS) selectors.
  - Flexible video source carrier: Color Test Pattern, Physical Web Camera, or Live RTSP feed.

- **Live Stream Preview**:
  - High-performance HTML5 Canvas / WebGL hardware rendering pipeline.
  - Real-time HUD overlays: stream carrier status, frame health, latency metrics, and hardware acceleration badge.
  - 2x2 Telemetry Grid tracking resolution, real-time FPS, detected Camera2/CameraX HAL capabilities, and decode latency.

- **Comprehensive Multi-Layer Telemetry Diagnostics**:
  - Layer-by-layer inspection: Transport, Video Decoder, Frame Pipeline, Camera API & HAL, Virtualization Backend, and Target Integration.
  - Pipeline Boundary Milestones checklist tracking end-to-end frame verification stages.
  - Real-time diagnostic event logs with subsystem tags, severity level filtering (INFO, WARNING, ERROR), and search.
  - Markdown telemetry export with one-click clipboard copy and `.md` file download.

- **Target Application Compatibility Matrix**:
  - Stage 3 Truth-in-Engineering verification matrix with 8 preconfigured target profiles (Open Camera, Zesto Controlled Test, Discord, WhatsApp, Zoom, Google Meet, Telegram, Instagram, Snapchat).
  - Background streaming service controller and IPC provider status.
  - Interactive Controlled Camera Test Target harness with live side-by-side feed switching between raw sensor and virtual injection.
  - LSPatch non-root portable mode & LSPosed root module setup guide with `TargetApplicationPatcherInspector` manifest integrity verification.

## Tech Stack

- React 18 + TypeScript + Vite
- Tailwind CSS with custom Elegant Dark theme
- Lucide React Icons
- Motion animations
