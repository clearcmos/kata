# kata

An API-first Android automation runtime. Rules live in `automations/` as JSON, get pushed to
the phone with `cli/kata push`, and run on-device in a foreground service. There is deliberately
no rule builder in the app.

Read `README.md` first for the schema, the vocabulary, and the API table. This file carries the
things that are not obvious from the code and the findings that cost time to establish.

## Layout

```
app/src/main/kotlin/com/clearcmos/kata/
  model/
    Spec.kt          the vocabulary: every trigger, condition, and action declared once
    Automation.kt    Automation, Step, Param, and ${params.key} substitution
    Validator.kt     turns a parsed automation into a list of readable problems
    Args.kt          typed, failure-loud access to an argument bag
    Json.kt          org.json to Kotlin collections, so nothing else touches JSONObject
  engine/
    Engine.kt        runs automations on one single-threaded executor
    Store.kt         the on-device rule set, atomic writes, change listeners
    RunLog.kt        capped persisted ring of run records
    Conditions.kt    condition evaluation
    DeviceState.kt   point-in-time device reads
    Clock.kt         wall-clock helpers for time windows and next-occurrence
    TriggerEvent.kt  TriggerEvent plus TriggerMatcher
    KataService.kt   the foreground service that hosts everything
    Kata.kt          process-wide Store/RunLog/Engine singletons
    Notifications.kt the two notification channels
  triggers/
    TriggerRegistry.kt        binds Android event sources, registering only what is in use
    AlarmReceiver.kt          where time_of_day and interval land
    BootReceiver.kt           restarts the engine after reboot or reinstall
    KataNotificationListener.kt  notification_posted / notification_removed
  actions/
    ActionRunner.kt  every action implementation
  api/
    ControlApi.kt    the routes
    TinyHttpServer.kt  hand-rolled HTTP/1.1 on loopback
    Capabilities.kt  resolves declared requirements against live device state
    ApiToken.kt      generates the bearer token, mirrors it where adb can read it
  ui/
    MainActivity.kt       list, arm/disarm, engine and grant status
    AutomationActivity.kt one rule: params, run now, run history, definition
    Insets.kt             edge-to-edge padding
cli/kata               the workstation CLI, Python 3 stdlib only
automations/           the source of truth, one JSON file per automation
```

## The permission ceiling

The target is a locked retail Galaxy S25. **Root, custom ROMs, platform-key signing and Device
Owner provisioning are permanently out of scope.** Do not design around them and do not raise
them as rejected alternatives.

Splitting work into a second app buys nothing: Android enforces permissions per UID, so a fresh
APK has exactly this app's ceiling. New capability comes from a different class of grant, not
from more apps, which is why every tier lives in kata behind a `Requirement`.

What is reachable, in rough order of what it costs the user:

| Tier | Cost | Unlocks |
| --- | --- | --- |
| Normal and runtime permissions | nothing, or one dialog | most of the current vocabulary |
| `WRITE_SECURE_SETTINGS` | one `adb shell pm grant`, re-run after each install | `secure_setting`, `global_setting` |
| Settings special access | a toggle the user taps | DND policy, notification listener, modify system settings |
| AccessibilityService | a toggle the user taps | **implemented**: `app_foreground` / `app_background` triggers, `app_foreground` condition, `global_action` and `tap_ui` actions |
| Shizuku | user starts it; re-armed over adb after a reboot | ADB shell privilege: `svc`, `pm`, `am force-stop`, `cmd` |

Shizuku is not root. It runs at ADB shell privilege and is available on this device; the user
already has `adb-reconnect` and a wireless-debugging Quick Settings tile, so re-arming it is one
command. It stays a live option unless the user says otherwise.

Genuinely out of reach under this ceiling: toggling Wi-Fi or mobile data through an API
(removed for third-party callers in Android 10) and force-stopping another app. Say so in one
line and stop there.

## Extending the vocabulary

The vocabulary is meant to grow on demand. When a wanted automation cannot be expressed, the
answer is to add the type, not to report it unsupported or to bend the request into an awkward
combination of existing ones. That is the trade made by having no scripting escape hatch.

Adding one, in order:

