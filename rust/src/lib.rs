//! The library the Android app loads (`libnpw_android.so`): the shared client
//! core with its UniFFI interface from `npw-ffi`. Re-exporting the crate links
//! its exported FFI functions and UniFFI metadata into this cdylib, from which
//! Gradle generates the Kotlin bindings (library mode).

pub use npw_ffi::*;
