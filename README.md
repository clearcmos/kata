# kata

An Android automation runtime with no rule builder. Automations are JSON files in this repo,
pushed to the phone over adb, and run on-device by a foreground service. The phone app shows
what is installed, what fired, and what failed, and lets you flip rules on and off.

Developed against a Galaxy S25 (SM-S931W) on Android 16 / One UI 8.5. Nothing in it is Samsung
specific.

## Why it is shaped this way

Tasker and its open-source imitators spend most of their complexity on a visual rule builder,
because a person composing logic through a touchscreen needs one. If the rules are written
somewhere else, that entire surface is dead weight.

So kata inverts it: the device is an API-first runtime with a status screen, and authoring
happens on a workstation against a repo, with an agent or an editor. What the phone keeps is
the two things it is genuinely better at, observation and a fast on/off switch.

The control API is the reason this works rather than just being a config loader. It answers
with what it accepted, what it refused and why, what the device can actually do, and what
happened on every run:

```
$ kata validate
ok  arrive-home
ok  charger-connected
ok  low-battery-alert
ok, ungranted  nightly-dnd
      dnd_policy: Settings > Notifications > Do Not Disturb > App access > kata
```

A rule with mistakes comes back with all of them at once, not the first one:

```json
{
  "valid": false,
  "problems": [
    "trigger has unknown trigger type 'wifi_conected' (did you mean 'wifi_connected'?)",
    "actions[0].titel is not a field of 'notify' (did you mean 'title'?)",
    "actions[0] is missing required field 'title' (Notification title.)",
    "actions[1].mode must be one of off, priority, none, alarms, got \"loud\""
  ],
  "unmet": ["dnd_policy: Settings > Notifications > Do Not Disturb > App access > kata"]
}
```

## An automation

One JSON file per automation in `automations/`. The file, the request body, and what
`GET /automations` returns are the same shape, so nothing translates between the repo and the
device.

```json
{
  "id": "low-battery-alert",
  "name": "Low battery alert",
  "description": "Warn once when the battery falls past the threshold, unless already charging.",
  "enabled": true,
  "trigger": { "type": "battery_level", "below": 20 },
  "conditions": [
    { "type": "charging", "value": false }
  ],
  "actions": [
    { "type": "notify", "id": 2001, "title": "Battery low", "text": "Under ${params.threshold}%." },
    { "type": "vibrate", "ms": 600 }
  ],
  "params": [
    { "key": "threshold", "label": "Warn under (%)", "type": "int", "value": "20" }
  ]
}
```

`params` are the values you can change from the phone without touching the repo. Any string
field can reference one as `${params.key}`; substitution happens before validation and before
every run.

Because the phone owns those values, `kata push` carries them across: a sync updates each
param's label, type, and the default for a newly declared key, but keeps a value you set on the
device. `kata push --reset-params` overwrites them with the repo's instead.

Conditions are ANDed. Actions run in order and stop at the first failure, because later actions
generally assume the earlier ones landed.

## Vocabulary

18 triggers, 10 conditions, 21 actions. `kata schema` prints all of them with their fields and
prerequisites. The short version:

- **Triggers**: `manual`, `boot_completed`, `power_connected`, `power_disconnected`,
  `battery_level`, `screen_on`, `screen_off`, `wifi_connected`, `wifi_disconnected`,
  `bluetooth_connected`, `bluetooth_disconnected`, `headset_plugged`, `headset_unplugged`,
  `airplane_mode`, `time_of_day`, `interval`, `notification_posted`, `notification_removed`
- **Conditions**: `time_between`, `day_of_week`, `battery_below`, `battery_above`, `charging`,
  `screen_on`, `wifi_ssid`, `wifi_connected`, `dnd_active`, `app_installed`
- **Actions**: `notify`, `cancel_notification`, `dnd`, `ringer_mode`, `volume`, `media`,
  `vibrate`, `torch`, `tts`, `http_request`, `launch_app`, `start_activity`, `broadcast`,
  `clipboard`, `secure_setting`, `global_setting`, `system_setting`, `wake_screen`, `wait`,
  `log`, `set_enabled`

