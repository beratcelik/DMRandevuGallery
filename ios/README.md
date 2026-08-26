# DMRandevu Galeri — iOS

The iPhone client, feature-for-feature with the Android one: the same feed of customer videos, the
same three export filters (faces, plates, watermark), the same playback controls, and the same
rule that nothing leaves the app unprotected once a filter is on.

```
open ios/DMRandevuGaleri.xcodeproj
```

Requires Xcode 26 with the Metal toolchain (`xcodebuild -downloadComponent MetalToolchain`).
Deployment target iOS 18.

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
- **No page-index correction after a delete.** The vertical pager is positioned by conversation
  key, so removing a conversation above the viewport leaves the visible one where it is. The
  Android build had to step the pager back by hand.

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

`BlurTimelineTests` is pure logic and runs anywhere. `VideoExportTests` needs a clip to work on and
skips without one — put a `sample.mp4` in the host app's Documents directory, or set
`DMRANDEVU_SAMPLE_VIDEO`. Use a clip with both a visible face and a visible plate.

The face test only runs on a device: Vision's face detector answers "could not create inference
context" in the simulator. Everything else, the plate model included, runs in either.
