# YourTube

Android app for YouTube without ads: the mobile YouTube site in a native shell modelled on the YouTube app, with Brave's adblock engine, SponsorBlock, Return YouTube Dislike, DeArrow, background playback, picture-in-picture, a mini player and a lot more.

**Download:** the latest APK is on the [Releases](https://github.com/Wuerfelhusten/YourTube/releases/latest) page. Once installed, the app updates itself.

## Build

Requirements: JDK 17, Android SDK with NDK `28.2.13676358`, Rust with
[cargo-ndk](https://github.com/bbqsrc/cargo-ndk), Node 20+ and git.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Offline adblock lists bundled into the APK
node tools/adblock/build-snapshot.mjs

./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed with keystore.properties if present
```

`keystore.properties` (not in git): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## License

[GPL-3.0](LICENSE)
