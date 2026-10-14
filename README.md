# Assist overlay

A replacement for the Home Assistant companion app's Assist popup. It's an Android app that becomes the
**default digital assistant**: a long-press of the power button opens a panel over the current app and talks
to one of your Home Assistant Assist pipelines.

- Speech-to-text runs on the phone: Google's recognizer (live words), or Whisper (whisper.cpp, small.en q8_0)
  on the phone itself, downloaded from Hugging Face the first time.
- Text goes to HA over the websocket (`assist_pipeline/run`, starting at the intent stage), so the reply streams in.
  Spoken replies use the pipeline's own TTS engine and voice, streamed sentence by sentence.
- The conversation stays for 10 minutes (`Config.KEEP_MS`); a quiet notification reopens it. Fling the panel
  sideways to tuck it into a tab on the screen edge while a slow answer comes in.
- `![Name](ha:camera.x)` lines in a reply show as a camera picture or a state card.
- "Continue in Claude" shares the transcript to the Claude app.

## Setup on the phone

1. Install the APK from the [latest release](../../releases/latest).
2. Open Assist and enter your Home Assistant address (Nabu Casa or your own https URL), plus an optional
   address at home that is tried first (plain http on the LAN is fine).
3. Sign in (HA's own login page), pick the voice assistant pipeline, allow the mic and notifications,
   and make Assist the default digital assistant.

The app checks the latest GitHub release every few hours and offers updates.

Settings can also be copied over adb (handy when reinstalling). Only the shell can send this:

    adb shell am broadcast -n com.ahaverty.assist/.ConfigReceiver \
      --es remote_url https://example.ui.nabu.casa --es home_url http://192.168.1.10:8123 \
      --es pipeline_id <id> --es assistant_name Friday --es user_name Sam

Keys: `home_url remote_url pipeline_id pipeline_name assistant_name user_name whisper_words tts_engine tts_language tts_voice`.

Sign-in uses HA's IndieAuth with client_id `https://assist.ahaverty.com/`. The redirect is caught in the WebView,
so that domain never has to exist.

## Build and release

GitHub Actions (`.github/workflows/build.yml`) builds every push to main. To release, bump `versionCode` and
`versionName` in `app/build.gradle.kts` and push: a new versionName becomes a GitHub release with `assist.apk`
and `version.json`, and phones pick it up from there. The commit subject (minus a `1.9: ` prefix) is the release note.

Signing uses the repository secrets `KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Keep the
original `release.jks`: updates only install over the old app when they're signed with the same key.

Local builds still work: JDK 21, the Android SDK with NDK 27.2.12479018 and CMake 3.22.1,
`git submodule update --init`, then `./gradlew assembleDebug` (or `assembleRelease` with a `keystore.properties`).
