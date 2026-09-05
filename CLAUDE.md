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

What is reachable, in rough order of what it costs to obtain:

| Tier | Cost | Unlocks |
| --- | --- | --- |
| Normal and runtime permissions | nothing, or one dialog | most of the current vocabulary |
| `WRITE_SECURE_SETTINGS` | one `adb shell pm grant`, re-run after each install | `secure_setting`, `global_setting` |
| Settings special access | a toggle in Settings, or `appops set` over adb for some | DND policy, notification listener, modify system settings, appear on top (`launch_app`, `start_activity`) |
| AccessibilityService | a toggle in Settings | **implemented**: `app_foreground` / `app_background` triggers, `app_foreground` condition, `global_action` and `tap_ui` actions |
| Shizuku | user starts it; re-armed over adb after a reboot | ADB shell privilege: `svc`, `pm`, `am force-stop`, `cmd` |

Shizuku is not root. It runs at ADB shell privilege and is available on this device. With a
reconnect script and a wireless-debugging Quick Settings tile in place, re-arming it after a
reboot is one command, so it stays a live option.

Genuinely out of reach under this ceiling: toggling Wi-Fi or mobile data through an API
(removed for third-party callers in Android 10), force-stopping another app, and connecting or
disconnecting one Bluetooth audio device (`BluetoothDevice.connect()` is behind
`BLUETOOTH_PRIVILEGED`). Say so in one line and stop there. Reading which audio devices are
connected is public API and is what the `bluetooth_connected` condition does; the connect itself
runs on the workstation at adb shell privilege, asked for over `ssh`.

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

### startForegroundService returns before the service exists

`MainActivity` starts the engine then immediately renders its status from
`KataService.isRunning`. That flag is set in the service's `onCreate`, which has not run yet
when `startForegroundService` returns, so the first paint after a cold start showed
"Engine stopped" with a Start engine button while the engine was already serving requests.
Nothing re-rendered afterwards, so the wrong status stuck until the activity was left and
re-entered.

The status itself was correct at the instant it was read, which is what makes this kind of bug
persist: every individual piece behaves, and only the timing is wrong. `awaitEngine` now
re-renders as soon as the flag flips, and gives up after a bounded number of polls so a genuine
failure to start still shows as stopped.

Sampling the UI over time is what catches it; a single screenshot after a fixed sleep can land
on either side:

```
adb shell am force-stop <pkg> && adb shell am start -n <pkg>/.ui.MainActivity
adb shell uiautomator dump /sdcard/w.xml && adb shell cat /sdcard/w.xml
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

That covers the machinery. The real tile toggle is then a one-off check to run at
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

The matched automation was the `engine-heartbeat` rule that has since been removed; the engine
now writes its own start record instead, so a boot matching nothing is normal.

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
  note above. A reconnect script then finds the new random
  port and re-arming 5555. Budget for that before rebooting a device you are driving remotely.

The engine leaves a dated record of every start in the run log itself, under the id `engine`
(`kata runs --id engine`). The main failure mode of a background automation engine is dying
quietly, and that record is what tells "the engine restarted" from "the engine has been dead
for two days". It used to be a `boot_completed` rule called `engine-heartbeat`; it became an
engine concern so that the automation list shows only rules the user chose, which is also how
MacroDroid's system log and Tasker's Monitor Start entry handle it.

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

### launch_app reported success while the platform dropped the launch

`startActivity()` from the foreground service returns normally when Android refuses a
background activity launch. The action logged `launched com.example` and nothing appeared; the
only evidence was in logcat:

```
ActivityTaskManager: Background activity launch blocked! goo.gle/android-bal [callingPackage: com.clearcmos.kata ...]
```

A foreground service alone is not an exemption. "Appear on top" (`SYSTEM_ALERT_WINDOW`) is, and
it is grantable over adb with `appops set`, so it is now a `Requirement` on `launch_app` and
`start_activity`, the install block grants it, and the action fails with the remedy instead of
claiming a launch the platform never performed. A locked phone still defers the launched
activity until the next unlock; that is the platform, not kata.

### The Wi-Fi SSID is not readable from the engine

`ACCESS_FINE_LOCATION` granted and the location master switch on are not enough. The platform
withholds the network name from a caller that does not also hold background location, and it
withholds it identically whether the read comes from the service or with the activity in front,
so there is no window in which it works:

```
skipped_conditions  probe
  ERR condition:wifi_ssid: ssid unreadable; grant location to match on it
