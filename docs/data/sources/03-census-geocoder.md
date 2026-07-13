# 03: US Census Bureau Geocoder

Turns a user-typed address into latitude/longitude so the app can run a spatial "nearby sites" query. This is the primary geocoder.

## Upstream

- URL: `https://geocoding.geo.census.gov/geocoder/locations/onelineaddress`
- Parameters: `address=<one line>&benchmark=Public_AR_Current&format=json`
- Free, no credentials, maintained by the Census Bureau.
- Called live per user lookup in `GeocodingService`. No database table; the response is used in-flight only.

## Behavior

- On a successful match, the first result's coordinates are used.
- On no match or an HTTP error, the service falls back to Nominatim (see [source 04](04-nominatim-geocoder.md)).

## Refresh

Not applicable. There is nothing to load or refresh. It is documented here only so the full address-to-coordinates path is on record.

## Quirks

- Census geocoding quality is strong for standard US street addresses and weak for PO boxes, rural routes, and partial addresses; those are what most often fall through to Nominatim.
- No API key means no rate-limit headers to manage, but sustained heavy traffic can still be throttled by Census. There is no separate quota to monitor today.
