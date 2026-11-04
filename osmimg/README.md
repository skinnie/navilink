# osmimg

OpenStreetMap → Garmin `.img` maps, shared by NaviLink, bbnav and any other project.
Plain JVM, no NaviLink code. splitter and mkgmap are downloaded on first use.

## Command line

    ./gradlew :osmimg:cliJar                      # -> osmimg/build/cli/osmimg.jar
    java -jar osmimg.jar regions europe           # region ids
    java -jar osmimg.jar build portugal --max 2G --routing bike --labels latin1
    java -jar osmimg.jar categories               # POI type -> category table

`java -jar osmimg.jar` with no arguments lists every option.

## What it makes

- **Style `clean`** (default, `src/main/resources/osmimg/style/clean`): POIs in the Garmin Find categories
  (Food & Drink by cuisine, Lodging, Shopping, Services...), names from name/brand/operator, no clutter.
  Roads, areas, turn restrictions and address search come from mkgmap's built-in default style.
- **Bike routing** (`--routing bike`): units that only route by car (276C, nüvi) route like a bicycle.
- **Labels**: `ascii` (every unit), `latin1` (capitals with accents), `mixed` (newer units).
- **Split** (`--max`): parts of at most that size, each with its own family ID.
- `poi-categories.txt`: type → category for every POI type the style makes (for search menus).
- Device rules (`DeviceProfile`, `planInstall`, `planSplit`, `adviseInstall`): file names, slots and
  size limits per Garmin unit, read from GarminDevice.xml.

## Cache

`$OSMIMG_CACHE` or `~/.cache/osmimg`: tools, downloads (refreshed after 14 days), split tiles (reused
while the download is unchanged) and built maps (`out/<region>`). Every program using osmimg shares it.

## Tests

    ./gradlew :osmimg:test                        # fast; style tests need mkgmap in the cache
    ./gradlew :osmimg:test --tests osmimg.MapBuildTest -Pnl.maps=azores [-Pnl.maps.max=104857600] [-Pnl.maps.bike=true]
