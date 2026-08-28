# DMRandevu Galeri

Android client for the DMRandevu media gallery: a full-screen feed of the videos customers
sent in over Instagram DM, for triaging them into Stories and Reels.

Vertical swipe moves between customers, horizontal swipe between that customer's videos.
There is no delete button — **swiping forward to the next customer deletes the one you left**,
after a five-second grace period in which swiping back cancels it. Deleting only clears the
conversation from the server's Redis; the Instagram DM and its media are untouched.

Per video: save to the phone gallery, hand straight to Instagram Stories, prepare for Reels,
or generate an AI caption.

## Server

The app is a client for an existing DMRandevu deployment — it has no backend of its own and
stores nothing but the session cookie and the login form's last values. It signs in with admin
credentials against `POST /admin/auth/login` and then uses `/admin/media-gallery-page`,
`/admin/media-gallery-resolve`, `/admin/media-proxy`, `/admin/generate-caption` and
`DELETE /admin/conversation/:salonId/:clientId`.

Videos are never fetched from Instagram's CDN directly; they stream through the server's
media proxy, which is what carries the session cookie and handles range requests.

An account can be entered as an @handle or as a numeric Instagram id. Numeric ids and a couple
of known handles are resolved on the device, so the app still works against a server that
predates `/admin/media-gallery-resolve`.

## Build

Needs the Android SDK (compileSdk 36) and JDK 17+. `gradle.properties` pins the JDK path to
the one Android Studio ships on macOS — change it for other machines.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To point the app at a server running on the development machine, forward the port over USB and
use `http://127.0.0.1:<port>` as the server address:

```sh
adb reverse tcp:3111 tcp:3111
```

## Notes

Reels cannot be opened directly. Instagram honours `ADD_TO_REEL` only for apps Meta approved
for "Sharing to Reels" and silently redirects everyone else, so the Reels button saves the
video to the phone gallery and copies the caption instead — the Reel is created by picking it
there. Stories has no such restriction and does open with the video loaded. No Instagram share
intent accepts a caption on any surface, which is why captions travel via the clipboard.

## Küfür filtresi (profanity beep)

A fourth export filter: Turkish swearing is replaced with a beep, while the background sound keeps
playing underneath. Off by default; the toggle is the speaker icon in the page header.

How it works, and why it is built this way:

- **Recognition takes several passes.** whisper will not give good words and good times at once.
  With `no_timestamps` it transcribes swearing faithfully but reports one thirty-second block;
  with `max_len=1` it gives a word at a time but quietly substitutes an innocent near-homophone —
  the operator's own clip came back as "sikeceğim" one way and "çıkacağım" the other. Slowing the
  audio to 0.75× surfaces words no real-time pass produces. So detection and timing are separate
  passes, reconciled by Needleman-Wunsch alignment in `WordAlignment`.
- **The larger model is conditional.** `small` runs only when `base` returns almost nothing, the
  one case it was measured to help. Where base hears the speech, small adds three minutes to find
  the same words — and on the clip that actually contains swearing, small was the one that
  sanitised it.
- **Separation is windowed.** Only the censor windows go through the UVR model, so the cost is
  proportional to how much swearing there is rather than to the length of the video.
- **The native build must be optimised.** AGP sets `CMAKE_BUILD_TYPE=Debug` for debug variants,
  which left ggml at `-O0` and made one recognition pass take ten minutes. The `:whisper` module
  forces Release; `RecognitionSpeedTest` keeps the number honest.

Measured on a Galaxy S22+, 35 s clip: about 75 seconds end to end, of which recognition is ~90%.

Models (~320 MB: whisper base + small, UVR-MDX-NET-Voc_FT) are downloaded on first use into
`filesDir/censor-models` and checked by size and SHA-256. They are not bundled — they would
quadruple a 30 MB app for a filter that may never be switched on.

**Vocal separation model: UVR-MDX-NET-Voc_FT, by the Ultimate Vocal Remover project
(Anjok07 and aufr33), MIT with attribution requested.**

### Tests

Unit tests run anywhere. The device tests need clips pushed by hand, since they are customers'
videos and are not committed:

```
adb push clip_with_swearing.mp4 /data/local/tmp/censor_test.mp4
adb push clip_without.mp4       /data/local/tmp/censor_clean.mp4
adb push bench16k.pcm           /data/local/tmp/bench16k.pcm   # 16 kHz mono raw PCM
```

`CensorAudioExportTest` asserts the beep covers the swearing, that 1 kHz dominates inside the
window and not outside, and that a clip with speech but no swearing is left completely untouched.
