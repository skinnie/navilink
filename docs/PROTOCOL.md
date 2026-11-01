# The Smartphone Link Bluetooth protocol (nüvi side)

Notes from reading Garmin Smartphone Link 2.11.12 for Android and the nüvi 34xx firmware 8.50.
They explain how NaviLink talks to a nüvi. Nothing here touches Garmin's servers.

## Two Bluetooth links

Both are classic Bluetooth RFCOMM (serial port) links carrying small HTTP/1.1-style text messages.

| Direction | Who connects | UUID | Used for |
|---|---|---|---|
| phone → nüvi | phone connects to the nüvi | `59845525-f612-4fde-83d9-1c6c914c4272` | sending places (`/new-route`) |
| nüvi → phone | nüvi connects to the phone's **GarminLink** service | `00001101-0000-1000-8000-00805f9b34fb` (SPP) | the nüvi's requests: login, destination, parking spot, saved places, live services |

Smartphone Link waits for the nüvi to connect to GarminLink first, then connects back to the nüvi.
To wake a nüvi it connects on the nüvi UUID, writes `ping`, and closes.
The firmware logs `SPP_ACCEPT: Notify PL2 is connected` when a phone connects in ("PL2" = Phone Link 2).

Only one app on a phone can offer GarminLink at a time, so stop Smartphone Link before using NaviLink.

## Message framing

Requests and answers look like HTTP/1.1. The body length is given by `Content-Length`; requests add
one extra CRLF after the body that is not counted.

```
POST /new-route HTTP/1.1
Host: gps-device
Content-Length: 69

lat=474203760&lon=-105100235&name=Castelo+de+Leiria&locality=Leiria

```

Answers from the phone to the nüvi (note the `Content-Length` of text length + 4, copied from Smartphone Link):

```
HTTP/1.1 200 OK
Content-Length: 6

OK

```

- Coordinates are Garmin **semicircles**: degrees × 2³¹ / 180, as integers. Smartphone Link truncates;
  `.gpi` files (and gpsbabel) round to the nearest one.
- Values are URL-encoded UTF-8, one `key=value&key=value` record per body line.

## Phone → nüvi

| Path | Body | Notes |
|---|---|---|
| `/new-route` | one line per stop: `lat`, `lon`, `name`, `thoroughfare`, `postalCode`, `locality`, `adminArea`, `country` | one line = "go here", several = a route |
| `/re-auth` | none | asks the nüvi to log in again |
| `/route-share` | a GPX file | **not in nüvi 34xx firmware 8.50** (zūmo feature) |
| `/smart-notification-*`, `/live-track-*` | various | not used by NaviLink |

## nüvi → phone

| Path | What the nüvi sends | Smartphone Link's answer |
|---|---|---|
| `/auth-info?unit-identifier=<id>` | lines `category=<n>`: the live services the nüvi wants | `200 OK` when no Garmin account is stored, otherwise app/device identity lines |
| `/find-destination` | `lat`, `lon` of the nüvi's destination | `200 OK` |
| `/parking-spot` | `lat`, `lon` of where the car was parked | `200 OK` |
| `/save-locations` | one line per place: `name`, `lat`, `lon`, `addr`, `phone`, `cat`; plus `type` (`recent` or `saved`) and `replace` (`true`/`false`) | `200 OK` |
| anything else | | NaviLink answers `501 Not implemented` |

## What nüvi 34xx firmware 8.50 contains

The `.gcd` firmware file is a list of records (`u16 type, u16 length, data`). Records of one type
together make one region. Region `0x02bd` is the main program (ARM/Thumb, not compressed).
Its Phone Link strings are stored as UTF-16LE:

```
/auth-info  unit-identifier  category  subscription-token  device-identifier
client-generated-token  application-version  app-hash  device-type  operating-system
client-type-id  account-id
/find-destination  lat  lon
/parking-spot  lat  lon
name  lat  lon  addr  phone  cat
save-locations  recent  saved  type  true  replace
/new-route
/re-auth
```

There is no `route-share`, so a 34xx can't receive GPX or GPI files over Bluetooth. Bulk POIs have to
go over USB, as a `.gpi` file in `Garmin/POI`.

## By cable

Over USB the nüvi is a plain drive (mass-storage mode). Its firmware describes one input data type in
`Garmin/GarminDevice.xml`: `GPSData`, `InputToUnit`, folder `GPX`, extension `.gpx`. It looks for
GPX files in `/Garmin/GPX/` and in the SD card's GPX folder when it starts, the way Garmin BaseCamp's
"Send to device" works. Waypoints become saved places; routes become trips.

`Current.gpx`, `Position.gpx` and `Archive/` in that folder are the nüvi's own files (track log, last
position); don't write those.

The firmware knows these Garmin GPX extension tags (`http://www.garmin.com/xmlschemas/GpxExtensions/v3`):

```
gpxx:WaypointExtension  gpxx:Categories  gpxx:Category  gpxx:Address  gpxx:StreetAddress
gpxx:City  gpxx:State  gpxx:Country  gpxx:PostalCode  gpxx:PhoneNumber  gpxx:Proximity
gpxx:DisplayMode  gpxx:RouteExtension  gpxx:IsAutoNamed  gpxx:DisplayColor
gpxx:RoutePointExtension  gpxx:rpt  gpxx:Subclass
```

NaviLink writes `Garmin/GPX/NaviLink.gpx`: one `<wpt>` per saved place, with name, address, phone
and category in a `gpxx:WaypointExtension`, and one `<rte>` per route. Route stops are plain
`<rtept>`s without `gpxx:rpt` shaping points, so the nüvi calculates the roads between them.

## Open questions

- Does the nüvi accept `/new-route` straight away, or only after it has sent `/auth-info` and got an answer?
  NaviLink can do both: **Connect + Send**, or **Listen**, let the nüvi connect, then **Send**.
- What the nüvi shows for a multi-stop `/new-route`, and how many stops it takes.
- By cable: whether routes show up in Trip Planner with all their stops, and whether the nüvi
  adds the places from `NaviLink.gpx` again on every start.

Please open an issue with NaviLink's log if you try it on a nüvi.
