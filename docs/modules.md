# Making a module

A module is a normal Android TV app that declares a card for this launcher. Your repo, your
licence, your signing key, released as an APK wherever you like, for example on your GitHub
Releases page with its SHA-256. The TV owner sideloads it like any other app. The launcher finds the
card and asks the owner once; if they answer Show, your app appears as a card from then on: your
label, an optional caption, and your colour in the ambient glow, placed ahead of the alphabetical
grid.

The contract is [`docs/specs/card.md`](specs/card.md). This page is the short version.

## 1. Declare the card

On the activity that already has the `LEANBACK_LAUNCHER` filter, add a second filter and a
`meta-data` entry:

```xml
<activity android:name=".LibraryActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="com.gathin.tvhome.category.DOCK_CARD" />
    </intent-filter>
    <meta-data android:name="com.gathin.tvhome.card" android:resource="@xml/module_card" />
</activity>
```

Then create `res/xml/module_card.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<card spec="1" label="Retro" caption="NES games" accent="#E8A33D" />
```

- `label`: 1 to 24 characters, letters, digits, space and `. & + ' -`. Keep it short; it is the
  name on the tile and in the top shelf. Words like "verified" or "official", the launcher's own UI
  names and input names such as HDMI are refused, and so is a new card whose label (or whose app
  label) is the name of another app installed on the TV (the full list is in the spec). Changing
  the label in an update makes the owner decide again, so pick it once.
- `caption`: optional, up to 32 characters from the same set as `label` — letters, digits, space and
  `. & + ' -`, so a `/`, a comma or a colon is not read. A character outside the set drops the whole
  card rather than showing it without the caption (`logcat -s TvHomeCards` says `caption has
  characters outside A-Z a-z 0-9 space . & + ' -`). The
  endorsement words refused in labels are refused here too. The
  name line under the tile is one line; label and caption together show about 26 characters on a
  4 column grid, so keep the caption to a few words. `tests/test_addon_module_cards.py` holds every
  card under `addons/` to these rules.
- `accent`: optional `#RRGGBB`, the glow colour behind the card while it is focused in the dock.
- Write the values as literal text. `@string` references are not resolved in spec 1.

The card must sit on the same activity as your `LEANBACK_LAUNCHER` filter, and that activity must
be exported and enabled. Otherwise the card is dropped.

## 2. Keep it light

The launcher targets TVs with 1 GB of RAM, and the owners trust what they put on their home screen.

- No background services unless the feature needs one, and say so in your README.
- No telemetry without an opt-in. No ads.
- Do not bundle content you have no right to redistribute.
- Use your own art. Do not use other companies' logos as your banner or icon.
- Describe every permission you request in your README.

## 3. Test on a TV

Install over ADB, press Home, and the prompt appears:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell input keyevent KEYCODE_HOME
```

(This repo's `tools/tv.py` works where the `adb` binary is blocked; see the README.)

Useful checks:

```bash
# Did the card filter and the meta-data land on the right activity?
adb shell pm dump io.github.udhaykumarbala.retro | grep -B2 -A6 DOCK_CARD

# Why was a card rejected? One line per reason.
adb logcat -s TvHomeCards
```

Then: answer Keep hidden and check the app keeps its ordinary tile; hold OK on Settings, open the
Module cards list, set it to Shown, and check the card takes the tile's place; press OK on the card
and check your app opens; uninstall and check the card is gone. Take a screenshot of the card
focused in the grid for your README.

## 4. Release and keep your key

The owner's answer is pinned to your signing certificate. Publish the certificate digest with every
release so people can check what they install:

```bash
apksigner verify -print-certs app-release.apk | grep 'SHA-256'
```

(`apksigner` accepts its options with one dash as well as two.)

The line "Signer #1 certificate SHA-256 digest" is what the launcher computes on the TV. Sign every
update with the same key: a different key makes every TV ask again, and the card stays hidden
until the owner answers. Sign with one certificate; the launcher rejects APKs with several signers.
Keep the keystore out of your repo.

Publish the APK's own SHA-256 next to it as well.

## Reference module

Retro, an NES library for the TV, declares a card exactly as above:
<https://github.com/udhaykumarbala/tv-vibehack-retro>.

A launcher that wants other apps' Continue watching rows can read them, see
[`docs/watch-next.md`](watch-next.md).

## Each team's next game on Home (game_day)

An app can hand Home a short list of upcoming events, which the launcher shows in the F1 panel's place under the
clock and rotates through, with the theme's F1 weekend as one more turn. The launcher only reads; it never writes
to the app and never goes online, so the app does the fetching. The owner opts in by naming the app's package in
the `game_day` key (`config.xml` or a theme); there is no card prompt for it.

1. Name a read-only content provider in a `meta-data` on your `<application>`:

   ```xml
   <meta-data android:name="com.gathin.tvhome.games" android:value="com.example.games" />
   <provider android:name=".GamesProvider" android:authorities="com.example.games" android:exported="true" />
   ```

   The provider must belong to the package the owner named; the launcher ignores an authority another app holds.
2. Answer a query on `content://<authority>/turns` with these columns, one row per turn, sorted by start:

   | Column | Type | What |
   |---|---|---|
   | `top_line` | text | The small top line, for example "Minnesota Wild at Dallas Stars". Rows without it are skipped. |
   | `word` | text | The word before the countdown: "Puck drop" shows "Puck drop in 2h 18m". Empty shows the countdown alone. |
   | `start_ms`, `end_ms` | integer | Epoch milliseconds. Between them the panel says "Live now"; after `end_ms` the row is dropped. |
   | `colour` | integer | ARGB of the short bar. |
   | `calendar` | text | Where the row came from; not shown. |
   | `turn_seconds` | integer | Seconds per turn, 10 to 120; anything else means 30. Read from the first row. |

   An empty cursor hides the panel. Keep it to what Home shows anyway: the provider is exported without a
   permission, so it must hold no links, accounts or other private data.
