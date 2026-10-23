# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Cheto Gallery (store id `ai.cheto.gallery`, code namespace `com.dmrandevu.gallery`) is an Android app (Kotlin, Compose) and an iOS app (SwiftUI) for an existing DMRandevu server's admin media gallery. The apps show a full-screen feed of the videos customers sent over Instagram DM, Messenger and WhatsApp, and export them with optional filters. There is no backend in this repo. Product behaviour and the reasoning behind it are in `README.md` (top level) and `ios/README.md`. Read the relevant section before changing behaviour, because most rules there exist to fix a bug someone actually hit.

## Destructive actions: read before driving a device

- **Moving forward to a different customer deletes the customer you left.** About 5 s after the feed settles on another customer's video, the app calls `DELETE /admin/conversation/:salonId/:clientId` (decided by `onSettled` in `ui/FeedPages.kt` / `UI/FeedPages.swift`). That erases the whole conversation from the server's Redis, and only a server backup can bring it back. The delete sends `seenUntil` (the customer's `lastCustomerAt` as loaded), and the server refuses it with a 409 if the customer wrote since. That protects new messages, not the conversation you swiped past. When you drive a phone or emulator against a real server (`adb shell input swipe`, UI tests), only swipe between videos of the same customer. Taps, long-presses and screenshots are safe.
- On the `trafik_cezasi` account, a right swipe on an Instagram video **reports it as a traffic violation to real officers**, after a 3 s undo window.
- `SettingsUITests` changes the password and signs out. It skips itself unless `DMRANDEVU_MOCK_SERVER` points at a local or private-network address. Keep that guard.

## Commands

### Android

`gradle.properties` pins `org.gradle.java.home` to Android Studio's bundled JBR on macOS.

```sh
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                                                  # all JVM unit tests
./gradlew :app:testDebugUnitTest --tests 'com.dmrandevu.gallery.ui.FeedPagesTest' # one class
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dmrandevu.gallery.media.censor.CensorAudioExportTest
./gradlew :app:bundleRelease        # signed only if ~/.cheto-signing/keystore.properties exists
adb reverse tcp:3111 tcp:3111       # then use http://127.0.0.1:3111 as the server address
```

Instrumented tests in `app/src/androidTest` need clips pushed by hand to `/data/local/tmp/` (customer videos are not committed). The README's "Tests" section has the file names. Raise `versionCode` in `app/build.gradle.kts` for every Play upload. Store texts and graphics are in `store/android/`.

### iOS

You need Xcode 26 with the Metal toolchain. On a fresh clone, or after updating `third_party/whisper.cpp`:

```sh
git submodule update --init --recursive
ios/Scripts/build-whisper-xcframework.sh   # writes ios/Frameworks/whisper.xcframework (not committed; linking fails without it)
```

```sh
xcodebuild test -project ios/DMRandevuGaleri.xcodeproj -scheme DMRandevuGaleri \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:DMRandevuGaleriTests/FeedPagesTests
```

- The simulator must run iOS 18 or later, the app's minimum. On this Mac the iPhone 15 simulators run iOS 17.5, and `xcodebuild` reports them as "Unable to find a device".
- Most unit tests use XCTest. `FeedPagesTests` and `DecisionQueueTests` use Swift Testing (`@Test`). `-only-testing` works for both.
- The Xcode project uses file-system-synchronized groups. A new `.swift` file under `ios/DMRandevuGaleri/` is picked up automatically, with no `project.pbxproj` edit needed.
- Test environment variables:
  - `DMRANDEVU_SAMPLE_VIDEO`: a clip for `VideoExportTests`. The face case runs on a device only.
  - `DMRANDEVU_USER`, `DMRANDEVU_PASS`, `DMRANDEVU_ACCOUNT`: sign-in details for `GalleryUITests`.
  - `DMRANDEVU_MOCK_SERVER`: the local stand-in server for `SettingsUITests`.
- Run UI tests in the simulator unless the phone is unlocked and awake.

## Architecture

### Two platforms, one design

The iOS app is a port of the Android one that keeps the same file names, type names, structure and reasoning. For example, `ui/FeedPages.kt` pairs with `UI/FeedPages.swift`, and `player/PlayerManager.kt` with `Player/PlayerManager.swift`. Unit tests pair up the same way: `app/src/test/...Test.kt` with `ios/DMRandevuGaleriTests/...Tests.swift`.

A behaviour change normally lands in **both platforms in the same commit**, together with its tests and, when it is user-visible, the README. The table in `ios/README.md` lists the deliberate platform differences: Vision instead of ML Kit, Core ML instead of ONNX, a Metal Core Image kernel instead of GLSL, audio re-encoded instead of transmuxed, and a paging ScrollView instead of a drag detector for the decision swipe.

### Layers

The paths below are Android paths. iOS mirrors them under `ios/DMRandevuGaleri/`.

- **`ServiceLocator`** holds hand-rolled singletons; there is no DI framework. `MainActivity` switches between Login and Gallery based on the resolved Instagram id; there is no navigation library.
- **`data/`**:
  - `GalleryRepository` talks to DMRandevu's `/admin/*` endpoints with a persisted session cookie. The OkHttp client sets an explicit User-Agent because the login route rejects bot-like ones.
  - `IhbarRepository` calls `/admin/ihbar/*`, and the server forwards those calls to Trafik İhbar. The phone holds no İhbar address or token.
  - `IhbarAccount` limits the decision axis to `trafik_cezasi`, and only for Instagram-channel videos.
- **`ui/`**:
  - `GalleryViewModel` owns the single source of truth, `items` (a list of conversations). `feed` is *derived* from it: one page per video, with page id `conversationKey#mediaIndex`. İhbar marks and `ManualMarks` use the same key, so don't invent another id format.
  - `GalleryViewModel` reads `ServiceLocator` at construction, so it can't be built in a JVM test. Rules that matter therefore live in pure, separately tested files:
    - `FeedPages`: settling on a page leads to a delete decision.
    - `DecisionQueue`: the swipe-decision undo ledger. Its 3 s window is deliberately shorter than the 5 s delete grace period.
    - `SwipeDecision`.
  - Put any new rule that can delete or report something in a pure file like these, with tests.
- **`player/`**:
  - `PlayerManager` keeps a pool of 3 ExoPlayers keyed by page id, not by index.
  - `SlotTable` is the pure LRU bookkeeping for that pool, kept free of ExoPlayer; two black-screen bugs came from this logic.
  - Once a player has been given video effects (the watermark preview), it is committed to the GL pipeline. It must not pre-buffer, and it is replaced rather than reused.
- **`media/`**: `Downloader` calls `VideoExporter`, which applies `ExportOptions`. The options are:
  - face blur: ML Kit on Android, Vision on iOS
  - plate blur: `assets/models/plate-detector.onnx` on Android, `PlateDetector640/416.mlpackage` on iOS. Both use the same weights, which are licensed **AGPL-3.0**.
  - a wandering watermark
  - a profanity beep (`censor/`)

  **Invariant:** when any filter is requested and the export fails, it throws. It never falls back to delivering the unfiltered video. The watermark preview runs through the same code as the export.
- **`media/censor/`** runs whisper.cpp: through the `:whisper` Gradle module (JNI) on Android and through the xcframework on iOS.
  - The `:whisper` module forces `CMAKE_BUILD_TYPE=Release` even for debug builds. Without it, one recognition pass takes ten minutes.
  - Recognition runs in several independent passes (`no_context`), which `WordAlignment` reconciles; then `TimingRefiner` re-recognises a snippet around each hit, and vocal separation runs only inside the censor windows.
  - About 320 MB of models download at runtime into `filesDir/censor-models` and are checked by SHA-256. They are never bundled.
  - The README section "Küfür filtresi" explains why each pass exists.
- **`InstagramSharing`** handles the Stories and Reels hand-off using Meta app id `1059486250258693` (`META_APP_ID` / `metaAppID`). No hand-off accepts a caption, so the generated caption travels on the clipboard. The README "Notes" section covers the Live-mode requirement, and why a rejection can't be detected from inside the app.

### Pinned and fixed choices

- media3 is pinned to 1.8.x because `ExperimentalFrameExtractor`, which the face scan uses, was removed in 1.10.
- The Android build is `arm64-v8a` only.
- The first-run tour shows again only when `TOUR_REVISION` / `tourRevision` changes (stored as `tour_shown_build`), not on version bumps. Change it only when the gestures change.
- Use locale-independent case conversion on identifiers: `lowercase()` with no argument in Kotlin, `caseInsensitiveCompare` in Swift. A Turkish locale turns `I` into dotless `ı`, and the match then fails silently. `IhbarAccount` has the full story.

## Conventions

- User-facing text is **Turkish only**. On Android it lives in `app/src/main/res/values/strings.xml`; on iOS it lives in `ios/DMRandevuGaleri/Resources/Strings.swift` (`enum Strings`). UI tests assert on the literal Turkish copy (for example the `Copy` enum in `GalleryUITests`), so changing a string can break them.
- Comments explain *why*, often at length. Older code writes them in English; newer code writes them in Turkish, with `NEDEN …` ("why …") headings. Match the language and density of the file you're editing.
- Commit messages are a single English sentence describing the behaviour. A commit that touches only one platform is usually prefixed `iOS:` or `Android:`.
