#![cfg(target_os = "android")]

use jni::{
    objects::{JClass, JObject, JString},
    sys::jstring,
    JNIEnv,
};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
};

const UNAVAILABLE: &str = r#"{"refusal":"unavailable"}"#;

fn snapshot(raw: &str, profile: &str, protected: impl FnMut() -> bool) -> String {
    let result = match focus_browser_host::peek::phone_post(raw, profile, protected) {
        Ok(snapshot) => serde_json::json!({ "snapshot": snapshot }),
        Err(refusal) => serde_json::json!({ "refusal": refusal }),
    };
    result.to_string()
}

#[no_mangle]
pub extern "system" fn Java_org_nightingalelab_focus_phone_PeekActivity_fetchNative(
    mut env: JNIEnv,
    _class: JClass,
    raw: JString,
    profile: JString,
    protection: JObject,
) -> jstring {
    let response = match (env.get_string(&raw), env.get_string(&profile)) {
        (Ok(raw), Ok(profile)) => {
            let raw: String = raw.into();
            let profile: String = profile.into();
            catch_unwind(AssertUnwindSafe(|| {
                snapshot(&raw, &profile, || {
                    env.call_method(&protection, "getAsBoolean", "()Z", &[])
                        .and_then(|value| value.z())
                        .unwrap_or(false)
                })
            }))
            .unwrap_or_else(|_| UNAVAILABLE.into())
        }
        _ => return ptr::null_mut(), // JNI may have signaled an exception.
    };
    if env.exception_check().unwrap_or(true) {
        return ptr::null_mut();
    }
    env.new_string(response)
        .map(JString::into_raw)
        .unwrap_or(ptr::null_mut())
}