1. A `TypeSpec` in `Vocabulary` (`model/Spec.kt`): id, doc, fields, and any `Requirement` the
   platform gates it behind. The doc strings are read by an authoring agent through
   `/capabilities`, so write them as instructions, not labels.
2. The implementation: a branch in `ActionRunner.execute`, `ConditionEvaluator.evaluate`, or
   `TriggerRegistry` plus a `TriggerMatcher` case if the trigger takes filter fields.
3. A manifest permission if needed, and a `Requirement` arm in `Capabilities.status` naming the
   exact remedy (an adb command, or the Settings path) so an unmet prerequisite is actionable.
4. A cross-field rule in `Validator.checkStepInvariants` if the spec cannot express it.
5. A unit test if there is pure logic worth pinning. Anything touching an Android API is
   verified on the device instead.

Then:

```
nix develop --command gradle ktlintFormat
nix develop --command gradle ktlintCheck lintDebug testDebugUnitTest assembleDebug
```

install with the `pm grant` block below (an install clears every grant), and confirm the new
type end to end with `cli/kata simulate` or `cli/kata fire`.

Push back only when the platform genuinely cannot do it without root or Shizuku, and say which
of the two it would take.

## Design decisions worth keeping

- **The spec registry is the single source of the vocabulary.** `Vocabulary` in `Spec.kt`
  declares each type's fields, docs, and prerequisites once. Validation, `/capabilities`,
  `/schema`, the CLI's `schema` command, and the detail screen all read from it. Adding an
  action means adding a `TypeSpec` and a branch in `ActionRunner`, and everything else follows.
- **Validation must never throw.** A validator that throws reports one problem and hides the
  rest, which costs an authoring round trip per mistake. `Validator.validate` fences every step
  and `checkField` reports whether a value is usable so the cross-field invariant checks stand
  down rather than throwing on a value that already failed its type check. There are regression
  tests for both.
- **`PUT /automations` is all or nothing.** A partially applied sync leaves the phone matching
  neither the repo nor any intended state, and the difference is invisible from the device.
- **One single-threaded executor owns every run.** Two triggers landing at the same instant
  queue instead of interleaving, which makes `wait` safe and the run log ordered.
- **`Store` is the only change-notification point.** Every mutation goes through it, and
  `KataService` rebinds triggers from its listener. An earlier version also passed an
  `onRulesChanged` callback into `ControlApi`, which made the registry tear down and rebuild
  twice per change.
- **Selective trigger registration.** `TriggerRegistry.refresh()` registers only the event
  sources the current rule set uses. This is not a micro-optimisation: `ACTION_BATTERY_CHANGED`
  fires every few seconds, and a device with only time-based rules should not be listening to
  the battery at all.
- **`battery_level` is edge-triggered** via a `prev_level` fact, so it fires on the way past a
  threshold rather than on every battery broadcast while the level sits beyond it.
- **Simulation goes through the production matcher.** `POST /simulate` builds a real
  `TriggerEvent` and runs it through `TriggerMatcher`, so a simulated fire tests the same code
  path as a real one.
- **No scripting escape hatch.** Gaps get a new typed action, not an embedded interpreter or a
  Termux dependency. A Termux dependency in particular would mean two processes to keep alive
  against One UI's battery management and two capability surfaces for an author to reason
  about. `http_request` covers most of what a script would have done by moving logic to a
  server.

## What was taken from OpenTasker

