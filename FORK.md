# This fork: the weather under the clock

A fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) that leaves the launcher's own behaviour
alone and adds one thing: **the weather for one place, as a single line under Home's clock**.

```
GOOD AFTERNOON
17:42
SATURDAY 1 NOVEMBER
☁ 14° Takizawa
```

## What is different

| Part | What it does |
|---|---|
| `weather_now` | A launcher key naming the package of an add-on that publishes the weather. Empty by default. The launcher still asks for no `INTERNET` permission and goes online for nothing |
| `addons/weather-glance` | The add-on: pick one place once and it publishes the reading. Open-Meteo, no key, no account, no card prompt |

The columns are [@javierfn](https://github.com/javierfn)'s `weather_now` contract, unchanged, so the same
add-on can serve both forks rather than two that drift apart: `place`, `temperature`, `unit`, `code`,
`is_day`, `updated_ms`. Home draws the sky symbol for the WMO code, rounds the temperature and adds only
"°", then the place; a reading older than three hours hides the row, and so does an empty key, so a TV
without the add-on looks exactly as it did.

Two things the upstream attempt did not get right are fixed here:

- **A paused Home cannot apply a stale reading.** Stopping the observer bumps a generation, and a query that
  was already running or already posted declines itself at the worker *and* at delivery, so a stop → start
  never shows the previous session's line.
- **A reading belongs to the place it was fetched for.** Picking another city while a fetch is in flight
  publishes nothing under the new name: the worker freezes the place, the coordinates and a selection
  generation when the request is made, stores only while that generation is current, and hands an overtaken
  fetch off to the newest city — including when the overtaken one failed.

## Install

1. Build the launcher from this fork (`launcher/`, JDK 17 and the Android SDK) and install it as the Home
   app, as the main repo's README describes.
2. Build and install `addons/weather-glance`, open it once and pick your place.
3. Name it in your theme or `config.xml`:

   ```json
   { "weather_now": "io.github.lostininaka.weatherglance" }
   ```

Nothing else in the launcher changes, so an existing theme keeps working: the key is empty unless you set it.

## Credits

The launcher, its themes and its add-on contract are [Gathin23](https://github.com/Gathin23)'s work. The
`weather_now` column contract and its name are [@javierfn](https://github.com/javierfn)'s, and both of
[@SaviorOneZero](https://github.com/SaviorOneZero)'s lifecycle and selection reports are what the two guards
above answer. MIT, as the repo it forks.
