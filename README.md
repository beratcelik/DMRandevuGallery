# DMRandevu Galeri

Android client for the DMRandevu media gallery: a full-screen feed of the videos customers
sent in over Instagram DM, for triaging them into Stories and Reels.

One flat vertical feed: every page is a video. Swiping up moves to the next video and, past a
customer's last one, to the next customer. There is no delete button — **moving forward past a
customer deletes the one you left**, after a five-second grace period in which swiping back
cancels it. Deleting only clears the conversation from the server's Redis; the Instagram DM and
its media are untouched.

The horizontal axis is the decision (on the `trafik_cezasi` account only): **swipe right to
report the video as a traffic violation, swipe left to dismiss it**. The request is held for
three seconds behind an undo chip, and swiping back onto the page cancels it too. Dismissing a
video that belongs to a multi-video report removes only that video; the report stays up with the
rest of its evidence. Dismissing one that was already approved retracts it from the officers it
reached, and rides the same three-second window as any other dismiss — nothing asks first,
because retraction sends the officers no notice of its own; the record simply stops being
visible to them.

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

Reels opens its composer directly, and getting there was **not** a Meta approval. "Sharing to
Reels" stopped being a closed programme in October 2023: it is self-serve, has no App Review
submission, requests no permissions and needs neither Business Verification nor a Play Store
listing. What it does need is a Meta App ID belonging to this app, riding on the intent as
`com.instagram.platform.extra.APPLICATION_ID`, with that Meta app switched to **Live** mode.

The app's ID is `1059486250258693`, in `InstagramSharing.META_APP_ID` (Android) and
`InstagramSharing.metaAppID` (iOS). Before it, the code carried `685976850839286` — a public
sample from Meta's own documentation, registered to nobody here — which is why Instagram used to
answer a hand-off with a message of its own about the app not being supported or verified. That
ID rides on **both** the Stories and the Reels hand-off.

Two things gate it, and neither is visible from inside this app:

- **The Meta app must be Live**, not in Development mode. In Development only accounts holding a
  role on the app clear the gate, so testing with your own Instagram account proves nothing. Test
  with a phone signed into an account with **no role** on the app.
- **Leave Google Play Package Name empty** in the Meta dashboard unless the build is actually
  public on Play. Meta's compliance crawler fails an unreachable one and can degrade the app.

`REELS_COMPOSER_ENABLED` / `reelsComposerEnabled` switches the composer path on and is currently
`true`. Turning it off returns to the older route: export, save to the phone gallery, copy the
caption, open Instagram, pick the video by hand.

That fallback is also the recovery path if Instagram refuses the ID, because **it cannot be
detected from here**. `openReelComposer` returns false only when Instagram does not answer the
intent at all; a rejection happens inside the composer, after the hand-off has already
succeeded, and the composer route never writes to the phone gallery — so the operator is left
with an error dialog and nothing to pick.

Stories has no ID-free path at all, which is why it was the button that showed Instagram's
complaint first. No Instagram share intent accepts a caption on any surface, which is why
captions travel via the clipboard.

The video must also be within Instagram's envelope: 1080p, 3 to 60 seconds, H.264/H.265 in
MP4/MOV/WebM, and on iOS no larger than 50 MB.

## Orientation

The first launch of an install shows a full-screen tour of the gestures and the buttons,
dismissed with "Anladım". The flag is the **build** it was last shown for
(`tour_shown_build`, the same key string on both platforms), not a plain boolean: raising
`versionCode` / `CURRENT_PROJECT_VERSION` when the gestures change shows it once more. Both are
still at 1, so reinstalling over an existing install does not bring it back.

The tour drops the swipe-left / swipe-right rows on accounts where the decision axis is inert,
because teaching a gesture that does nothing is worse than teaching nothing.

## Küfür filtresi (profanity beep)

A fourth export filter: Turkish swearing is replaced with a beep, while the background sound keeps
playing underneath. Off by default; the toggle is the speaker icon on the filter rail down the right edge.

How it works, and why it is built this way:

- **Every pass stands alone.** whisper primes a call with the text of the one before it, and the
  same context is reused across passes over one clip. A short snippet decoded after three full
  passes came back with different words and its timestamps piled onto one edge of the audio —
  which looked like the phone's CPU being unreliable, and was `no_context` being off.
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
- **A second pass places the beep.** The first pass gives the right words at the wrong times:
  whisper works in thirty-second windows and a word early in one is dragged towards the window's
  start, which put swearing at 30.00 s that is at 30.68 s. Re-recognising six seconds around each
  hit puts the word in the middle of a single window, where there is no boundary to pull it, and
  places it to within 20 ms. When that pass cannot place the words — its timestamps sometimes pile
  onto one edge of the snippet — the rough timing stands and the window widens to 1.95 s rather
  than risk missing.
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
