# Onyx Boox SDK: The Ultimate Guide
**Status:** Verified & Production-Ready
**Device Target:** NoteAir 4C (Android 11+)
**SDK Version:** `onyxsdk-pen:1.5.0.4`

This document serves as the authoritative reference for implementing high-performance stylus input on Onyx Boox devices, distilling findings from reverse-engineering the native app and successful integration into `Notate`.

---

## 1. Core Input Architecture (`TouchHelper`)
The `TouchHelper` class is the primary bridge between the Android View system and the E-Ink hardware driver.

### Initialization
```kotlin
val touchHelper = TouchHelper.create(view, object : RawInputCallback() {
    override fun onBeginRawDrawing(isEraser: Boolean, point: TouchPoint) { ... }
    override fun onEndRawDrawing(isEraser: Boolean, point: TouchPoint) { ... }
    override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) { ... }
    // ... implement other methods as no-ops if not needed
})
```

### Essential Configuration
To ensure the driver behaves correctly, apply these settings immediately after initialization or tool changes:

```kotlin
touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL) // or other styles
touchHelper.setStrokeWidth(pixelWidth)
touchHelper.setStrokeColor(Color.BLACK) // Use solid Black/Grayscale for E-Ink
touchHelper.setRawDrawingRenderEnabled(true) // Enable hardware ink
```

---

## 2. Hardware-Accelerated Eraser (The "Lasso" Recipe)
Achieving a visible, hardware-rendered dashed line for selection tools requires a specific sequence of undocumented calls. The native driver defaults to a solid line unless explicitly configured.

**Correct Implementation:**
```kotlin
// 1. Enable Eraser Drawing Channel (Critical for Stylus Button)
Device.currentDevice().setEraserRawDrawingEnabled(true)
touchHelper.setRawDrawingRenderEnabled(true)

// 2. Set Style to DASH (Constant 5)
touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_DASH)

// 3. Force Color to Black (Invisible otherwise)
touchHelper.setStrokeColor(Color.BLACK)

// 4. Set Width to 5.0f (Native Standard)
touchHelper.setStrokeWidth(5.0f)

// 5. Configure Dash Pattern (Gap/Length)
// NOTE: Must be a SINGLE element array [5.0f], not [5.0f, 5.0f]
Device.currentDevice().setStrokeParameters(TouchHelper.STROKE_STYLE_DASH, floatArrayOf(5.0f))
```

**Teardown:**
When switching back to a pen, you **must** disable the eraser channel:
```kotlin
Device.currentDevice().setEraserRawDrawingEnabled(false)
```

---

## 3. Screen Refresh Strategy (Ghosting Control)
Never rely on global timers or `invalidate()`. The most effective strategy matches the native app's "Clean Up on Lift" approach.

### The "Regional GC" Pattern
Refresh **only** the area modified by the last stroke, and only after the user lifts the pen.

```kotlin
// In onEndRawDrawing:
val dirtyRect = RectF(strokeBounds)
dirtyRect.inset(-10f, -10f) // Add safety padding

// Execute Partial GC Refresh
EpdController.invalidate(
    view,
    dirtyRect.left.toInt(), dirtyRect.top.toInt(),
    right.toInt(), bottom.toInt(),
    UpdateMode.GC // GC removes all ghosting without full-screen flash
)
```

---

## 4. Ultra-Low Latency "Turbo" Mode
Standard SDK methods (`EpdDeviceManager`) are insufficient for 60fps UI animations (sidebars, scrolling). You must use reflection to access the "Turbo" and "Transient Update" APIs used by the system UI.

### Implementation (`EpdFastModeController`)
```kotlin
// Access hidden methods via Reflection
val setEpdTurbo = EpdController::class.java.getMethod("setEpdTurbo", Boolean::class.javaPrimitiveType)
val applyTransient = EpdController::class.java.getMethod("applyTransientUpdate", UpdateMode::class.java)
val clearTransient = EpdController::class.java.getMethod("clearTransientUpdate", Boolean::class.javaPrimitiveType)

// Enter Fast Mode (e.g., on Touch Down / Scroll Start)
setEpdTurbo.invoke(null, true)
applyTransient.invoke(null, UpdateMode.ANIMATION)

// Exit Fast Mode (e.g., on Touch Up / Scroll End)
clearTransient.invoke(null, true) // Restore previous high-quality mode
```

---

## 5. Native Pen Wrappers (1.5.0.4+)
The 1.5.x SDK refactored the pen rendering classes. Direct instantiation of `NeoFountainPen` is deprecated. Use the `*Wrapper` static utilities.

### Mapping
| Old Class | New Wrapper Class |
| :--- | :--- |
| `NeoFountainPen` | `NeoFountainPenWrapper` |
| `NeoBrushPen` | `NeoBrushPenWrapper` |
| `NeoMarkerPen` | `NeoMarkerPenWrapper` |
| `NeoCharcoalPenV2` | `NeoCharcoalPenV2Wrapper` |

### Usage Example
```kotlin
// Render a stroke to software canvas (Persistence)
NeoFountainPenWrapper.drawStroke(
    canvas,
    paint,
    touchPoints,
    width,
    pressure,
    limit,
    true // useNative = true for hardware-aligned smoothing
)
```

**Warning:** Native wrappers often ignore the `Canvas` matrix (Pan/Zoom). You must manually transform points to screen space (Identity matrix), draw, and then restore the canvas.

---

## 6. Important Private Constants

### Stroke Styles (`TouchHelper`)
| Name | Value | Description |
| :--- | :--- | :--- |
| `STROKE_STYLE_PENCIL` | `0` | Standard aliased line |
| `STROKE_STYLE_FOUNTAIN` | `1` | Pressure-sensitive, smoothed |
| `STROKE_STYLE_MARKER` | `2` | Thick, semi-transparent (simulated) |
| `STROKE_STYLE_DASH` | `5` | **Hardware Eraser / Selection Line** |

