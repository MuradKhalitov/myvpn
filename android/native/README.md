# Official libXray local build

This application uses the official [XTLS/libXray](https://github.com/XTLS/libXray)
source only. The native artifact is deliberately local and ignored by Git.

## Pinned source

- libXray tag: `v26.3.27`
- libXray commit: `38ae3cd8914d5bc2a7f81122fc6206efe3c07ad6`
- Xray-core Go module: `v1.260327.0`

## Build

Run from `android` on a machine with Go, Python, Android SDK, and Android NDK:

```powershell
.\native\build-libxray.ps1
```

The script clones the pinned tag to `android/.native/libXray`, verifies the
commit, runs the official `python build/main.py android` command, and leaves
the official gomobile output at `android/.native/libXray/libXray.aar`.

`app/build.gradle.kts` imports that local AAR. Do not commit the AAR, clone,
Go cache, gomobile cache, or NDK download.

## Licenses

libXray is MIT licensed. Xray-core is MPL-2.0 licensed. Release packaging
must retain the appropriate notices and make the Xray-core source availability
information required by MPL-2.0 available to recipients.
