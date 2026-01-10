# Notate

**High-Performance Infinite Canvas for Onyx Boox**

**Notate** is an open-source note-taking application designed for **Onyx Boox E-Ink devices** (Target: NoteAir4C).
It combines low-latency hardware rendering with a flexible software drawing engine to provide a responsive and reliable writing experience.

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?style=for-the-badge&logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-Android_15_E--Ink-green?style=for-the-badge&logo=android)
![Status](https://img.shields.io/badge/Status-Active_Development-orange?style=for-the-badge)

---

## Overview

Notate utilizes a **Hybrid Rendering Pipeline** to address the latency challenges common in E-Ink development:
1.  **Hardware Layer:** Utilizes the Onyx EPD controller (`Direct Update` mode) for immediate stylus feedback.
2.  **Software Layer:** Commits strokes to a background Tiled Infinite Canvas with Levels of Detail (LOD) for persistence, zooming, and efficient rendering.

### Key Features
*   **Infinite Canvas:** Unbounded drawing area with optimization for deep zoom levels (1% - 1000%).
*   **Shape Recognition:** "Draw and hold" to automatically convert rough strokes into geometric shapes (Circles, Squares, Polygons).
*   **Scribble-to-Erase:** Detects scribble gestures over existing text to trigger erasure.
*   **Smart Erasers:** Includes Stroke, Lasso, and Object erasers with hardware-accelerated visualization.
*   **Dynamic Navigation:** Supports fixed-page emulation within the infinite canvas, including a grid-based page preview.

## Technical Architecture

*   **Rendering Engine:** Custom `SurfaceView` implementation using **Quadtree** spatial partitioning for $O(\log N)$ visibility queries.
*   **E-Ink Optimization:**
    *   **Ghosting Control:** Automates switching between `UpdateMode.DU` (Drawing) and `UpdateMode.GC` (Cleanup).
    *   **Animation Mode:** Triggers refresh for smooth 60fps UI interactions (Menus, Zooming, Panning).

## Building & Installation

### Prerequisites
*   JDK 21
*   Onyx Boox Device (Android 11+)

### Build Steps
1.  **Clone the repository:**
    ```bash
    git clone https://github.com/yourusername/notate.git
    cd notate
    ```
2.  **Build the debug APK:**
    ```bash
    ./gradlew app:assembleDebug

    ./gradlew app:installDebug
    ```

## Controls

| Action | Gesture |
| :--- | :--- |
| **Pan** | One-finger drag |
| **Zoom** | Two-finger pinch |
| **Undo** | Two-finger tap |
| **Shape Snap** | Draw & Hold |
| **Erase** | Stylus Button, "Scribble" gesture, or dedicated tool |

## License

This project is open source. See [LICENSE](LICENSE) for details.