```

`wifi_ssid` at least says so. The ssid filter on `wifi_connected` is the worse half, because a
trigger that never matches is indistinguishable from a rule nobody wrote.

Declaring `ACCESS_BACKGROUND_LOCATION` would fix it and was declined: always-on location is a
large grant to buy one string. `ip_address` identifies a network instead, needs only
`ACCESS_NETWORK_STATE`, and matches against a DHCP lease, which is usually what a rule means by
"this network" anyway. Both spec doc strings now say this, because a field that fails silently
is an authoring trap and the doc strings are what an authoring agent reads.

### Registering the network callback replays the current network

`registerNetworkCallback` delivers `onCapabilitiesChanged` for a matching network that is
already connected, so `wifi_connected` fires within a millisecond of the first
`TriggerRegistry.refresh()` after the engine starts, as well as on a real join:

```
00:40:55.189 KataTriggers: refreshing registrations for [..., wifi_connected, ...]
00:40:55.190 KataEngine: wifi_connected matched 1 automation(s)
```

That is what covers a device joining Wi-Fi before the engine is up, which is the normal order
after a reboot, and it is the only reason a Wi-Fi rule survives one. The replayed event carries
no SSID, and `announcedWifi` is set on that first callback so a later one that might carry the
name is suppressed. A rule that has to work on this path cannot filter on ssid.

The same field is why a later refresh does not fire the rules again: a `kata push` rebuilds the
callback, the platform replays the same network into it, and the replay is dropped as already
announced. Verified by pushing an unchanged rule set and reading the run log. Only an engine
start replays, which means a reboot, a reinstall, or the app being force-stopped and reopened.

### A wifi_connected rule runs before Wi-Fi is the default route

Rejoining Wi-Fi leaves mobile data as the default network until the new link validates.
`wifi_connected` fires before that, so whatever the rule reads or tries to reach in its first
moments still sees the carrier's network:

```
skipped_conditions  wireless-debug-home  (3ms, via wifi_connected)
  ERR condition:ip_address: ip=100.64.x.x, want 192.0.2.13
