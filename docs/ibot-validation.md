# ibot validation record

Date: 2026-10-31. Implementation, offline Mock tests, real-provider calls and device
verification are separate claims. This development build is not a production release.

## Source and Git identity

- Baseline: upstream formal v0.1.23, `b05dadbc5cf064b97bc4f99090e4c6eddff1e2a8`.
- Windows helper PR #1 preserved at `53b68ea91ae366e235fcb6f2f3b27cd8644b90b1`.
- Fork main preserved at `8df52edb7f6caddb2649b668feecacd7a4aacce0`.
- Development branch: `feat/mika-multi-ai`; Draft PR: https://github.com/lijunpixel/Mika/pull/2.
- Repository origin: `git@github-lijunpixel:lijunpixel/Mika.git`.
- Existing local public key matched a public key registered to lijunpixel. Reused through
  a dedicated SSH alias; no private key contents read or emitted, no new key generated.
- Actual SSH response: `Hi lijunpixel! You've successfully authenticated, but GitHub does
  not provide shell access.` (SSH exit 1 is expected.)
- `git ls-remote`, non-force dry-run and normal branch push succeeded. Global Git identity,
  stored HTTPS credentials and other SSH aliases were not changed.

## Build and automated validation

- Host: Windows ThinkPad; isolated Microsoft JDK 17.0.21, Gradle wrapper 8.11.1,
  Android SDK platform 36 and build-tools 35.0.0/34.0.0.
- SDK command-line tools archive SHA-256 verified against Google's published digest.
- Windows PowerShell USB MCP offline suite: 16 passed, 0 failed (including startup token
  rotation, bounded failure and port-change rejection). GitHub Windows smoke
  workflow also passed on PowerShell 5.1 and 7 for source `a1e2cc9`.
- Targeted safety, collaboration, summary replacement and branding regression: passed.
- The original full Windows JVM tasks exposed upstream POSIX filesystem assumptions.
  Separate portable tasks exclude POSIX cases (core=67, app=69); complete Linux tasks remain
  unchanged and run in the PR workflow. Portable success is not full POSIX coverage.
- Lint baseline contains 28 errors only in unchanged upstream v0.1.23 files; new errors
  remain fatal. Inherited compileSdk/AGP compatibility and other warnings remain visible.
- Initial Pixel Compose tests failed inside old Espresso's removed InputManager reflection.
  Updated only instrumentation dependencies to stable runner 1.7.0, JUnit extension 1.3.0
  and Espresso 3.7.0, following https://developer.android.com/jetpack/androidx/releases/test.

Windows core portable result: 2,456 tests, zero failures/errors, two skipped. Windows app
portable JVM result: 969 tests, zero failures/errors/skips. Local lint passed:
zero new errors, 85 warnings, inherited baseline error count=28.

The full Linux `:core:test :app:testDebugUnitTest :app:lintDebug` command passed on source
`a1e2cc9712da0180f5c30898d0766f9fb8dcb5cb` (7m 21s):
https://github.com/lijunpixel/Mika/actions/runs/41213212295.
Windows smoke also passed: https://github.com/lijunpixel/Mika/actions/runs/41213212105.

## Delivered APK

- File: `D:\bot\Mika\dist\ibot-multiai-dev-debug.apk`.
- Size: 99,376,767 bytes (94.77 MiB).
- SHA-256: `bbade88c421f9a7c2dc60ecb594f6278d7b2c7ae7710bfd639fd468349a773eb`.
- Runtime source / embedded build identifier: `a1e2cc9712da0180f5c30898d0766f9fb8dcb5cb` / `a1e2cc9`.
- Version: `0.1.23-ibot-dev`, versionCode=24. APK signature verified with Android apksigner.
- Installed with `adb install -r -t` on the independent development package. Final activity
  launch returned `Status: ok`; startup screenshot: `D:\bot\evidence\ibot-final-startup.png`.
