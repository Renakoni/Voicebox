# VoiceBox app releases

Version 2.0.0 starts the update channel for `dev.renakoni.voicebox`. Users install this baseline from GitHub. Later releases use the same application ID and release signing key.

1. Set `voiceboxVersionName` to the new `major.minor.patch` version and increase `voiceboxVersionCode` in `Android/gradle.properties`. Android and the update checker both use the code to order releases.
2. Commit the version change. Build the signed ARM64 APK from the commit to be tagged, passing the production ONNX Runtime AAR to `:app:assembleRelease`. [Build and package verification](../Android/model/gptsovits/README.md)
3. Run `python tools/prepare_app_update.py artifacts/app-release` from the repository root. It copies the APK and generates `voicebox-update.json` with the version recorded by Gradle and the size and SHA-256 of that exact APK.
4. Create a draft GitHub release tagged `v<versionName>` at that commit. Attach both generated files, the corresponding third-party sources and checksums. Publish the complete stable app release as **Latest**. Keep backend-only releases and prereleases off the Latest app channel.

The About page reads `releases/latest/download/voicebox-update.json` when the user taps **Check for updates**. For a higher version code it downloads the APK from the matching version tag, verifies the bytes, digest, package name and version, then opens the Android installer on request. The installer checks the signing identity and asks the user to confirm.