`secure_setting` and `global_setting` are the wide ones. With `WRITE_SECURE_SETTINGS` granted
over adb, they reach settings that normally need root or Shizuku.

## The CLI

`cli/kata` sets up the adb port forward, reads the API token off the device, and talks to the
engine. Python 3 standard library only.

```
kata push [--reset-params]         replace the device rule set with automations/
kata validate                      check automations/ against the device, install nothing
kata list                          what is installed, armed, and how it last ran
kata show <id>                     one automation as the device holds it
kata fire <id> [--dry]             run it now
kata simulate <type> [k=v ...]     inject a fake trigger and see what matches
kata runs [--id X] [--limit N]     recent run records, step by step
kata enable|disable <id>           arm or disarm
kata param <id> <key> <value>      change a parameter
kata delete <id>                   remove one automation
kata capabilities                  what this device can actually do right now
kata schema                        the full vocabulary
kata doctor                        check the whole path from here to the engine
```

`push` is all or nothing. If any automation fails validation, nothing is installed and every
problem is reported, because a partially applied sync leaves the phone matching neither the
repo nor any intended state.

## Control API

Loopback only, on port 8770, reached through `adb forward tcp:8770 tcp:8770`. Every request
needs an `X-Kata-Token` header. The token is generated on first run and mirrored to
`/sdcard/Android/data/com.clearcmos.kata/files/api-token`, which `adb shell` can read and other
apps cannot.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | liveness, the only unauthenticated route |
| GET | `/capabilities` | device facts, grant state, vocabulary annotated with what is available |
| GET | `/schema` | the vocabulary alone |
| GET | `/automations` | everything installed |
| PUT | `/automations` | replace the whole set, atomically |
| POST | `/automations` | install or replace one |
| GET | `/automations/{id}` | one automation |
| DELETE | `/automations/{id}` | remove one |
| POST | `/automations/{id}/enable` | arm |
| POST | `/automations/{id}/disable` | disarm |
| POST | `/automations/{id}/fire` | run now, `{"dry_run": true}` to evaluate without acting |
| POST | `/automations/{id}/params` | set one parameter |
| POST | `/validate` | check an automation without installing it |
| POST | `/simulate` | inject a trigger event, get back what matched and every run record |
| GET | `/runs` | recent runs, `?id=` and `?limit=` |
| DELETE | `/runs` | clear the run log |

## Setup

```
nix develop --command gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.clearcmos.kata android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.clearcmos.kata android.permission.POST_NOTIFICATIONS
adb shell pm grant com.clearcmos.kata android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.clearcmos.kata android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.clearcmos.kata android.permission.BLUETOOTH_CONNECT
```

Open the app once so the service starts and the token is written, then `cli/kata doctor`.

`adb install -r` clears every permission granted with `pm grant`, so the grants above are
re-run after each install, not just the first.

Three prerequisites cannot be granted over adb and need a tap on the phone. `kata capabilities`
names each one and where it lives in Settings:

- Do Not Disturb access, for the `dnd` and `ringer_mode` actions
- Notification access, for the `notification_posted` and `notification_removed` triggers
- Modify system settings, for the `system_setting` action

On One UI, also exclude kata from "Put unused apps to sleep" (Settings > Battery > Background
usage limits). Samsung will otherwise stop the service after a day or two of the app not being
opened, and rules will quietly stop firing.

## Build

```
nix develop --command gradle assembleDebug
nix develop --command gradle testDebugUnitTest
nix develop --command gradle ktlintCheck lintDebug
nix develop --command gradle ktlintFormat
```

Lint runs with `warningsAsErrors = true`.

## Limits

- Anything needing root or Shizuku is out of reach: toggling Wi-Fi or mobile data, force-stopping
  apps, granting permissions. What `WRITE_SECURE_SETTINGS` covers is covered; the rest is not.
- No scripting. Rules are declarative, and gaps are closed by adding a typed action rather than
  by an escape hatch. `http_request` covers most of what a script would have been used for by
  moving the logic to a server.
- `interval` triggers can be deferred by Doze while the screen is off. `time_of_day` uses exact
  alarms and does not drift.
- Reading the connected Wi-Fi SSID needs location permission and location switched on. Without
  it `wifi_connected` still fires, but rules that match on `ssid` never match.
