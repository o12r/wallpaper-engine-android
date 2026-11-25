//! JNI bridge between the app's GL thread and apolline-scene (MIT OR Apache-2.0).
//!
//! Every function that touches GL must run on the thread that owns the current EGL context
//! (GLSurfaceView's renderer thread, or the wallpaper engine's own GL thread).

use std::ffi::{c_char, c_void, CStr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::Mutex;

use apolline_scene::render::Fit;
use apolline_scene::{Project, Renderer};
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;

#[link(name = "EGL")]
extern "C" {
    fn eglGetProcAddress(name: *const c_char) -> *const c_void;
}

static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

fn set_error(msg: impl Into<String>) {
    if let Ok(mut e) = LAST_ERROR.lock() {
        *e = msg.into();
    }
}

struct Handle {
    gl: glow::Context,
    renderer: Renderer,
    pointer: [f32; 2],
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn load(project: &str, assets: Option<&str>, properties: Option<&str>) -> Result<Handle, String> {
    let mut project =
        Project::open(Path::new(project), assets.map(Path::new)).map_err(|e| e.to_string())?;
    if let Some(json) = properties.filter(|s| !s.trim().is_empty()) {
        let values = serde_json::from_str(json).map_err(|e| format!("properties: {e}"))?;
        project.set_properties(&values).map_err(|e| e.to_string())?;
    }
    let prepared = project.prepare_pointed().map_err(|e| e.to_string())?;
    if let Ok(d) = apolline_scene::project::DIAG.lock() {
        set_error(d.clone());
    }
    let stage = prepared.stage().ok().flatten();
    let gl = unsafe {
        glow::Context::from_loader_function_cstr(|name: &CStr| eglGetProcAddress(name.as_ptr()))
    };
    let mut renderer = unsafe { Renderer::new(&gl, &prepared.scene, &prepared.layers) }
        .map_err(|e| e.to_string())?;
    if let Some(stage) = stage {
        renderer.script(stage);
    }
    Ok(Handle {
        gl,
        renderer,
        pointer: [0.0; 2],
    })
}

/// Opens a project and builds a renderer on the current GL context. Returns 0 on failure;
/// `nativeLastError` then says why.
#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    project: JString,
    assets: JString,
    properties: JString,
) -> jlong {
    let project = jstr(&mut env, &project).unwrap_or_default();
    let assets = jstr(&mut env, &assets);
    let properties = jstr(&mut env, &properties);
    let result = catch_unwind(AssertUnwindSafe(|| {
        load(&project, assets.as_deref(), properties.as_deref())
    }));
    match result {
        Ok(Ok(h)) => Box::into_raw(Box::new(h)) as jlong,
        Ok(Err(e)) => {
            set_error(e);
            0
        }
        Err(_) => {
            set_error("native panic while loading the scene");
            0
        }
    }
}

/// Draws one frame into the bound framebuffer. Returns whether the scene wants another frame
/// right away (it is animated or the parallax is still moving).
#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeDraw(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    width: jint,
    height: jint,
    time: jfloat,
    delta: jfloat,
    px: jfloat,
    py: jfloat,
) -> jboolean {
    if handle == 0 || width <= 0 || height <= 0 {
        return 0;
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    h.pointer = [px.clamp(-1.0, 1.0), py.clamp(-1.0, 1.0)];
    let r = catch_unwind(AssertUnwindSafe(|| {
        let delta = delta.clamp(0.0, 0.25);
        let moving = h.renderer.follow(h.pointer, delta);
        unsafe {
            h.renderer.animate(&h.gl, time, delta);
            h.renderer.draw_timed(
                &h.gl,
                (width as u32, height as u32),
                Fit::Cover,
                time,
                delta,
            );
        }
        moving || h.renderer.animated()
    }));
    r.unwrap_or(false) as jboolean
}

/// 128 floats: 64 left bins then 64 right bins, about 0..1.
#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeSetSpectrum(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
    bins: JFloatArray,
) {
    if handle == 0 {
        return;
    }
    let mut buf = [0f32; 128];
    if env.get_float_array_region(&bins, 0, &mut buf).is_err() {
        return;
    }
    let h = unsafe { &mut *(handle as *mut Handle) };
    let (l, r) = buf.split_at(64);
    let l: [f32; 64] = l.try_into().unwrap();
    let r: [f32; 64] = r.try_into().unwrap();
    h.renderer.set_spectrum(&l, &r);
}

/// Bit 0: pointer, 1: time of day, 2: audio.
#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeNeeds(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let n = unsafe { &*(handle as *const Handle) }.renderer.needs();
    (n.pointer as jint) | ((n.daytime as jint) << 1) | ((n.audio as jint) << 2)
}

#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeDestroy(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let h = unsafe { *Box::from_raw(handle as *mut Handle) };
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe { h.renderer.delete(&h.gl) }));
}

#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeLastError(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    let msg = LAST_ERROR.lock().map(|e| e.clone()).unwrap_or_default();
    env.new_string(msg)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Dry run without GL: what the scene uses that is not drawn yet, one per line. Empty means
/// the scene is fully supported; starts with "ERROR:" when it cannot be opened.
#[no_mangle]
pub extern "system" fn Java_com_weworkshop_app_scene_NativeScene_nativeCheck(
    mut env: JNIEnv,
    _: JClass,
    project: JString,
    assets: JString,
) -> jstring {
    let project = jstr(&mut env, &project).unwrap_or_default();
    let assets = jstr(&mut env, &assets);
    let out = catch_unwind(AssertUnwindSafe(|| -> String {
        let p = match Project::open(Path::new(&project), assets.as_deref().map(Path::new)) {
            Ok(p) => p,
            Err(e) => return format!("ERROR: {e}"),
        };
        let scene = match p.scene() {
            Ok(s) => s,
            Err(e) => return format!("ERROR: {e}"),
        };
        let mut lines: Vec<String> = scene.missing().iter().map(ToString::to_string).collect();
        let (_, skipped) = p.layers(&scene);
        lines.extend(skipped.into_iter().map(|s| format!("layer: {s}")));
        lines.join("\n")
    }))
    .unwrap_or_else(|_| "ERROR: native panic".into());
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