### Display Schemes (`EpdController`)
Use `EpdController.setDisplayScheme(int)` to optimize global device behavior.
| Name | Value | Usage |
| :--- | :--- | :--- |
| `SCHEME_NORMAL` | `1` | Reading / Static UI |
| `SCHEME_SCRIBBLE` | `3` | **Note Taking** (Minimizes input latency) |
| `SCHEME_APP_ANIM` | `4` | Video / heavy scrolling |

### Pen States (`EpdController.setScreenHandWritingPenState`)
| Name | Value | Usage |
| :--- | :--- | :--- |
| `PEN_START` | `1` | Initialize driver |
| `PEN_DRAWING` | `2` | Active input |
| `PEN_PAUSE` | `3` | **Pause during Pan/Zoom** (Prevents stray marks) |
| `PEN_ERASING` | `4` | Eraser mode |

---

## 7. Advanced Custom Hardware Rendering (`NeoPen`)
To bypass the limited `TouchHelper` stroke styles and achieve fully customizable hardware rendering (e.g., custom pressure curves, min/max width), use `NeoPen` directly via `NeoPenNative`.

### The Mechanism
Instead of relying on `TouchHelper`'s internal rendering, we disable it and drive the `NeoPen` native render pipeline manually using raw input events.

### Implementation Recipe

1. **Type Mapping**:
   `NeoPenNative` uses a different `TouchPoint` class than `TouchHelper`.
   - `TouchHelper`: `com.onyx.android.sdk.data.note.TouchPoint`
   - `NeoPenNative`: `com.onyx.android.sdk.base.data.TouchPoint`

2. **Disable Default Rendering**:
   Configure `TouchHelper` to report input but **not** render.
   ```kotlin
   touchHelper.setRawDrawingRenderEnabled(false)
   ```

3. **Configure `NeoPen`**:
   Create a `NeoPen` instance with your specific parameters. 
   **CRITICAL**: You must set `maxTouchPressure` (default is 1.0f, which breaks hardware rendering).
   ```kotlin
   val config = NeoPenConfig().apply {
       type = NeoPenConfig.NEOPEN_PEN_TYPE_FOUNTAIN
       width = currentTool.width
       minWidth = 0.5f
       pressureSensitivity = 0.8f
       color = Color.BLACK
       maxTouchPressure = 4096f // Match hardware (e.g. EpdController.getMaxTouchPressure())
   }
   // NeoPenNative is a Kotlin object; access methods directly from the class name.
   val neoPen = NeoPenNative.createPen(config.type, config)
   ```

4. **Drive the Hardware Render**:
   In your `TouchHelper` callback, pass converted points to `NeoPenNative`.
   ```kotlin
   override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
       val basePoint = toBasePoint(point)
       NeoPenNative.onPenMove(neoPen, listOf(basePoint), basePoint, true)
   }

   override fun onBeginRawDrawing(isEraser: Boolean, point: TouchPoint) {
       NeoPenNative.onPenDown(neoPen, toBasePoint(point), true)
   }

   override fun onEndRawDrawing(isEraser: Boolean, point: TouchPoint) {
       NeoPenNative.onPenUp(neoPen, toBasePoint(point), true)
   }
   ```

5. **Cleanup**:
   Destroy the pen when changing tools or destroying the view.
   ```kotlin
   NeoPenNative.destroyPen(neoPen)
   ```

---

## 8. `NeoPenConfig` Property Reference
The `NeoPenConfig` class (used by `NeoPenNative`) fine-tunes the native rendering engines.

### Core Configuration
| Property | Type | Description |
| :--- | :--- | :--- |
| `type` | `Int` | Engine ID (2: Fountain, 5: CharcoalV2, 7: Pencil, 8: Ballpoint). |
| `color` | `Int` | ARGB color. |
| `width` | `Float` | Maximum stroke width. |
| `minWidth` | `Float` | Minimum width (at zero pressure). |
| `maxTouchPressure`| `Float` | Hardware normalization (e.g., 4096.0f). **Must be set.** |

### Dynamic Response
| Property | Type | Description |
| :--- | :--- | :--- |
| `pressureSensitivity` | `Float` | `0.0`-`1.0`. Width response to pressure. |
| `velocitySensitivity` | `Float` | `0.0`-`1.0`. Speed-dependent thinning. |
| `smoothLevel` | `Float` | Path smoothing intensity. |

### Stamp & Texture
| Property | Type | Description |
| :--- | :--- | :--- |
| `brushShape` | `Int` | 0: Circle, 1: Ellipse, 2: Rectangle. |
| `brushSpacing` | `Float` | Step size between stamps (e.g., 0.1). |
| `tiltEnabled` | `Boolean` | Enables Wacom tilt support. |
| `tiltScale` | `Float` | Multiplier for tilt-induced width changes. |

---

## 9. Common Pitfalls & Fixes
- **Unresolved `INSTANCE`**: `NeoPenNative` is a Kotlin `object`. In Kotlin, call `NeoPenNative.method()`. In Java, call `NeoPenNative.INSTANCE.method()`.
- **Incompatible `TouchPoint`**: Always convert between `sdk.data.note.TouchPoint` and `sdk.base.data.TouchPoint`.
- **Invisible Ink**: Ensure `maxTouchPressure` is set to a realistic value (> 1.0f) and `setRawDrawingRenderEnabled(false)` is called on `TouchHelper` if using `NeoPenNative` to avoid double-rendering or conflicts.