[OpenTasker](https://github.com/SysAdminDoc/OpenTasker) is MIT, so reuse is clean with
attribution. Three of its ideas are adapted here, each because kata had a real gap:

- **Argument sensitivity** (`model/Sensitivity.kt`). kata was writing every argument verbatim
  into the run record on a dry run, and that record is persisted to `runs.json` and served over
  the API. An `Authorization` header sat in cleartext in both. Resolution is declared flag
  first, then a name heuristic, and an unknown key falls through to the heuristic and is masked.
  Fail closed: over-masking is an annoyance, printing a credential is not recoverable.
- **Retry safety** (`RetrySafety`, `IDEMPOTENT_ACTIONS`). Only idempotent actions accept a
  `retry`. Their comment makes the point better than a doc can: an action phrased as a toggle
  recomputes from the live value, so a retry flips it back rather than repeating it.
- **Action outputs**. Actions publish variables into the run scope, which is what makes a rule a
  sequence rather than a list of independent effects.

Worth recording that OpenTasker arrived independently at kata's central idea. Their action
catalogue exists so "the runtime, editor, capability, and release count surfaces can no longer
invent independent action lists", which is exactly why `Vocabulary` is a single registry.

Deliberately not taken: the Compose GUI, Room and SQLCipher, Locale plugin support, and Tasker
or MacroDroid import. All the wrong shape for a tool with no authoring UI.

Deliberately skipped from the port: `mqtt.publish`, which would add a broker client dependency
for a broker this user does not run.

## Findings

### Android 15+ edge-to-edge breaks the framework action bar

With `targetSdk` 35 or higher the platform draws every app edge-to-edge and stops insetting the
content frame. With a `Theme.Material3.DayNight` (action bar) theme, the decor's action bar then
draws over the top of the layout: the header was laid out at y=36..247 while `action_bar_container`
occupied y=0..295, so it was invisible with no error anywhere.

The fix is `Theme.Material3.DayNight.NoActionBar`, a `MaterialToolbar` in each layout passed to
`setSupportActionBar`, and `Insets.applySystemBars` applying `systemBars()` insets explicitly.
Do not go back to a decor-provided action bar.

`uiautomator dump` is how this was diagnosed. It reports each view's text and bounds, which
distinguishes "the view has no text" from "the view is covered", and those look identical on a
screenshot:

```
adb shell uiautomator dump /sdcard/win.xml && adb shell cat /sdcard/win.xml
```

### buildMap shadows a property named like a Map member

`FieldSpec.toMap()` used `buildMap { ... if (values.isNotEmpty()) put("values", values) }`. Inside
that lambda `values` binds to the **map's own** `values` collection, not the field's, so every
enum published a self-referential list:

```
values=[below, int, false, Fire when the level drops..., (this Collection)]
```

Android's `org.json` tolerated it, so `/schema` returned 200 on the device and the bug was
invisible; the reference implementation used in JVM tests throws, which is how it surfaced.
Every enum's allowed-value list was wrong in the machine-readable contract an authoring agent
reads, while the Kotlin-side validator kept reporting the right values.

Qualify the receiver (`this@FieldSpec.values`) whenever a `buildMap` body names a property that
`Map` also defines: `values`, `keys`, `entries`, `size`.

### Simulation must be given the trigger's facts

`/simulate` exists so a rule can be exercised without waiting for the real condition, and its
value depends entirely on running the same path production does. When trigger facts became
variables, `onEvent` seeded them but `fireNow` did not take a seed at all, so a simulated
`app_foreground` logged a bare `${vars.package}` while the real one resolved it.

That is the exact failure the design is meant to prevent, and it is invisible unless a test
compares the two paths. `fireNow` now takes the seed and `/simulate` passes `event.facts`. Any
future run entry point has to do the same.

### Testing a setting_changed rule that watches adb_wifi_enabled

The obvious test is destructive. Firing the rule needs `adb_wifi_enabled` to transition to 1,
which means setting it to 0 first, and that kills the very connection running the test with no
scripted way back. Do not do it.

Verify the observer on a throwaway key instead. `settings put global <anything>` works with
`WRITE_SECURE_SETTINGS` and a ContentObserver on its Uri fires normally:

```
settings put global kata_selftest 0    # rule with equals=1 must stay silent
settings put global kata_selftest 1    # rule must fire
settings delete global kata_selftest
```

That covers the machinery. The real tile toggle is then a one-off check for the user to run at
the phone, which is also the only safe place to run it.

### Reboot survival, verified

Tested on the S25 by rebooting and checking everything **before** opening the app, since
launching it would have started the service by hand and proved nothing. All of it held:

```
23:12:36 KataA11y: accessibility service connected, foreground=com.sec.android.app.launcher
23:12:40 KataTriggers: refreshing registrations for [battery_level, boot_completed, ...]
23:12:40 KataTriggers: scheduled 1 alarm(s)
23:12:40 KataService: engine up: 5 enabled
23:12:40 KataEngine: boot_completed matched 1 automation(s)
```

The rescheduled alarm was confirmed in `dumpsys alarm` rather than inferred from the log line:

```
RTC_WAKEUP #87: Alarm{... type 0 ... com.clearcmos.kata}
tag=*walarm*:com.clearcmos.kata/.triggers.AlarmReceiver
OW=2026-08-31 22:30:00.000
```

Two things worth knowing:

- **`pm grant` permissions survive a reboot.** Only a reinstall clears them, so the grant block
  belongs in the install cycle, not in a boot checklist. Special access granted through Settings
  (accessibility, DND, notification listener) survives too.
- **A reboot clears wireless debugging** and needs a tap on the phone to restore, matching the
  note in the user's global CLAUDE.md. `adb-reconnect` then reconnects by scanning for the new random
  port and re-arming 5555. Budget for that before rebooting a device you are driving remotely.

The `engine-heartbeat` automation exists to leave a dated record of this in the run log. The
main failure mode of a background automation engine is dying quietly, and a `boot_completed`
rule is the cheapest way to tell "the engine restarted" from "the engine has been dead for two
days".

### The accessibility service is unbound and rebound at will

The platform recycles an AccessibilityService on its own schedule. Observed in a normal session,
with nothing being reinstalled:

```
22:47:33 I KataA11y: accessibility service unbound
22:47:34 I KataA11y: accessibility service connected
```

Anything cached in `onAccessibilityEvent` is therefore lost without warning, and the first
symptom is subtle: the foreground-app condition reported "unknown" while the app was plainly in
front, and `app_background` silently stopped firing because the edge detector had no previous
value to compare against.

Two rules follow, and both are load-bearing:

- **Seed on connect.** `onServiceConnected` calls `activeApplicationPackage()` so the cache is
  populated before any event arrives. Without it the first app switch after every rebind emits
  no `app_background` at all.
- **Never read the cache from outside.** `currentPackage` queries the window list live and
  returns null when the service is not bound. The cached field is private and exists only so the
  event handler can tell a real switch from a repeat. A stale cached value is indistinguishable
  from a correct one at the call site.

Related: `event.packageName` on `TYPE_WINDOW_STATE_CHANGED` is whatever raised the event, which
includes keyboards, toasts and popups. Reading the active `TYPE_APPLICATION` window out of
`getWindows()` instead is what stops an IME appearing over an app from reading as an app switch.
That needs `flagRetrieveInteractiveWindows` in the service config.

Note also that the node carrying a label is usually not the clickable one, so `tap_ui` walks up
to the nearest clickable ancestor before performing `ACTION_CLICK`.

### The API token path

`ApiToken` mirrors the token to `getExternalFilesDir(null)`, which lands at
`/sdcard/Android/data/com.clearcmos.kata/files/api-token`. `adb shell` can read that path while
other apps cannot, which is what lets the CLI authenticate with no user step and without a
debuggable build. Verified on the S25:

```
adb shell cat /sdcard/Android/data/com.clearcmos.kata/files/api-token
```

Loopback binding alone is not sufficient authentication. Every app on the device can reach
loopback, and this API can write secure settings.

### aapt and the launcher icon

With `minSdk 34` the `-v26` qualifier on `mipmap-anydpi-v26` is dead, and lint fails on it with
`ObsoleteSdkInt`. But `mipmap-anydpi` alone does not satisfy aapt for a manifest icon reference
(`resource mipmap/ic_launcher not found`). The icon is fully vector, so there is no density
variance for mipmap to serve: it lives in `drawable/` and the manifest points at
`@drawable/ic_launcher`.

### ktlint code style

The gradle plugin's `android.set(true)` does not select the ktlint code style. Without
`ktlint_code_style = android_studio` in `.editorconfig`, ktlint defaults to `ktlint_official`,
whose wrapping rules (`multiline-expression-wrapping`, `class-signature`,
`chain-method-continuation`) disagree with the formatting the rest of the codebase uses and
produce dozens of unfixable violations.

Note that `ktlintFormat` rewrites files, so a `python3` patch script that matches a multi-line
Kotlin construct will fail after a format pass has collapsed it onto one line. Match on the
current text.

### Lint runs with warningsAsErrors

Several checks fire on things that are ordinary in this app and each needed a real fix rather
than a suppression:

- `Overdraw` on a list row's `?attr/selectableItemBackground`: use `android:foreground`, which
  is the correct modern placement anyway.
- `SetTextI18n`: every dynamic string needs a format resource. A port number must be `%s` and
  not `%d`, or `PluralsCandidate` reads it as a count.
- `UnusedResources`: this caught that the engine status was hardcoded to "Engine running"
  without ever checking. `KataService.isRunning` now backs it.
- `CoarseFineLocation`: `ACCESS_FINE_LOCATION` requires `ACCESS_COARSE_LOCATION` declared
  alongside it.

## Verification

```
nix develop --command gradle ktlintCheck :app:lintDebug        # format and lint
nix develop --command gradle :app:testDebugUnitTest            # unit tests
nix develop --command gradle :app:koverVerifyDebug             # coverage gate
nix develop --command gradle :app:assembleDebug                # build
nix develop --command gradle ktlintFormat                      # fix formatting
python3 -m unittest discover -s tests                          # CLI tests
ruff check cli tests && ruff format --check cli tests          # CLI lint and format
```

CI runs all of it on push and PR, plus `git diff --exit-code -- '*.lockfile'` so a dependency
cannot move without a reviewed lockfile diff.

### Test exemptions

Kover excludes these from the coverage gate because they are verified on the device rather than
on the JVM. Anything not listed here is expected to carry tests:

| Excluded | Why |
| --- | --- |
| `ui.*`, `databinding.*` | Activities and generated binding classes; checked by running the app |
| `KataService`, `Kata`, `Notifications` | Service lifecycle and notification channels; only meaningful on a device |
| `DeviceState` | The one real implementation of `DeviceReadings`; the interface is what conditions are tested against |
| `ActionRunner`, `SshClient` | Every branch performs a real device or network effect |
| `ApiToken` | Generates and mirrors a key to app storage |
| `Capabilities` | Every method reads live permission and system-service state. `CapabilityReporter`, the interface `ControlApi` depends on, is tested through a stub |
| `triggers.*` | Broadcast receivers, the accessibility service, and alarm scheduling |

The line to hold: logic that can run on the JVM lives in a class that does. `Engine` and
`ControlApi` both take their collaborators through the primary constructor for exactly this
reason, with a `Context` convenience constructor that assembles the real ones.

### Decisions

- **2026-08-31, changelog**: no `CHANGELOG.md`. Commit messages carry the reasoning and git
  history is the record; a hand-maintained changelog for a single-consumer app would drift.
- **2026-08-31, dependency updates**: dependabot opens monthly PRs for github-actions and
  gradle. Versions live only in `gradle/libs.versions.toml` and the `*.lockfile` set; the flake
  pins the JDK, Gradle, and SDK separately and is bumped by hand.
- **2026-08-31, coverage floor 90**: measured 92.9% at the time. Ratchet upward only.
- **2026-08-31, real org.json in tests**: `android.jar` ships stubs that return null under
  `isReturnDefaultValues`, so every persistence test silently passed while writing nothing.
  `testImplementation(libs.json)` shadows the stub.

## Build and deploy

```
nix develop --command gradle assembleDebug testDebugUnitTest ktlintCheck lintDebug
nix develop --command gradle ktlintFormat
```

`buildToolsVersion` is pinned in `app/build.gradle.kts` so the read-only nix store SDK is never
asked to fetch another revision, and `GRADLE_OPTS` in `flake.nix` points aapt2 at the SDK copy
for the same reason.

`adb install -r` clears every permission granted with `pm grant`. The full cycle:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.clearcmos.kata android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.clearcmos.kata android.permission.POST_NOTIFICATIONS
adb shell pm grant com.clearcmos.kata android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.clearcmos.kata android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.clearcmos.kata android.permission.BLUETOOTH_CONNECT
adb shell am start -n com.clearcmos.kata/.ui.MainActivity
```

The rule set and run log survive a reinstall; they live in `filesDir`, not in the APK.

## Testing a change end to end

`simulate` is the fastest loop, because it does not need the real condition to occur:

```
cli/kata push
cli/kata simulate wifi_connected ssid=TestNet
cli/kata simulate battery_level level=19 prev_level=21
cli/kata fire nightly-dnd --dry
cli/kata runs --limit 5
```

Unit tests cover the pure logic only: `Validator`, `Clock`, and `TriggerMatcher`. Anything
touching an Android API is verified on the device through the CLI.
