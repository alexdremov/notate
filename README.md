# Notate

<p align="center">
  <img src="icon.svg" alt="Notate Icon" width="128" height="128">
</p>

**High-Performance Infinite Canvas for Onyx Boox**

## About Notate

Notate is an open-source note-taking application engineered specifically for **Onyx Boox E-Ink devices**.
It addresses the common trade-off in E-Ink development—latency vs. feature set—by implementing a **Hybrid Rendering Pipeline**.

The app decouples input from persistence: your pen strokes are rendered instantly via the device's hardware overlay (Direct Update mode) for zero-latency feedback,
while a background thread commits them to a robust, tiled infinite canvas.
This ensures that writing feels like paper, but digital capabilities (infinite zoom, layers, smart editing) remain fully accessible.


## ✨ Features

| Feature | Description |
|---------|-------------|
| ⚡ **Zero-Latency Input** | Leverages the Onyx `EpdController` to write directly to the hardware frame buffer |
| ♾️ **Infinite Canvas** | An endless workspace with zoom from 1% to 1000% using tiles + level-of-detail optimizations |
| 📐 **Shape Recognition** | Draw and hold to snap rough strokes into perfect geometric shapes: lines, triangles, rectangles, circles, pentagons, hexagons |
| 📄 **Backgrounds** | Blank, ruled, grid, or dots background |
| ✏️ **Scribble-to-Erase** | Quickly erase content by scribbling over it—detects zig-zag gesture density and velocity |
| 🧴 **Smart Erasers** | Stroke eraser, standard (partial) eraser, and lasso eraser modes |
| 📝 **Images** | Paste images seamlessly into the canvas |
|  **Selection** | Select, copy and move groups of strokes or images |
| 🧭 **Fixed Pages Mode** | Page Mode emulation with visual grid to help structure notes in pages |
| 🎨 **Deep Customization** | Pen styles (Fountain, Ballpoint, Fineliner, Highlighter), colors, and stroke sizes |
| ⏪ **Undo/Redo** | Unlimited undo/redo history |
| 📱 **Beautiful UI** | A clean, intuitive interface with floating toolbars and sidebar |
| 📤 **Export Options** | Export to PDF (vector or raster) |
| ☁️ **Cloud Sync** | Google Drive / WEBDAV integration for instant backup and sync |


## 🎮 Gestures & Controls

| Gesture | Action |
|---------|--------|
| **Draw** | Standard pen input |
| **Hold** | Triggers shape recognition (configurable) |
| **Scribble** | Triggers erase mode over scribbled area (configurable) |
| **Button** | Toggle Eraser |
| **Pan** | Drag anywhere to move the viewport |
| **Pinch** | Zoom in and out |
| **Double Tap** | Undo the last action |
| **Tap UI** | Interact with floating toolbar or sidebar |
| **Longpress** | Select strokes or objects or paste objects by longpressing to empty area |

---

## 📦 Installation

### Prerequisites

- **Device:** Onyx Boox (Android 11+ recommended)

### Relases

Download the latest APK from the [Releases](https://github.com/alexdremov/notate/releases) section and install it on your device.

### Build from Source

1. **Clone the repository:**
   ```bash
   git clone https://github.com/alexdremov/notate.git
   cd notate
   ```

2. **Build Debug APK:**
   ```bash
   ./gradlew app:assembleDebug
   ```
   APK will be at:  `app/build/outputs/apk/debug/app-debug.apk`

---

## 🏗️ Technical Architecture

For developers interested in E-Ink optimization, Notate employs several key patterns:

### Spatial Partitioning
Uses a **Quadtree** (`util/Quadtree.kt`) to manage stroke visibility, ensuring O(log N) query performance even with thousands of strokes.

### Ghosting Control
Automatically switches between eink different update mods to ensure smooth rendering on E-Ink displays.

### Tiled Rendering
The `TileManager` implements Level-of-Detail (LOD) with cached bitmap tiles, allowing smooth zooming across a virtually infinite canvas.

### Concurrency
Heavy tasks like tile generation and shape recognition run on dedicated `ThreadPoolExecutors` to keep the UI thread responsive.

### Key Classes

| Class | Purpose |
|-------|---------|
| `InfiniteCanvasModel` | Core data model with thread-safe stroke storage |
| `TileManager` | LOD-based tile caching and rendering |
| `Quadtree` | Spatial indexing for efficient stroke queries |
| `ShapeRecognizer` | Geometry recognition using Douglas-Peucker + competitive scoring |
| `ScribbleDetector` | Gesture analysis for scribble-to-erase |
| `PenInputHandler` | Input processing with dwell detection |

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- [Onyx Boox SDK](https://github.com/onyx-intl/OnyxAndroidDemo) for E-Ink pen input APIs
- [Notable](https://github.com/Ethran/notable) for inspiration
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) for SDK restrictions workaround
