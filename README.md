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
