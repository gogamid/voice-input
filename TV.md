# Unofficial TV adaptation of FUTO Voice Input nightly

This is a modified, non-commercial personal-use fork, not an official FUTO
release. FUTO's license, attribution, and payment functionality remain intact.
See [LICENSE.md](LICENSE.md) for the terms governing this software.

## Status

The cloud-built ARM32 baseline passed. The TV source now includes direct USB
capture, a voice-only input panel, a setup/test screen, and a native assistant
entry point for the waveform button. Device verification is in progress; these
features must not be described as working until tested on the actual TV.

The stock recording activity and IME are disabled in this variant. TV dictation
does not open `AudioRecord` and never falls back to the built-in TV microphone.
It checks microphone permission, AppOps (including the system privacy toggle),
and microphone mute before opening USB, then rechecks privacy during capture.
The recording AppOp is started/stopped around actual USB capture so Android can
account for microphone use. Audio is buffered in RAM only, not written to disk
or uploaded. Silence ends a session; recording is bounded to 20 seconds.

## Source and native-engine provenance

- Source baseline: `1536d25229e1874435b1ada75a2e79d53bc5e4be`, the revision embedded
  in the official nightly APK tested on the TV.
- Official download: <https://dl.voiceinput.futo.org/VoiceInput/dev.apk>.
- APK SHA-256: `12386b029d676ceb8422dcd615dda33288d6a9df2fc77ce902afa4a1afc2bf52`.
- Public `libs` submodule remains pinned to its original commit.

The baseline's `transcribe.cpp` submodule is not accessible anonymously. Instead
of substituting an unrelated engine or claiming a complete source build, CI
verifies the official APK's checksum and embedded source revision, then extracts
only its five ARM32 voice/transcription/ggml libraries. Java/Kotlin app code is
compiled from this fork. The VAD library comes from the original public `libs`
submodule. Our small `tvusb` JNI capture library is built from source with the
NDK; it neither replaces nor recompiles the transcription engine.
No TV recordings, settings backups, or private device data belong in
this repository or its CI artifacts.

The nightly URL is mutable. If upstream replaces the APK, CI deliberately fails
the checksum check; do not bypass it. Updating the baseline requires reviewing
the new APK, its source revision, and the corresponding native interface together.

## GitHub Actions

Pushes to `tv` build the APK and run existing unit tests using GitHub-hosted
Ubuntu runners. The workflow can also be started manually. It does not publish
a release, install an APK, or change the TV.

The TV build uses `-PtvPrebuiltNative`, packages only `armeabi-v7a`, and has a
separate application ID, `org.futo.voiceinput.tv`. The official nightly can remain
installed. Official update checks are disabled for this separately signed fork;
FUTO payment methods are unchanged.

Repository Actions secrets `TV_KEYSTORE_BASE64` and `TV_KEYSTORE_PASSWORD` hold
the persistent personal signing identity. They are never included in artifacts.
Retain a secure backup: future APK updates must use the same key.

Download `voice-input-tv-arm32-<run number>` from a successful workflow run.
Artifacts include the signed APK, package/architecture inspection, and checksum.
Build success alone does not establish USB microphone or remote-button support.

## One-time TV setup

Install the separately signed APK, enable `org.futo.voiceinput.tv/.TvInputMethodService`,
and grant its microphone permission. Open **Voice Input TV** to grant access to
the USB receiver and select/download the small English model, or use the original
FUTO settings for other models/languages and support/payment options.

The native shortcut uses Android's assistant service, not an accessibility button
mapper. It needs a one-time ADB grant of `android.permission.WRITE_SECURE_SETTINGS`
to switch temporarily from the typing IME to the voice IME. Select
`org.futo.voiceinput.tv/.TvVoiceService` as the assistant/voice interaction service.
The code uses that settings permission only for the default input method.
It remembers the previous typing IME, restores it after dictation/cancellation,
and rolls back a switch when no input view appears. Password fields are refused.

Android must provide a focused editable field in its assist context. Applications
that hide this context may not support the native shortcut; no-text-field launches
must not start recording. No hotword listener is registered. The placeholder
RecognitionService required by assistant metadata explicitly rejects general
SpeechRecognizer requests rather than implicitly starting a microphone.

On the tested TCL firmware, `KEYCODE_VOICE_ASSIST` launches
`android.speech.action.WEB_SEARCH`, not the configured assistant. A no-display
activity handles that native intent and asks the selected TV voice service to
collect the underlying editor's assist context. It does not record by itself.
