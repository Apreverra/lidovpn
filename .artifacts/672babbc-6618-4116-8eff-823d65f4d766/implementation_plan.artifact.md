# Release Preparation Plan (v1.0.5)

This plan prepares the application for a new version release on GitHub. It includes version incrementing, fixing hardcoded version strings, and generating the necessary metadata for the in-app update system.

## User Review Required

> [!IMPORTANT]
> **Version Number:** I have chosen `1.0.5` as the next version. If you prefer `1.1.0` or another number, please let me know.
> **Update Metadata:** The `update.json` file needs to be uploaded to your GitHub repository (`Apreverra/lidovpn`) for the in-app update check to work.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///C:/Users/Apreverra/AndroidStudioProjects/vpn/app/build.gradle.kts)
- Increment `versionCode` from `4` to `5`.
- Update `versionName` from `"1.0.4"` to `"1.0.5"`.
- Enable `buildConfig` to allow safer version access in code.

---

### Source Code

#### [MODIFY] [SettingsScreen.kt](file:///C:/Users/Apreverra/AndroidStudioProjects/vpn/app/src/main/java/com/lido/vpn/ui/screens/SettingsScreen.kt)
- Update the hardcoded version fallback in the settings card to `"1.0.5"`.
- (Optional) Refactor to use `BuildConfig.VERSION_NAME` for better maintainability.

---

### Release Metadata

#### [NEW] [update.json](file:///C:/Users/Apreverra/AndroidStudioProjects/vpn/update.json)
- Create a JSON file that matches the structure expected by `AppViewModel.checkForUpdates()`.
- This file should be placed in the root of your repository (main branch).

#### [NEW] [CHANGELOG.md](file:///C:/Users/Apreverra/AndroidStudioProjects/vpn/CHANGELOG.md)
- Initialize a changelog to document the improvements in this release.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleRelease` to ensure the project builds correctly with the new versioning.
- Verify the generated `BuildConfig` (if enabled) contains the correct version.

### Manual Verification
- Check the Settings screen in the app to ensure the version is displayed as `1.0.5`.
- Verify that the `update.json` structure matches the `VpnUpdateInfo` data class.
