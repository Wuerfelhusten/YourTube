//! JNI bridge between TrueTube (Kotlin) and Brave's adblock-rust engine.
//!
//! Kotlin side: `com.moddingforge.truetube.adblock.NativeAdblock` (an `object` with
//! `@JvmStatic external` functions). An engine is passed around as an opaque `jlong` handle.
//!
//! Thread safety: the crate is built without the `single-thread` feature, so `Engine` is
//! `Send + Sync` and the read-only calls (`check`, `cosmetic`, `hiddenClassIds`) may run on
//! several WebView threads at once. `destroy` must not race with them; Kotlin guards it
//! with a read/write lock.

use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JIntArray, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE};
use jni::JNIEnv;
use serde::Serialize;

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

const ADBLOCK_VERSION: &str = "0.13.5";

// --- helpers ---------------------------------------------------------------------------------

/// Runs `f`, turning errors and panics into a Java RuntimeException and returning `default`.
fn guarded<T>(env: &mut JNIEnv, default: T, f: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    let outcome = catch_unwind(AssertUnwindSafe(|| f(env)));
    let message = match outcome {
        Ok(Ok(value)) => return value,
        Ok(Err(e)) => e.to_string(),
        Err(panic) => panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic in adblock engine".into()),
    };
    let _ = env.throw_new("java/lang/RuntimeException", format!("adblock: {message}"));
    default
}

fn engine<'a>(handle: jlong) -> Result<&'a Engine> {
    if handle == 0 {
        return Err("engine handle is null".into());
    }
    // SAFETY: handle was produced by Box::into_raw in into_handle and is only freed by
    // nativeDestroy, which Kotlin never runs concurrently with other calls.
    Ok(unsafe { &*(handle as *const Engine) })
}

fn into_handle(engine: Engine) -> jlong {
    Box::into_raw(Box::new(engine)) as jlong
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String> {
    Ok(env.get_string(s)?.into())
}

fn string_array(env: &mut JNIEnv, arr: &JObjectArray) -> Result<Vec<String>> {
    let len = env.get_array_length(arr)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = env.get_object_array_element(arr, i)?;
        let js = JString::from(obj);
        out.push(string(env, &js)?);
        env.delete_local_ref(js)?;
    }
    Ok(out)
}

fn new_jstring(env: &mut JNIEnv, s: &str) -> Result<jstring> {
    Ok(env.new_string(s)?.into_raw())
}

fn load_resources(engine: &mut Engine, path: &str) -> Result<usize> {
    let data = std::fs::read_to_string(path)?;
    let resources: Vec<Resource> = serde_json::from_str(&data)?;
    let count = resources.len();
    engine.use_resources(resources);
    Ok(count)
}

// --- JNI functions ---------------------------------------------------------------------------

/// Builds an engine from filter list files.
///
/// - `listPaths[i]` is parsed with permission mask `permissionMasks[i]` (Brave uses 1 for its
///   default components: "trusted uBO resources").
/// - `resourcesPath` (nullable): resources.json with scriptlets/redirects.
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeCreate<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    list_paths: JObjectArray<'l>,
    permission_masks: JIntArray<'l>,
    resources_path: JString<'l>,
    debug: jboolean,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let paths = string_array(env, &list_paths)?;
        let mut masks = vec![0i32; paths.len()];
        if !masks.is_empty() {
            env.get_int_array_region(&permission_masks, 0, &mut masks)?;
        }

        let mut set = FilterSet::new(debug != JNI_FALSE);
        for (path, mask) in paths.iter().zip(masks) {
            let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
            let opts = ParseOptions {
                permissions: PermissionMask::from_bits(mask as u8),
                ..Default::default()
            };
            set.add_filter_list(text, opts);
        }

        let mut engine = Engine::new_with_filter_set(set);
        if !resources_path.is_null() {
            let path = string(env, &resources_path)?;
            load_resources(&mut engine, &path)?;
        }
        Ok(into_handle(engine))
    })
}

