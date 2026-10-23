# Android CI and signed releases

`.github/workflows/android-release.yml` is the authoritative Android build. A push to `main` validates the app, signs an APK, and publishes its GitHub Release **in the same workflow run**. Manual dispatch on `main` does the same. Pull requests run validation only and never receive release secrets. Branches other than `main` cannot sign or publish.

## What is checked

On Ubuntu 24.04 with Temurin JDK 17, Gradle 9.3.1, Android platform 36.1 and build-tools 36.0.0, CI runs `testDebugUnitTest lintDebug assembleDebug`. Tests cover QR approval, TLS 1.3 identity checks, durable reconnection and revocation, secure route discovery for saved identities only, and byte-for-byte transfers against a source-pinned Rust TLS receiver. The cross-application test is required to run without skips. CI also rejects cleartext endpoints and the removed pairing surface in production Android sources. This is not a physical-phone, camera or connected-emulator test. The export lacks complete Gradle Wrapper scripts/JAR, so CI installs the exact Gradle version from the existing wrapper properties instead of relying on a global runner version.

Only after those checks succeed does a protected `android-release` Environment supply the signing identity to `assembleRelease`. The pipeline verifies the APK certificate, application ID, version and launcher using `apksigner` and `aapt` before uploading any distribution artifact. Signing files live only under the runner's temporary directory and are removed with `if: always()`. Signed builds do not write Gradle caches. Publication is a separate dependent job; it alone has `contents: write`.

## Versions and downloads

## Versions and downloads

For workflow run number `N`, the APK version is `1.0.N`, Android `versionCode` is `N + 1`, and the release tag is `android-v1.0.N`. Gaps from PRs or failed runs are intentional. Versions are injected through environment variables; CI never commits a version bump or starts a follow-up workflow. Keep the workflow filename and its run-number history; replacing/resetting it requires choosing an offset greater than every already-distributed versionCode before publishing again.

The durable Release assets are:

- `AuraShare-1.0.N.apk`: directly installable APK, requiring Android 7.0 / API 24 or newer.
- `SHA256SUMS`: SHA-256 checksums of the APK and metadata.
- `release.json`: source commit, originating run ID, version and public certificate fingerprint.

No AAB/Play Store publishing has been configured. GitHub Releases are separate from Google Play. Validation reports are retained as Actions artifacts for 14 days and signed build artifacts for 30 days; Release assets do not share those artifact expiry limits. Reruns use the same version and never overwrite an already-published APK; an integrity difference fails rather than silently replacing a binary. Incomplete draft releases can be resumed.

## Signing identity and compatibility

The GitHub Environment is restricted to the `main` branch. There is no manual reviewer gate because releases were requested to be fully automatic; adding required reviewers changes that behavior. Repository write access and workflow changes must therefore be treated as privileged. Pull requests must be reviewed before merging into `main`.

The Environment holds `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Do not move them to repository-level secrets or commit a keystore/password. The only committed certificate data is its **public** SHA-256 fingerprint in `.github/android-release-certificate.sha256`. The release key was newly created after inspection found no existing GitHub releases, build runs, keystore or distributed APK in this repository/history. GitHub secrets cannot be downloaded back for backup: arrange a separately secured, owner-controlled signing-key backup through a trusted administrative process before relying on long-term distribution. Never expose the key through CI logs or artifacts.

The package name remains `com.aistudio.aurashare.vzkqnm`. An independently distributed AI Studio/debug build signed with a different key cannot be updated in place by this release. Such an installation needs the original private signing key to preserve update compatibility, or the old app must be uninstalled first (which may delete its local data). No earlier distributed binary outside this repository was available for certificate comparison. Future CI releases share the protected key and can update one another when their versionCode increases.

The existing AI Studio signing environment names remain supported as fallback inputs for development. Missing `google-services.json` remains configured as a warning; configuring Firebase features later requires the appropriate Firebase project configuration.
