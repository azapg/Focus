fn main() {
    println!("cargo:rerun-if-env-changed=XEET_VENDOR_DIR");
    if std::env::var_os("CARGO_FEATURE_PEEK").is_none() {
        return;
    }
    let expected = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("peek-assets");
    let configured =
        std::env::var_os("XEET_VENDOR_DIR").and_then(|p| std::fs::canonicalize(p).ok());
    assert_eq!(
        configured,
        std::fs::canonicalize(expected).ok(),
        "Build the Peek host from the repository to use its redistributable xeet assets"
    );
}
