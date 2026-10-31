# ibot Multi-AI

ibot is an incremental Mika fork. The upstream license, attribution, phone runtime, providers,
ChatGPT sign-in, browser, memory and USB/Wi-Fi MCP remain available. The development app is
installed alongside Mika; it does not import or erase Mika's private data.

## Build and install

Use JDK 17, Android SDK platform 36, build-tools 35.0.0, and the checked-in Gradle wrapper.
Set `JAVA_HOME`, `ANDROID_HOME`, and an ignored `local.properties` pointing to the SDK.

```powershell
.\gradlew.bat :core:portableTest :app:portableDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The explicitly named Windows tasks exclude POSIX sandbox/filesystem contracts that Windows
cannot implement (67 core and 69 Android JVM cases). The original complete tasks remain intact;
on Linux run `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug`. The PR workflow runs
those complete tasks. Portable success must not be reported as complete POSIX coverage.
Lint baselines 28 errors in unchanged upstream v0.1.23 files; new errors remain fatal.
Pixel API 37 instrumentation uses stable AndroidX Test runner 1.7.0 / Espresso 3.7.0.
After installing the debug and androidTest APKs, run the device acceptance script:

```powershell
.\tools\windows\test-ibot-device.ps1 -Serial 37011FDJH001TZ -Adb C:\Android\platform-tools\adb.exe -Wake
```

`-Wake` only wakes the display; unlock it manually. The script isolates Compose test cases
in separate instrumentation processes without clearing data. The inherited Compose test clock
can re-enter layout during combined API 37 runs; those combined runs are not claimed as passed.

Debug application ID: `com.wangzan101.mika.multiai.dev`; launcher label: **ibot Dev**.
The release application ID is `com.wangzan101.mika.multiai`. Upstream namespace/class names
are retained to minimize changes. Do not uninstall Mika or use `pm clear`.
Compare the installed package and public signing-certificate digest before installation.
Never install a differently signed APK over the original application.

## Chat and roles

Open **ibot · Multi-AI** from the welcome page or Settings. AI management opens Mika's existing
provider settings. ChatGPT is the default coordinator when configured; otherwise select a
coordinator explicitly. All roles can be assigned to different configured models.

- Single: one selected coordinator answers.
- Sequential: selected roles hand necessary outputs to the next role, then the coordinator summarizes.
- Parallel: independent answers are kept separate until synthesis, including disagreement and failures.
- Experts: a planner proposes at most eight dependency nodes; the full graph is checked before execution.

Raw visible responses, provenance, state changes and handoffs are shown. Private model reasoning is
not shared. Responses are model opinions, not independently verified facts. No conversation history,
personal memory, stored skills or screenshots are automatically shared between providers.

## Connections and costs

GPT, DeepSeek and Grok API reuse existing provider clients. Grok API uses official xAI endpoints.
The installed Grok App (`ai.x.grok`) and Grok Bot (`ai.x.grok.bot`) are separate connection types:
an installed app is **not** a callable API. No private cookie/login reverse engineering is used.
Muse remains **not configured** until a stable, authorized API/CLI/MCP interface is confirmed.
No Oracle service is changed or deployed by this implementation.

The default test budget is zero. Enable **Local Mock** for an offline demonstration; Mock responses
are explicitly labeled and do not validate external services. Real calls require credentials or
eligible ChatGPT sign-in, a positive task budget, and explicitly supplied per-model input/output
prices. Prices are estimates supplied by the user, not a billing guarantee. Unknown pricing blocks
calls. Request, token and estimated-cost reservations are shared across retries and nested phone
execution. Failed requests retain their conservative reservation because a server may still bill them.

Defaults: 20 model requests, two concurrent model calls, one phone executor, 2,048 output tokens per
request, 32,000 reserved tokens, 120-second request timeout and ten-minute active task timeout.
Cancellation stops subsequent dispatch and synthesis. Interrupted runs require a new explicit start.
Run history is encrypted with Android Keystore; credentials are not embedded in source or APK assets.

## Phone safety

Planning has no phone tools; review has no write tools. Only an explicitly authorized Executor can
request `phone_execute_task (collaboration bridge)`. The existing phone runtime uses a conversation-scoped, budgeted model
and a tool allowlist, without changing the user's global model selection. Web, sandbox, external MCP,
memory, scheduling and recursive delegation are excluded from that run. Outward actions (sending, posting, ordering, deleting and payment) require human
takeover in collaboration runs, including when global autonomous mode is enabled. Takeover stops further collaboration.
ibot never collects lock-screen credentials and returns `manual_unlock_required` for remote unlock.
State inspection and separately enabled wake remain available.

## MCP compatibility

Original `mika_*` tool names and semantics are preserved. Development MCP listens on device port
8766; use Windows port 18766, leaving the original Mika forwarding at 8765 untouched:

```powershell
.\tools\windows\mika-usb-mcp.ps1 -Action Connect -Serial 37011FDJH001TZ -PackageName com.wangzan101.mika.multiai.dev -LocalPort 18766 -CheckHealth
```

`ibot_list_agents` reports connections without credentials. `ibot_run_collaboration` accepts a
`request` object matching `CollaborationRequest` and returns `task_id`. Remote initiation is limited
to zero-cost Mock tasks without phone grants. Real-provider tasks must be configured and started
on the phone. Existing status/wait/cancel tools follow collaboration IDs; MCP cannot approve a
payment or grant phone permissions. Never publish the local bearer token or expose this listener
through an unapproved public tunnel. The existing Oracle SSH tunnel is temporary and not assumed live.

## Rollback and evidence

Stop only the development app and remove only its 18766 forwarding. The original package and 8765
forwarding are independent. Source rollback uses the staged commits; do not reset unrelated work.
APK hashes, build commit, actual test results and device evidence are recorded separately in
`docs/ibot-validation.md`. A passing Mock test is not a passing external-provider or phone-action test.
