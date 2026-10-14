# Assist overlay

Alan's replacement for the HA companion app's Assist popup. It's an Android app that becomes the
**default digital assistant**: a long-press of the power button opens a panel over the current app.
That panel talks to the HA "Claude Phone" pipeline (`Config.PIPELINE_ID`, friday-voice bridge, phone profile).

- Speech-to-text runs on the phone, using Google's recognizer named explicitly (see `Speech.kt` for why).
  You see the words live, and the mic is released on every path.
- Text goes to HA over the websocket (`assist_pipeline/run`, start at the intent stage), so the reply streams in.
  Spoken replies use the pipeline's TTS (Google Cloud Chirp), streamed.
- The conversation stays in memory for 10 minutes (`Config.KEEP_MS`). Leaving for another app keeps it, and a
  quiet notification reopens it. Only the X button, Back or "New chat" end things. Tapping outside the panel does nothing.
- The "Claude" button shares the transcript to the Claude app (ACTION_SEND, which prefills a new chat).
  If the share intent isn't available, it copies the transcript to the clipboard and opens Claude instead.
- Sign-in uses HA's own login page in a WebView. The client_id is `https://assist.ahaverty.com/`, and the
  redirect is caught in the WebView, so that domain never has to exist.

## Build

    JAVA_HOME=JDK 21, SDK at C:\Android\sdk (local.properties)
    ./gradlew assembleRelease   ->  app/build/outputs/apk/release/app-release.apk

Builds are signed with `release.jks` / `keystore.properties`. Both are git-ignored and must not be lost:
updates only install over the old app when they're signed with the same key. Bump `versionCode` for each release.
