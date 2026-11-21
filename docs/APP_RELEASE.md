# VoiceBox app releases

Version 1.2.0 starts the update channel for `dev.renakoni.voicebox`. Users install this baseline from GitHub. Later releases use the same application ID and release signing key.

1. Set `voiceboxVersionName` to the new `major.minor.patch` version and increase `voiceboxVersionCode` in `Android/gradle.properties`. Android and the update checker both use the code to order releases.
2. Commit the version change. Build the signed ARM64 APK from the commit to be tagged, passing the production ONNX Runtime AAR to `:app:assembleRelease`. [Build and package verification](../Android/model/gptsovits/README.md)
3. Name the APK `Voicebox-<versionName>-arm64.apk`. Create a draft GitHub release tagged `v<versionName>` at the build commit. Put the user-facing Markdown changelog in the release body and attach the APK, corresponding third-party sources and checksums. Publish the complete stable app release as **Latest**.

The About dialog reads published GitHub Releases, selects the highest stable app tag starting at 1.2.0, and shows its release notes. It reads the version name and code from that tag’s Gradle properties. For a higher version code it downloads the release APK, verifies GitHub’s size and SHA-256, checks its package name and version, and opens the Android installer for confirmation. Component tags such as `ming-runtime-v1` are filtered out.

Publish the ARM64 bridge ZIPs separately using `gptsovits-runtime-v<version>` and `ming-runtime-v<version>`. Each backend starts at v1 and increases its own version when it changes. Attach each ZIP and its catalog, with the catalog download URL pointing to the published ZIP. Keep both component releases off GitHub’s Latest channel. The APK also ships the GPT ZIP for offline installation.
