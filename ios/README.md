# DMRandevu Galeri — iOS

The iPhone client, feature-for-feature with the Android one: the same feed of customer videos, the
same three export filters (faces, plates, watermark), the same playback controls, and the same
rule that nothing leaves the app unprotected once a filter is on.

```
open ios/DMRandevuGaleri.xcodeproj
```

Requires Xcode 26 with the Metal toolchain (`xcodebuild -downloadComponent MetalToolchain`).
Deployment target iOS 18.

## Building it on another Mac

Two things the clone does not bring with it. Both are deliberate: one is a pinned upstream
checkout, the other its build product.

```sh
git clone --recurse-submodules https://github.com/beratcelik/DMRandevuGallery.git
cd DMRandevuGallery
ios/Scripts/build-whisper-xcframework.sh   # writes ios/Frameworks/whisper.xcframework
open ios/DMRandevuGaleri.xcodeproj
```

An existing clone that missed the submodule: `git submodule update --init --recursive`. Without
it the script has no sources; without the script the project has no whisper framework and the
build stops at linking.

Then, in Signing & Capabilities, **set your own team and change the bundle identifier**.
`com.dmrandevu.gallery` is registered to the team that built this, and Apple will not let a
second team claim the same identifier — automatic signing fails with no useful explanation
until you do. Anything unique works, e.g. `com.<you>.dmrandevugaleri`.

A free Apple ID is enough to run it on your own phone; the profile lasts seven days and the
first launch needs the certificate trusted once under Settings → General → VPN & Device
Management.

The Core ML models are committed and need nothing.

## How it maps to the Android build

Nearly all of it is a direct port — `BlurTimeline`, `RegionScanner`, `Downloader`, `PlayerManager`
and the view models keep their Android structure, names and reasoning. Where the platform forced a
different answer:

| Android | iOS | Why |
| --- | --- | --- |
| ML Kit face detection | Vision `VNDetectFaceRectanglesRequest` | Built in, so the plate model is the only weight the app ships. |
| ONNX Runtime + `plate-detector.onnx` | Core ML `PlateDetector640/416.mlpackage` | Same weights (see below), and Core ML reaches the Neural Engine. |
| media3 `Transformer` + GLSL shader | `AVAssetExportSession` + a Metal Core Image kernel | The kernel is a line-for-line port of the fragment shader. |
| ExoPlayer `setVideoEffects` | `AVPlayerItem.videoComposition` | Both let the watermark preview run through the very code the export uses. |
| MediaStore | `PHPhotoLibrary`, album "DMRandevu" | |
| `ADD_TO_STORY` intent | `instagram-stories://` + pasteboard | Reels is unreachable on both platforms without Meta's approval, so both save to the library and open Instagram. |

Two behaviours differ on purpose and are worth knowing:

- **Audio is re-encoded.** Android transmuxes it. `AVAssetExportSession`'s presets give no
  passthrough option; at these presets the loss is inaudible and Instagram re-encodes on upload
  anyway.
- **No page-index correction after a delete.** The vertical pager is positioned by page id
  (`conversationKey#mediaIndex`), so removing a conversation above the viewport leaves the visible
  page where it is. The Android build has to step its pager back by the deleted conversation's
  page count by hand.
- **The decision swipe is a paging ScrollView, not a DragGesture.** A `DragGesture` on this
  surface was tried once and reverted: it claimed every vertical swipe and the feed stopped
  scrolling. The horizontal axis instead carries three panes — report, video, dismiss — using the
  same construct that has paged inside the vertical feed all along, so SwiftUI arbitrates the two
  axes itself. Android uses a horizontal pointer-input detector, which has no SwiftUI equivalent.

## The plate model

`Resources/PlateDetector640.mlpackage` and `PlateDetector416.mlpackage` are
`morsetechlab/yolov11-license-plate-detection` (nano), converted to Core ML from the same `.pt`
whose ONNX export the Android app ships — the shipped `plate-detector.onnx` is byte-identical to
the published `license-plate-finetune-v1n.onnx`, so both platforms run the same weights.

The conversion was checked against the ONNX on a real frame: outputs agree to 6e-4, and both find
the same ten anchors above the 0.15 confidence floor at each input size.

**The model is AGPL-3.0, which travels with anything it is shipped in.**

Two fixed sizes rather than one flexible model because the fast/thorough toggle switches between
them; together they are about the same 20 MB the single ONNX would be.

## Tests

```
xcodebuild test -project ios/DMRandevuGaleri.xcodeproj -scheme DMRandevuGaleri \
  -destination 'platform=iOS,id=<device>'
```

Three suites:

- **`BlurTimelineTests`, `CMTimeIntegersTests`** — pure logic, run anywhere.
- **`VideoExportTests`** — measures what the export actually does to a real clip. Needs one: put a
  `sample.mp4` in the host app's Documents directory, or set `DMRANDEVU_SAMPLE_VIDEO`. Use a clip
  with both a visible face and a visible plate. The face case runs on a device only, because
  Vision's face detector answers "could not create inference context" in the simulator.
- **`DMRandevuGaleriUITests`** — drives the real app: the header and the right-edge filter rail, every toggle, tap
  to pause, press to run fast, the scrubber, vertical paging and the caption sheet. Needs a signed-in
  app (or `DMRANDEVU_USER` / `DMRANDEVU_PASS` in the runner environment) and skips otherwise.
  Swiping forward past a customer queues their deletion, exactly as it does in use.

Run the UI tests in the simulator unless the phone is unlocked and awake — device UI automation
fails to start on a locked screen. The photo-library case skips in the simulator, which refuses the
permission however it is granted.
