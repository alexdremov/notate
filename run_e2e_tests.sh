#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e 

echo "🧹 Cleaning up previous test data to ensure a fresh environment..."
adb shell rm -rf "/sdcard/NotateTests" || true
adb shell pm clear com.google.android.documentsui || true
adb shell pm clear com.alexdremov.notate || true

echo "⚡ Disabling emulator animations for maximum test speed..."
adb shell settings put global window_animation_scale 0.0
adb shell settings put global transition_animation_scale 0.0
adb shell settings put global animator_duration_scale 0.0

echo "📦 Building and installing the latest debug APK onto the emulator..."
./gradlew installDebug

echo "🚀 Ensuring Maestro is available in PATH..."
export PATH="$PATH:$HOME/.maestro/bin"

echo "============================================="
echo "🧪 Running Flow 1: Organize Notes (File Management)"
echo "============================================="
# This flow starts with a clean slate, creates a project, handles the Android file picker, and creates folders.
maestro test maestro/02_organize_notes.yaml

echo "============================================="
echo "🧪 Running Flow 2: Create Note and Draw"
echo "============================================="
maestro test maestro/01_create_note_and_draw.yaml

echo "============================================="
echo "🧪 Running Flow 3: Test All UI Menus & Popups"
echo "============================================="
maestro test maestro/03_test_ui_menus.yaml

echo "============================================="
echo "🧪 Running Flow 4: Export to PDF"
echo "============================================="
maestro test maestro/04_export_pdf.yaml

echo "============================================="
echo "✅ All Maestro E2E tests completed successfully!"
echo "📸 Screenshots (ui_menus_tested.png, pdf_export_success.png) saved to the project directory!"
echo "============================================="
