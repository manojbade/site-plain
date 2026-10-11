# 04: OpenStreetMap Nominatim (Geocoder Fallback)

Fallback geocoder, used only when the Census geocoder ([source 03](03-census-geocoder.md)) finds no match or fails, and only for addresses not already in the geocode cache.

## Upstream

- URL: `https://nominatim.openstreetmap.org/search` (`SITEPLAIN_GEOCODER_NOMINATIMBASEURL`)
- Parameters: `format=jsonv2&limit=1&countrycodes=us&addressdetails=1&q=<address>` (`limit` is `SITEPLAIN_GEOCODER_NOMINATIMLIMIT`, default 1)
- Free, no key, community-run by the OpenStreetMap Foundation (OSMF). No database table.

## Terms of use and the gate

OSMF's usage policy asks for an identifying `User-Agent` with a way to reach the operator, and an absolute maximum of one request per second.

- **User-Agent:** `SITEPLAIN_GEOCODER_USER_AGENT`, default `site-plain/0.0.1 (+https://site-plain.com/feedback)`, which names the feedback page as the contact. If that variable is set on Railway it replaces the default: unset it, or make sure it still carries a contact URL. If Nominatim ever answers 403, check this header first.
- **One request per second, app-wide** (`NominatimGate`): calls are spaced at least `SITEPLAIN_LIMITS_NOMINATIMINTERVAL` (1 s) apart. A lookup waits for the next free slot up to `SITEPLAIN_LIMITS_NOMINATIMMAXWAIT` (3 s); if the slot is further off, it answers 503 "busy" with `Retry-After`, never "address not found".
- **Timeout:** 5 s (`SITEPLAIN_GEOCODER_NOMINATIMTIMEOUT`). A single-address search normally answers in well under a second (0.49 s in a sample). With Census (3 s), the gate wait (3 s) and this call, one lookup holds a geocoding slot for at most about 11 s. A timeout reads as no answer: the lookup shows the could-not-resolve page and nothing is cached, so a retry tries again.

## Behavior

- The first result is used. The state comes from `ISO3166-2-lvl4` (`US-OH` gives `OH`), else from the state name.
- **Approximate matches.** A result at house level (`place_rank` 28 or more, or `addresstype` house or building) is exact. A street-level result (`place_rank` 26 or 27) is labeled `street`, because the point is somewhere on the road and can be far from the typed number. Anything coarser is labeled from `addresstype` using one fixed list: ZIP code, neighborhood, suburb, quarter, city district, district, borough, hamlet, village, town, city, municipality, county, region, state; any other type shows as `area`. The results page then says the location is only approximate.
- A resolved Nominatim lookup writes a `lookup_audit` row with `geocoder_used = nominatim` (no address text, no coordinates). Volume is not negligible: through 2026-10-10 Nominatim answered 98 of 311 geocoder-resolved lookups on prod (31%), which is why the gate and the cache exist.

## Refresh

Nothing to load or refresh.

## Quirks

- OSM coverage varies by region. For a US address it is a reasonable second try, not an authority.
- If both geocoders miss, the lookup redirects to a generic "could not resolve" results page (`/results?r=...` with an empty encrypted link). A `lookup_audit` row with `resolved = false` is written; the typed text is not stored or put in the link.