```

That address was in the RFC 6598 shared address space on `rmnet_data0`, the carrier's CGNAT
lease. The range reads like a Tailscale address
and is not one; there was no tun interface on the device at all. `ip -4 addr` settles it and a
guess does not.

`ipAddress()` had asked `getLinkProperties(activeNetwork)`. It now asks the Wi-Fi network
directly and skips VPNs, which report the transports of the network beneath them and would
otherwise match a Wi-Fi test.

The same window reaches actions, where reading a different network is no help: a LAN address is
unroutable over cellular, so the rule's own `ssh` check failed and stopped the run before the
write it was gating. Hence the leading `wait` and the `retry` on that step.

What makes this class of bug expensive is that every cheap test passes. Firing the rule by hand
works, and so does the engine-start replay, because both run long after the switchover. Only a
real join has the window, and on this device exercising a real join costs the adb session.

### A rule cannot wait for a sibling matched by the same event

`q30-connect-on-home` needs adb to the phone, and adb at home is brought up by
`wireless-debug-home`. Both match `wifi_connected`, and the engine runs matched rules one at a
time in store order, so whichever is queued second does not start until the first finishes. A
step in the Q30 rule that blocked until adb answered would therefore have held up the rule
that turns adb on, and the only reason it would not have been a deadlock is the timeout.

The rule hands the work off instead: its `ssh` step asks the workstation to queue the job as a
detached user unit and returns at once, and the unit does the waiting. The cost is that the
outcome lives in the workstation's journal rather than the run record, which the rule's
description says. The general form: an action that depends on the effect of another rule fired
by the same event must not block on it.

### Wireless debugging is scoped to one AP, and the platform closes it

There is no need for a rule that turns wireless debugging off when the device leaves Wi-Fi. The
framework authorises it against a single access point and records which one:

```
$ adb shell dumpsys adb
debugging_manager={
  connected_to_adb=true
  keystore=... wifiAP/ bssid aa:bb:cc:dd:ee:ff
```

`AdbDebuggingManager` keeps that BSSID so it can write `adb_wifi_enabled` back to 0 once the
device is no longer on that AP. It is stricter than a rule on `wifi_disconnected` would be,
because a roam to another access point changes the BSSID without the network being lost at all.

So the only direction worth automating is on. An off rule would duplicate platform behaviour,
and a rule that duplicates the platform is one that eventually gets blamed for something it did
not do.

### An SSH action's command is a request, not a guarantee

Read the target's `authorized_keys` before assuming an `ssh` action runs what it asks for. A key
pinned with `restrict,command="..."` ignores the `command` field entirely and runs the forced
command instead.

That turned a cheap reachability check into the one command guaranteed to fail in context: the
forced command was the adb reconnect script, the check runs *before* wireless debugging is
switched on, and the script scans for a port that is not open yet and exits non-zero. A failed
action stops the run, so the check aborted the very write it was there to gate.

sshd still exports the requested command as `SSH_ORIGINAL_COMMAND`, so the fix belongs on the
workstation rather than in the rule: the forced command answers a `check` verb immediately and
falls through to its real work otherwise. No `authorized_keys` change, and the existing rule
that asks for the real command is unaffected.

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
nix run nixpkgs#actionlint -- .github/workflows/ci.yml         # lint the workflow itself
```

`ruff` is not installed on this machine; run it at the version CI pins with
`uvx ruff@<RUFF_VERSION> ...`, reading the version out of `.github/workflows/ci.yml`.

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

### Device-only state

Parameter values, persisted variables, and the armed/disarmed flag live only on the phone; the
repo cannot rebuild them. A `kata push` carries the flag and the parameter values over from the
installed copy of each rule, so syncing the repo neither re-arms a rule disarmed on the phone
nor overwrites a value typed there; the file's `enabled` only seeds a rule the phone has not
seen before. `kata pull` captures all three things and nothing else, and `kata restore` puts a
pulled state back onto a device that has lost it: a wiped app, a `--reset-params` push, or a
new phone.

The pulled file carries no timestamp, so a pull that changes nothing produces no diff and real
changes stay visible in the destination repo's history. Rule bodies are excluded on purpose: a
second copy of the rules would eventually disagree with `automations/`.

Run `kata pull` after changing anything on the phone, which mostly means editing a parameter or
toggling a rule.

That split is also what keeps this repo publishable, so it holds for docs and tests too. No real
network name, address, or hardware address belongs in a file here: a rule gets a
`${params.key}` placeholder, and an example or a test fixture uses a documentation-reserved
address (`192.0.2.0/24`, RFC 5737) rather than a real lease. A run record pasted into a finding
gets the same treatment; the lesson in one never depends on the actual value. An access-point
BSSID is the one to be most careful with, because public wardriving databases map it to a
street address.

### Decisions

- **2026-08-31, changelog**: no `CHANGELOG.md`. Commit messages carry the reasoning and git
  history is the record; a hand-maintained changelog for a single-consumer app would drift.
- **2026-08-31, dependency updates**: dependabot opens monthly PRs for github-actions and
  gradle. Versions live only in `gradle/libs.versions.toml` and the `*.lockfile` set; the flake
  pins the JDK, Gradle, and SDK separately and is bumped by hand. See "Dependency updates".
- **2026-08-31, coverage floor 90**: measured 92.9% at the time. Ratchet upward only.
- **2026-08-31, ruff must be told about `cli/kata`**: the CLI is an executable script with no
  `.py` extension, so ruff matched nothing under `cli/` and reported success while linting only
  the test file. `extend-include = ["cli/kata"]` in `pyproject.toml` fixes it; the first real
  run then found four errors. A linter that passes without reading the code is worse than none.
- **2026-08-31, real org.json in tests**: `android.jar` ships stubs that return null under
  `isReturnDefaultValues`, so every persistence test silently passed while writing nothing.
  `testImplementation(libs.json)` shadows the stub.
- **2026-09-04, rule names**: `name` is kebab-case and equal to `id`, concise but descriptive
  (`q30-connect-on-home`, not `Connect Q30`). The CLI list and the app list both sort by name,
  so the name is what a reader scans. Existing ids were not renamed: parameter values on the
  phone are keyed by id and a rename would orphan them.
- **2026-09-04, the list holds only rules the user chose**: no diagnostic or infrastructure
  rules. The engine records its own start in the run log under the id `engine`; anything else
  of that kind belongs in the engine too, not in `automations/`.
- **2026-09-04, a push carries the armed flag**: `enabled` in a file only seeds a rule the phone
  has not seen. Before this a sync re-armed every rule disarmed on the device, and the fix was
  the same one-line merge params already had, not a per-rule checksum: rewriting an unchanged
  body is harmless, so there was nothing to skip.

## Dependency updates

Dependabot bumps `gradle/libs.versions.toml` but cannot regenerate the Gradle lockfiles, so the
lock still pins the old version and resolution fails before any check runs:

```
Could not resolve com.google.android.material:material:1.14.0.
  Cannot find a version that satisfies the version constraints
```

CI refreshes the locks on a dependabot branch and commits them, inside the same job that then
builds. That placement is deliberate: a push made with `GITHUB_TOKEN` does not retrigger
workflows, so doing it in a follow-up job would leave a correct branch wearing a stale red
check.

One approval click remains per Gradle PR. Pushing the refreshed lock moves the branch head, and
GitHub holds the run it queues for a bot-authored commit as `action_required`. The run that did
the relock has already gone green on the same content, so approving is a formality:

```
gh api -X POST repos/clearcmos/kata/actions/runs/<id>/approve
```

If the refresh step is ever unavailable, the manual procedure is to check the branch out and run:

```
nix develop --command gradle dependencies :app:dependencies --write-locks
```

then commit the `*.lockfile` changes onto the PR branch.

AGP major versions are ignored by dependabot on purpose. AGP 9 changes the Kotlin plugin wiring
and requires Gradle 9, which is a deliberate migration rather than something to take in a
monthly PR. Close such a PR rather than merging it.

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
adb shell appops set com.clearcmos.kata SYSTEM_ALERT_WINDOW allow
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

Unit tests cover the pure logic only: `Validator`, `Clock`, `TriggerMatcher`, `ConditionEvaluator`
against a fake device, `Engine` sequencing against a stub executor, `ControlApi` routing, and
the device-state carry-over on a sync. Anything touching an Android API is verified on the
device through the CLI.
