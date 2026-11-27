pub mod catalog;
pub mod lang;
pub mod net;
pub mod own;
pub mod ui;
pub mod youtube;

#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "android")]
mod worker;

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: android_activity::AndroidApp) {
    android::run(app);
}
