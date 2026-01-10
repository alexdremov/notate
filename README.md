# Notate

**High-Performance Infinite Canvas for Onyx Boox**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android_15_E--Ink-green?style=for-the-badge&logo=android)](https://www.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

---

## About Notate
Notate is an open-source note-taking application engineered specifically for **Onyx Boox E-Ink devices** (optimizing for the NoteAir4C).
It addresses the common trade-off in E-Ink development—latency vs. feature set—by implementing a **Hybrid Rendering Pipeline**.

The app decouples input from persistence: your pen strokes are rendered instantly via the device's hardware overlay (Direct Update mode) for zero-latency feedback,
while a background thread commits them to a robust, tiled infinite canvas.
This ensures that writing feels like paper, but digital capabilities (infinite zoom, layers, smart editing) remain fully accessible.

---

## Features

* ⚡ **Zero-Latency Input:** leverages the Onyx `EpdController` to write directly to the hardware frame buffer.
* ♾️ **Infinite Canvas:** An endless workspace. Zoom from 1% to 1000% with tiles + level-of-detail optimizations.
* 📐 **Shape Recognition:** Draw and hold to instantly snap rough strokes into perfect geometric shapes (Circles, Squares, Polygons) using a "Competitive Error" scoring algorithm.
* ✏️ **Scribble-to-Erase:** Quickly erase content by scribbling over it. The app detects the "zig-zag" gesture density and velocity to trigger the eraser.
* 🧴 **Smart Erasers:**
    * **Stroke Eraser:** Removes entire strokes.
    * **Standard Eraser:** Remove parts of strokes.
    * **Lasso Eraser:** Lasso strokes for selecting areas to clear.
* 🧭 **Dynamic Navigation:** Even on an infinite canvas, Notate offers "Page Mode" emulation with a visual grid to help you structure your notes.
* 🎨 **Deep Customization:** Pen styles, colors, stroke sizes, and more.

## Gestures & Controls

Notate uses a combination of pen and touch inputs to maximize efficiency without cluttering the UI.

* **Draw:** Standard input.
* **Hold:** Triggers **Shape Recognition** (snaps current stroke to geometry).
* **Scribble:** Triggers **Erase** mode over the scribbled area.
* **Button:** Toggle Eraser (Lasso/Stroke depending on config).
* **Pan:** Drag anywhere on the canvas to move the viewport.
* **Tap UI:** Interact with the floating toolbar or sidebar.
* **Pinch:** Zoom in and out.
* **Double Tap:** **Undo** the last action.

---

## Installation

### Prerequisites
* **Device:** Onyx Boox (Android 11+ recommended).
* **Environment:** Android Studio Iguana+, JDK 21.

### Build from Source

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/yourusername/notate.git
    cd notate
    ```

2.  **Build Debug APK:**
    ```bash
    ./gradlew app:assembleDebug
    ```

3.  **Release Build:**
    To build a signed release APK, configure your `keystore.properties` (see `scripts/release.sh` for details) and run:
    ```bash
    ./scripts/release.sh
    ```

---

## Technical Architecture

For developers interested in E-Ink optimization, Notate employs several advanced patterns:

*   **Hybrid Rendering:** Combines `SurfaceView` for hardware raw drawing and a software-based `TileManager` for permanent storage.
*   **Spatial Partitioning:** Uses a **Quadtree** to manage stroke visibility, ensuring $O(\log N)$ query performance even with thousands of strokes.
*   **Ghosting Control:** Automatically switches between `UpdateMode.DU` (during drawing) and `UpdateMode.GC` (after pauses) to maintain screen clarity without user intervention.
*   **Concurrency:** Heavy tasks like tile generation and shape recognition run on dedicated `ThreadPoolExecutors` in parallel to keep the UI thread 100% responsive.

## License

This project is open source. See [LICENSE](LICENSE) for details.
