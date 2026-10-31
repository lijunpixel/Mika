# ibot branding update — 2026-11-01

## Source and changes

- Fast-forwarded `feat/mika-multi-ai` from `0ac53fc` to the requested
  `4b898e28a1e1d7cc7922be282bd3f8fc0b463cfa` without discarding work.
- Runtime fix/build commit: `13749e3730307b82a3d388f9f74a5afdf96f12e0`.
- Follow-up `0fe51f2` only restores test imports and locks the English example; APK runtime unchanged.
- Chat header/sidebar show ibot. The selected-group summary replaces the unverified beta group;
  the other five examples match the requested commit in English, Simplified and Traditional Chinese.
- Removed old-author WeChat/X support, QR asset/export helper and automatic join-group callbacks.
  Support, source, guide and update URLs now target `lijunpixel/Mika`. Upstream source remains
  separately linked as attribution. Original author Wang Zan and copyright year 2026 remain visible.
- LICENSE, THIRD_PARTY_NOTICES.md, licenses/ and packaged license texts were not changed.
  APK inspection confirmed Apache license present and old-author QR asset absent.

## Actual local checks

- Targeted Android JVM tests: 39 passed, zero failures/errors/skips across ChatCopyTest,
  AboutCopyTest, MikaLinksTest, EnglishCopyTest, StringResourcesTest, LicensesTest and ApkIntegrityTest.
- Documentation consistency: four passed, zero failures.
- Lint: zero errors, 85 warnings; inherited baseline error count=28. No new suppression added.
- Initial test compilation exposed missing imports; restored them, then the targeted tests
  and Android test APK build passed. The development APK itself was assembled once.
- No external model requests or complete model/MCP regressions were run.

## APK and installation

- APK: `D:\bot\Mika\dist\ibot-dev-branding-13749e3.apk`
- Size: 102,737,226 bytes.
- SHA-256: `bed56724574fb62cf1564fd57cd7c7bf3598f75b5b504d40de23454bce1023ad`
- Embedded build: `13749e3`; version `0.1.23-ibot-dev`, versionCode=24.
- Application ID: `com.wangzan101.mika.multiai.dev` (ibot Dev).
- Installed and new APK certificate SHA-256 both:
  `8a8a621a7ab1f6708778e05152eef01795dbe985c738e4886f9a54e749d0a1ad`.
- Pixel 8 `37011FDJH001TZ`: `adb install -r -t` returned Success.
  Development-package first-install time remained 2026-10-31 22:25:43; only last-update time changed.
- Original Mika `com.wangzan101.mika` remained at 0.1.22 with unchanged first-install/last-update records.
  No uninstall or package-data clearing. Original USB forwarding at port 8765 remains present.
- Sanitized local metadata, test totals and install logs: `D:\bot\evidence\branding-20261101`.

## Device UI acceptance — pending manual unlock

Status: PENDING_MANUAL_UNLOCK. The phone reports keyguard showing and input restricted.
Wake was authorized and used; no lock-screen password was read or entered.
The new app and test APK are installed, but UI assertions/screenshots are not yet claimed as passed.

After manual unlock, run each method in a separate instrumentation process, using the installed
`com.wangzan101.mika.multiai.dev.test/androidx.test.runner.AndroidJUnitRunner`:

- BrandingScreenTest#brandingFixtureProductionChatTopAndSixExamples
- BrandingScreenTest#brandingFixtureSidebar
- AboutScreenTest#showsHeroRowsNoteAndFooter
- AboutScreenTest#feedbackSheetHasOnlyGitHubAndDiagnostics
- AboutScreenTest#externalRowsOpenLinks
- AboutScreenTest#footerOpensLicense

Branding fixtures render production ChatScreen/ChatDrawer with isolated state and fail-fast task
actions, without sending examples or changing user conversations. Their screenshot names start
with branding-fixture-. Record normal MainActivity captures separately: chat title/examples,
sidebar, About support choices, attribution and license. Do not label fixture images as a normal
application navigation test. Do not execute example tasks or change onboarding/provider credentials.