/// Builds an engine from inline rules (used for self-tests).
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeCreateFromRules<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    rules: JString<'l>,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let text = string(env, &rules)?;
        let mut set = FilterSet::new(false);
        set.add_filter_list(text, ParseOptions::default());
        Ok(into_handle(Engine::new_with_filter_set(set)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: see `engine()`; Kotlin calls this exactly once per handle.
        unsafe { drop(Box::from_raw(handle as *mut Engine)) };
    }
}

#[derive(Serialize)]
struct CheckResult<'a> {
    block: bool,
    important: bool,
    redirect: Option<&'a str>,
    rewritten_url: Option<&'a str>,
}

/// Checks a network request. Returns `null` if nothing matched (the common case, no JSON
/// allocation), otherwise `{"block":bool,"important":bool,"redirect":"data:...","rewritten_url":...}`.
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeCheck<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    url: JString<'l>,
    source_url: JString<'l>,
    request_type: JString<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let engine = engine(handle)?;
        let url = string(env, &url)?;
        let source = string(env, &source_url)?;
        let kind = string(env, &request_type)?;
        let request = match Request::new(&url, &source, &kind, "") {
            Ok(r) => r,
            Err(_) => return Ok(std::ptr::null_mut()), // unparseable URL: let it through
        };
        let result = engine.check_network_request(&request);
        let block = result.should_block();
        if !block && result.redirect.is_none() && result.rewritten_url.is_none() {
            return Ok(std::ptr::null_mut());
        }
        let json = serde_json::to_string(&CheckResult {
            block,
            important: result.important,
            redirect: result.redirect.as_deref(),
            rewritten_url: result.rewritten_url.as_deref(),
        })?;
        new_jstring(env, &json)
    })
}

/// Cosmetic resources for a page URL, as JSON (`UrlSpecificResources`: hide_selectors,
/// procedural_actions, exceptions, injected_script, generichide).
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeCosmetic<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    url: JString<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let engine = engine(handle)?;
        let url = string(env, &url)?;
        let resources = engine.url_cosmetic_resources(&url);
        let json = serde_json::to_string(&resources)?;
        new_jstring(env, &json)
    })
}

/// Generic hide selectors for newly seen classes/ids. `exceptionsJson` is the `exceptions`
/// array from `nativeCosmetic`. Returns a JSON array of selectors.
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeHiddenClassIds<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    classes: JObjectArray<'l>,
    ids: JObjectArray<'l>,
    exceptions_json: JString<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let engine = engine(handle)?;
        let classes = string_array(env, &classes)?;
        let ids = string_array(env, &ids)?;
        let exceptions: HashSet<String> = serde_json::from_str(&string(env, &exceptions_json)?)?;
        let selectors = engine.hidden_class_id_selectors(&classes, &ids, &exceptions);
        let json = serde_json::to_string(&selectors)?;
        new_jstring(env, &json)
    })
}

/// Serializes the engine (cache for fast cold starts; format tied to the adblock version).
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeSerialize<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let bytes = engine(handle)?.serialize();
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
    })
}

/// Restores an engine from `nativeSerialize` output. Resources are loaded again from
/// `resourcesPath` (nullable) to be safe.
#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeDeserialize<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    data: JByteArray<'l>,
    resources_path: JString<'l>,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let bytes = env.convert_byte_array(&data)?;
        let mut engine = Engine::default();
        engine
            .deserialize(&bytes)
            .map_err(|e| format!("deserialize failed: {e:?}"))?;
        if !resources_path.is_null() {
            let path = string(env, &resources_path)?;
            load_resources(&mut engine, &path)?;
        }
        Ok(into_handle(engine))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_moddingforge_truetube_adblock_NativeAdblock_nativeVersion<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| new_jstring(env, ADBLOCK_VERSION))
}
