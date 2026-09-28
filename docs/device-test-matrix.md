# Physical-device test matrix

This matrix records only behavior that was directly observed on physical hardware. A result remains **Not tested** until that flow is completed on the named device; screenshots or automated tests alone do not count as a pass.

## NVIDIA Shield Android TV

| Field | Value |
|---|---|
| Device | NVIDIA Shield Android TV (`foster`) |
| Android version | Android 11 |
| Resolution | 3840 × 2160 |
| Build | `tvDebug` |
| Test date | 2026-09-27 |

| Flow | Result | Notes |
|---|---|---|
| Dashboard D-pad navigation | Pass | Focus moved between dashboard cards and remained visible. |
| Open package installer | Pass | Installer opened from the dashboard and returned correctly. |
| Built-in file picker | Pass | Picker opened, browsed directories, and handled Back navigation. |
| Receive screen startup | Pass | LAN address and six-digit pairing code were displayed. |
| Settings navigation | Pass | Settings opened and remote focus was visible. |
| Install an APK | Not tested | Requires a future device session with a disposable package. |
| Install a queued batch | Not tested | Requires multiple disposable packages. |
| Browser pairing and upload | Not tested | The receive screen was observed, but no browser completed pairing or upload. |
| APKV export and download | Not tested | Requires an installed test application and paired browser. |
| Root installation | Not tested | Root mode was not exercised. |
| Shizuku installation | Not tested | Shizuku was not running during the session. |
| XAPK/OBB installation | Not tested | Requires a safe XAPK fixture and Root or Shizuku access. |

## Recording future results

Add a separate device section or dated run. Record the device model, Android version, resolution, build variant, flow, result, and concise notes. Do not infer a pass from another device, an emulator, screenshots, or automated tests.
