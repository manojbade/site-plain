# 04: OpenStreetMap Nominatim (Geocoder Fallback)

Fallback geocoder used only when the Census geocoder (see [source 03](03-census-geocoder.md)) returns no match or errors.

## Upstream

- URL: `https://nominatim.openstreetmap.org/search` (`siteplain.geocoder.nominatimBaseUrl`)
- Parameters: `format=jsonv2&limit=<nominatimLimit>&countrycodes=us&addressdetails=1&q=<query>` (see `GeocodingService.geocodeWithNominatim`). `nominatimLimit` defaults to 1 (`siteplain.geocoder.nominatimLimit`).
- Free, no credentials, community-run (OSM).
- Called live per user lookup in `GeocodingService`, only on Census miss. No database table.

## Terms of use

Nominatim's usage policy requires a valid identifying `User-Agent`. The app sends `siteplain.geocoder.userAgent`, wired to `SITEPLAIN_GEOCODER_USER_AGENT` (default `site-plain/0.0.1`). The other geocoder properties (base URLs, timeouts, limit) have code defaults but are not bound to env vars. If Nominatim ever returns 403s, check that header first. The public endpoint also asks for no more than ~1 request/second sustained; this app only hits it on Census misses, so volume is low.

## Refresh

Not applicable. Nothing to load or refresh.

## Quirks

- OSM coverage varies by region. For a US consumer address it is a reasonable second try, but it is not authoritative.
- If both geocoders miss, the lookup returns the "address not resolved" error page (`error/unresolved.html`); nothing is written to the database.
