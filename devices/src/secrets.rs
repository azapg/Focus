// Windows credentials stay bound to this Windows account using DPAPI.
#[cfg(windows)]
fn crypt(input: &[u8], protect: bool) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::{Foundation::LocalFree, Security::Cryptography::*};
    let source = CRYPT_INTEGER_BLOB {
        cbData: input.len() as u32,
        pbData: input.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        if protect {
            CryptProtectData(
                &source,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &source,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        return Err("Could not access the Windows device credential. Pair the TV again.".into());
    }
    let bytes =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe { LocalFree(output.pbData as _) };
    Ok(bytes)
}
#[cfg(all(not(windows), feature = "portable-test-harness"))]
fn crypt(input: &[u8], _: bool) -> Result<Vec<u8>, String> {
    // Explicit opt-in for disposable Linux test directories only. Never a production credential store.
    Ok(input.to_vec())
}
#[cfg(all(not(windows), not(feature = "portable-test-harness")))]
fn crypt(_: &[u8], _: bool) -> Result<Vec<u8>, String> {
    Err("Device credentials require the Windows credential store".into())
}
pub fn protect(value: &str) -> Result<String, String> {
    let bytes = crypt(value.as_bytes(), true)?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
pub fn unprotect(value: &str) -> Result<String, String> {
    if !value.len().is_multiple_of(2) || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Invalid saved credential".into());
    }
    let bytes: Vec<u8> = (0..value.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&value[i..i + 2], 16).unwrap())
        .collect();
    let bytes = crypt(&bytes, false)?;
    String::from_utf8(bytes).map_err(|_| "Invalid saved credential".into())
}
