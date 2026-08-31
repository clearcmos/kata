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