3. Call `notifyChange` on that URI when the rows change. The launcher queries when Home comes back, when Home is
   pressed on Home, right after a row ends, and on each change, and watches only while Home is on screen.

The [Game Day](../addons/README.md) add-on implements this from calendar links the person adds.

## The weather now on Home (weather_now)

An app can hand Home the weather for one place, which Home shows as a single line under its clock:
a sky symbol, the temperature and the place, say "☁ 14° Takizawa". The launcher only reads; it never
writes to the app and never goes online, so the app does the fetching. The owner opts in by naming the
app's package in the `weather_now` key (`config.xml` or a theme). There is no card prompt for it, and an
empty key means no line at all.

1. Name a read-only content provider in a `meta-data` on your `<application>`:

   ```xml
   <meta-data android:name="com.gathin.tvhome.weather" android:value="com.example.weather" />
   <provider android:name=".NowProvider" android:authorities="com.example.weather" android:exported="true" />
   ```

   The provider must belong to the package the owner named; the launcher ignores an authority another app
   holds. Your app also has to be *visible* to the launcher. It asks the package manager about the package
   the owner named, and from Android 11 that only answers for apps matching the launcher's own `<queries>`:
   an app with a `LAUNCHER`, `LEANBACK_LAUNCHER`, `DOCK_CARD` or `HOME` activity, or one that ships a card.
   Every add-on here has a launcher activity, so this costs nothing in practice — but an app that ships only
   a provider, and no activity of those kinds, is invisible and the row never appears. There is nothing you
   can declare in *your* manifest to fix that; the filter lives in the launcher's.

2. Answer a query on `content://<authority>/now` with one row and these columns:

   | Column | Type | What |
   |---|---|---|
   | `place` | text | The place's name, shown after the temperature. Empty shows the temperature alone |
   | `temperature` | integer | Degrees, rounded, in the app's own unit; the launcher adds only "°" |
   | `unit` | text | `C` or `F`; not shown |
   | `code` | integer | The WMO weather code, which picks the sky symbol |
   | `is_day` | integer | 1 by day, 0 at night (a moon for a clear night) |
   | `updated_ms` | integer | Epoch milliseconds of the reading. A row without it, or older than three hours, hides the line |
   | `sky` | text | *Optional.* The sky in words, e.g. `Overcast`, shown after the temperature |
   | `humidity` | integer | *Optional.* Relative humidity in percent, shown as `Humidity 57%` |
   | `precipitation_chance` | integer | *Optional.* Chance of precipitation in percent, drawn last and only from 30% upward: `… · Humidity 57% · Rain 60%` |
   | `snow_chance` | integer | *Optional.* Chance of snow in percent; it wins over the rain figure and the word is not repeated when the sky already says snow |

   An empty cursor hides the line, and so does a `null` in any of the optional columns for whichever part
   it feeds: the four after `updated_ms` are there for a provider that can do better, not as a requirement.
   Keep it to what Home shows: the provider is exported without a permission, so it must hold no
   coordinates, accounts or other private data. Columns are read by name, so their order does not matter.

3. Call `notifyChange` on that URI when a new reading arrives. Home queries when it comes back, on each
   change, and again if the line it is holding passes three hours while Home stays on screen; it watches
   only while it is on screen, and a query is the app's cue to refresh a reading that is getting old.

   **Give the app a clock of its own as well** — a `JobScheduler` or `WorkManager` job every 15–20 minutes,
   and pass the flex argument explicitly: `setPeriodic(20 min)` alone sets flex equal to the interval, so the
   system may use the whole window and a job asked for every 20 minutes can first run at 30.
   Home is only one of the things that can be on screen, and while another app is in front the launcher
   performs no queries at all: a provider that refreshed only on a read went a day stale on a set left in
   YouTube, and the line vanished the moment Home came back, because the launcher hides a reading older than
   three hours. Log a failed fetch too: a failure nobody can read in logcat looks exactly like a renderer
   that dropped the row, and that is a day spent looking in the wrong file.

A reading belongs to the place it was fetched for: if the person picks another city while a fetch is still
running, the running fetch must not publish under the new city's name. Publish only while the selection it
started with is still the current one, and hand an overtaken fetch off to the newest city.

The [Weather Glance](../addons/weather-glance/README.md) add-on implements this from Open-Meteo. The column
names are deliberately the same as the ones the `weather_now` fork entry uses, so one add-on serves both.
