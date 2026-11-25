# Fretwire Monitor (Android)

Hear your iRig USB guitar input live in your headphones, mixed with whatever else
your phone is playing (Spotify, YouTube, lessons). A small floating 🎸 bubble stays
over other apps for volume, mute and stop.

- Never takes audio focus, so other apps keep playing.
- Runs as a foreground service (microphone type) with a notification: Mute / Stop.
- Auto-picks the USB input and re-connects when the iRig is unplugged and replugged.
- Low-latency output path; the buffer grows by itself if it hears crackles.

Requires Android 8.0+.

## Build

```sh
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk (signed with the debug key, for sideloading)
```