- Final APK USB MCP initialization returned HTTP 200 again after installation.
- Subsequent documentation, instrumentation and desktop-helper commits do not change this APK's runtime code.
- Pixel device cases passed in isolated instrumentation processes: no-key start guard,
  all four Mock collaboration modes with independent output and result assertions, active
  task cancellation, traceable Mock MCP completion, and rejection of remote real/phone grants.
  Test count=5. Evidence directory: `D:\bot\evidence\final-device-tests`.
- Combined API 37 Compose execution still encountered an inherited test-clock re-entrant layout
  exception. This combined run is FAILED, not reported as passing. The checked-in
  `tools/windows/test-ibot-device.ps1` runs each Compose case in its own process, checks both
  instrumentation output and exit status, and never clears package data. This remains a
  test-harness limitation; normal on-device startup/navigation and isolated assertions passed.

## Pixel 8 and MCP

- Serial `37011FDJH001TZ`, API 37. Original Mika `com.wangzan101.mika`, version 0.1.22,
  retained alongside `com.wangzan101.mika.multiai.dev` / **ibot Dev**.
- Original signing-certificate SHA-256:
  `11d822669d535c554800f9cbe183a93e9f273e0c0e065dc428e569580b9a3fa8`.
- Development signing-certificate SHA-256:
  `8a8a621a7ab1f6708778e05152eef01795dbe985c738e4886f9a54e749d0a1ad`.
- Different signatures confirmed before installing the independent development package.
  No original-package uninstall, data clear or replacement was performed.
- App launched without API keys; welcome and collaboration screens inspected on device.
- Actual USB MCP initialization HTTP 200; discovery toolCount=11 (original nine plus
  `ibot_list_agents` and `ibot_run_collaboration`); read-only task list and state calls passed.
- Local Mock parallel collaboration completed through MCP with explicit Mock provenance.
- Remote unlock returned `manual_unlock_required`; no lock-screen credential read or entered.
- Development forward removal/restoration tested; old token returned HTTP 401 after close,
  refreshed connection returned 200 and rotated token. Original `8765 -> 8765` preserved.
- Captured a post-instrumentation startup race: the first immediate Connect probe failed,
  while Status with refreshed metadata returned 200. The desktop helper now retries only
  initialization up to three times with fresh in-memory metadata and rejects a changed port.
  Offline recovery/failure cases passed; stopping/restarting only ibot Dev then Connect on
  the real Pixel returned 200. Tool invocations and user actions are never replayed.
- Local sanitized evidence: `D:\bot\evidence\mcp-regression.json`; initial screenshots:
  `D:\bot\evidence\ibot-startup.png`, `D:\bot\evidence\ibot-collaboration.png`.
  No bearer token is included in this record or committed evidence.

## Provider acceptance and remaining limits

| Connection | Actual status |
| --- | --- |
| ChatGPT sign-in / GPT API | Adapter implemented; waiting for user configuration; real inference NOT_RUN |
| Official xAI Grok API | Adapter implemented; no API key configured; real inference NOT_RUN |
| Existing Grok App / Grok Bot | Both detected installed; no verified programmatic API; not interoperable with API adapter |
| DeepSeek API | Adapter implemented; no API key configured; real inference NOT_RUN |
| Muse | Adapter intentionally unconfigured; authorized stable transport UNVERIFIED |
| Local Mock | Offline orchestration only; not evidence of external-provider availability |

Zero real paid model requests were made. Real model tool execution, cross-provider live
cancellation and billing require configured providers and an explicitly funded budget.
Muse Oracle SSH exploration was rejected by the earlier environment approval policy;
no Oracle services were changed and no tunnel is assumed permanent. Wi-Fi and Oracle
end-to-end connectivity on this new package remain NOT_RUN; existing USB coverage is separate.
Release signing is not configured; no Release APK, store upload, production deployment or merge.

## Rollback

Stop only ibot Dev and remove only the owned development forwarding at port 18766.
Original Mika and its port 8765 are independent. Retain development data for investigation;
do not uninstall or clear either package as an automatic rollback. Source changes are isolated
on the development branch and reviewable in staged commits; main and PR #1 remain untouched.
