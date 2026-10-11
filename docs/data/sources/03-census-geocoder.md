# 03: US Census Bureau Geocoder

Turns a typed address into latitude and longitude so the app can find nearby sites. This is the primary geocoder.

## Upstream

- URL: `https://geocoding.geo.census.gov/geocoder/locations/onelineaddress` (`SITEPLAIN_GEOCODER_CENSUSBASEURL`)
- Parameters: `benchmark=Public_AR_Current&format=json&address=<one line>` (`SITEPLAIN_GEOCODER_CENSUSBENCHMARK`)
- Free, no key, run by the Census Bureau. Called live from `GeocodingService` on `POST /lookup`; nothing is loaded or stored.
- Request: `User-Agent` is `SITEPLAIN_GEOCODER_USER_AGENT` (default `site-plain/0.0.1 (+https://site-plain.com/feedback)`, the same header Nominatim gets); timeout 3 s (`SITEPLAIN_GEOCODER_CENSUSTIMEOUT`).

## Behavior

The lookup path, in order:

1. A trailing `, United States`, `, United States of America`, `, USA` or `, US` is removed.
2. **Geocode cache.** The address is normalized (case, runs of spaces and spacing around commas do not matter) and looked up in an in-memory cache. A hit makes no outbound call. A match is kept 7 days, a definite "no match" from both geocoders 1 hour; a timeout or error is never cached, so a retry calls again. At most 5,000 addresses, least recently used dropped first. Per instance, emptied by a restart. Variables: `SITEPLAIN_GEOCODER_CACHETTL`, `SITEPLAIN_GEOCODER_CACHEUNRESOLVEDTTL`, `SITEPLAIN_GEOCODER_CACHEMAXENTRIES`.
3. **Global cap.** An uncached lookup takes one of 6 geocoding slots for its whole Census plus Nominatim attempt; with all 6 busy it waits up to 1 s, then answers 503 with `Retry-After` (see [operations](../../operations.md#rate-limits-and-global-caps)).
4. **Census.** The first match's coordinates, matched address, city, state and ZIP are used. An empty `addressMatches` is a definite no match; a non-200 answer, a timeout or an unreadable body is a failed call. Either way the lookup falls back to Nominatim ([source 04](04-nominatim-geocoder.md)).
5. A match outside the covered US area, or no match from either geocoder, shows the generic "could not resolve" results page.

Each `POST /lookup` that reaches the geocoders writes one `lookup_audit` row: state, result count, nearest distance, which geocoder answered (`census`, `nominatim`) and whether it resolved. It stores no address text and no coordinates. A lookup refused as cross-site (403) or caught by the honeypot field writes nothing and calls no geocoder.

## Refresh

Nothing to load or refresh. Documented so the address-to-coordinates path is on record.

## Quirks

- Census is strong on standard street addresses and weak on PO boxes, rural routes and partial addresses; those fall through to Nominatim. Through 2026-10-10, Nominatim answered 98 of the 311 lookups that resolved by geocoder on prod.
- No key means no quota to watch, but sustained heavy use could be throttled by Census; the geocode cache and the 6-slot cap keep the app's own volume low.
