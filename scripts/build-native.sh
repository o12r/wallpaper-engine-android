#!/usr/bin/env bash
# Builds libwe_scene.so (Rust, apolline-scene) for the ABIs the app ships into app/src/main/jniLibs.
# Needs: rustup targets aarch64-linux-android + x86_64-linux-android, cargo-ndk, Android NDK.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -W 2>/dev/null || pwd)"
export PATH="$HOME/.cargo/bin:$PATH"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-C:/Android/Sdk/ndk/27.2.12479018}"
export LIBCLANG_PATH="${LIBCLANG_PATH:?set LIBCLANG_PATH to a folder with libclang.dll (e.g. pip install libclang)}"
# bindgen (rquickjs-sys) needs the compiler builtin headers such as stdbool.h
export CPATH="${CPATH:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/18/include}"
cd "$ROOT/native/scene"
cargo ndk --platform 29 -t arm64-v8a -t x86_64 -o "$ROOT/app/src/main/jniLibs" build --release
ls -l "$ROOT"/app/src/main/jniLibs/*/libwe_scene.so
