# Cheto Gallery

Android client for the DMRandevu media gallery: a full-screen feed of the videos customers
sent in over Instagram DM, Facebook Messenger and WhatsApp, for triaging them into Stories and
Reels. One account's feed mixes all three; a Messenger or WhatsApp conversation is marked with a
small label beside the name, and only Instagram names carry an @.

One flat vertical feed: every page is a video. Swiping up moves to the next video and, past a
customer's last one, to the next customer. There is no delete button — **moving forward past a
customer deletes the one you left**, after a five-second grace period in which swiping back
cancels it. Deleting only clears the conversation from the server's Redis; the DM itself and
its media are untouched, on whichever app it arrived.

The İhbar calls (status, approve, dismiss) go to the signed-in DMRandevu session at
`/admin/ihbar/…`, which forwards them to the Trafik İhbar server with a device token held on that
server (`IHBAR_DEVICE_TOKEN`). The phone carries no İhbar address and no token. Every other account
and channel keeps download, Story, Reels, caption, face and plate blur, the drifting watermark and
the swearing filter; only the swipe, the bulk dismiss and the status chip are İhbar's.

The horizontal axis is the decision (on the `trafik_cezasi` account only, and only for videos
that came in over Instagram): **swipe right to report the video as a traffic violation, swipe left
to dismiss it**. The İhbar server ingests Instagram alone, so a Messenger or WhatsApp video has no
record there to approve; on those the card does not move. The request is held for
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

Videos are never fetched from Meta's CDNs directly; they stream through the server's
media proxy, which is what carries the session cookie and handles range requests.

The app treats every video address as opaque and hands it to the proxy as it came. Instagram and
Messenger addresses are CDN urls. A WhatsApp video has none — the webhook delivers a media id whose
download needs the business's token — so the server names it `wa-media://{salonId}/{mediaId}` and
the proxy resolves it through Graph on each request. Meta keeps an inbound media id downloadable
for **7 days**, so a WhatsApp video older than that is not in the feed at all (an Instagram link
can die sooner; that still shows as an expired link). Each item's `channel`
(`instagram` / `facebook` / `whatsapp`) decides the header and the decision axis; against a server
too old to send it, the app reads the `fb:` / `wa:` prefix of the client id instead.

Everyone signs in with their own username and password, created by the super admin on the
server's `/admin/admins` page. A **gallery user** (the default there) reaches only the media
gallery of the Instagram accounts listed for them: any other account answers "not found", and the
rest of the panel is closed. Typing another account on the login screen therefore fails as
"account not found".

The gear in the top-right corner opens **Settings**: who is signed in, change your own password,
sign out, and the version. Changing the password needs the current one; it never leaves the phone
except to the server, and the app stores no password. Signing out closes the server session and clears
everything of the previous person (loaded conversations included), so the next sign-in, as anyone,
starts clean. A wrong current password is shown on the form; it does not log you out.

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

### Release build (Google Play)

Package `ai.cheto.gallery`. The release is signed with an **upload key that is not in the
repository**: Gradle reads `~/.cheto-signing/keystore.properties` (keystore path, alias and
passwords) and, without that file, simply builds the release unsigned. Back that folder up — with
Play App Signing a lost upload key can be reset through Play support, but it is a detour.

```sh
./gradlew :app:bundleRelease     # app/build/outputs/bundle/release/app-release.aab
```

Raise `versionCode` in `app/build.gradle.kts` for every upload; Play refuses a repeat. Store
texts and graphics, and the Console checklist, are in `store/android/`.

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

Both Reels buttons — the one in the bottom bar and "Reels olarak paylaş" in the caption sheet —
export the video, put the generated caption on the clipboard and then open the composer. The
bottom-bar one **also drops a copy in the phone gallery** on the way, without exporting twice.

That copy is the recovery path, because a refused App ID **cannot be detected from here**.
`openReelComposer` returns false only when Instagram does not answer the intent at all; a
rejection happens inside the composer, after the hand-off has already succeeded. Without the
gallery copy the operator would be left with an error dialog and nothing to pick.

`REELS_COMPOSER_ENABLED` / `reelsComposerEnabled` gates the caption sheet's button only. The
bottom-bar button always tries the composer and falls back to opening Instagram plain, where the
video is waiting in the gallery.

No Instagram hand-off accepts a caption on any surface, the Reels composer included, which is
why the caption is generated first and travels via the clipboard — Instagram is only opened once
there is something to paste.

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
