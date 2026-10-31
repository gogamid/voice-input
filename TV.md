# Unofficial TV adaptation of FUTO Voice Input nightly

This is a modified, non-commercial personal-use fork, not an official FUTO
release. FUTO's license, attribution, and payment functionality remain intact.
See [LICENSE.md](LICENSE.md) for the terms governing this software.

## Status

The initial milestone is a cloud-built ARM32 APK with unchanged recognition and
recording logic. Direct USB audio, remote-button activation, and a TV-specific
dictation panel are **not implemented yet**. The initial APK still uses Android's
normal microphone path; it must not be presented as a working remote-mic fix.

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
submodule. No TV recordings, settings backups, or private device data belong in
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
