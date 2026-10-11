// Results page: share link and the site boundary map.
// Loaded as a file so the page needs no inline script; the map reads its data from data- attributes.
(function () {
  'use strict';

  // EPA human exposure status colors, the same values as the --sp-status-* tokens in site-plain.css.
  var STATUS_COLORS = ['match', ['get', 'color'],
    'red', '#9b1c1c',
    'yellow', '#b8860b',
    'green', '#2d7a46',
    '#6c757d'
  ];
  var ADDRESS_PIN_COLOR = '#11263d';

  function setUpShare() {
    var button = document.getElementById('share-btn');
    if (!button) return;
    var input = document.getElementById(button.getAttribute('data-copy-target'));
    var status = document.getElementById(button.getAttribute('data-status-target'));
    if (!input || !status) return;
    input.value = window.location.href;
    var clearTimer = null;

    button.addEventListener('click', function () {
      if (clearTimer) clearTimeout(clearTimer);
      var done = function () {
        status.textContent = 'Link copied.';
        clearTimer = setTimeout(function () { status.textContent = ''; }, 4000);
      };
      var failed = function () {
        status.textContent = 'Copy failed. Copy the link from the address bar.';
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(input.value).then(done, failed);
      } else {
        failed();
      }
    });
  }

  function collectCoordinates(geojson) {
    var coords = [];
    (geojson.features || []).forEach(function (feature) {
      var geom = feature.geometry;
      if (!geom) return;
      var rings = geom.type === 'Polygon' ? geom.coordinates
        : geom.type === 'MultiPolygon' ? geom.coordinates.reduce(function (all, polygon) { return all.concat(polygon); }, [])
        : [];
      rings.forEach(function (ring) { coords = coords.concat(ring); });
    });
    return coords;
  }

  function setUpMap() {
    var mapEl = document.getElementById('results-map');
    if (!mapEl || typeof window.mapboxgl === 'undefined') return;
    var token = mapEl.getAttribute('data-mapbox-token');
    var lat = parseFloat(mapEl.getAttribute('data-lat'));
    var lng = parseFloat(mapEl.getAttribute('data-lng'));
    var geojsonText = mapEl.getAttribute('data-geojson');
    if (!token || !isFinite(lat) || !isFinite(lng) || !geojsonText) return;

    var geojson;
    try {
      geojson = JSON.parse(geojsonText);
    } catch (e) {
      return;
    }

    var mapboxgl = window.mapboxgl;
    mapboxgl.accessToken = token;
    var map = new mapboxgl.Map({
      container: mapEl,
      style: 'mapbox://styles/mapbox/light-v11',
      center: [lng, lat],
      zoom: 11
    });

    map.on('load', function () {
      map.addSource('sites', { type: 'geojson', data: geojson });
      map.addLayer({
        id: 'sites-fill',
        type: 'fill',
        source: 'sites',
        paint: { 'fill-color': STATUS_COLORS, 'fill-opacity': 0.25 }
      });
      map.addLayer({
        id: 'sites-outline',
        type: 'line',
        source: 'sites',
        paint: { 'line-color': STATUS_COLORS, 'line-width': 1.5 }
      });
      new mapboxgl.Marker({ color: ADDRESS_PIN_COLOR }).setLngLat([lng, lat]).addTo(map);

      // Fit the view to every site polygon plus the address pin.
      var coords = collectCoordinates(geojson);
      coords.push([lng, lat]);
      if (coords.length > 1) {
        var bounds = coords.reduce(function (b, c) { return b.extend(c); },
          new mapboxgl.LngLatBounds(coords[0], coords[0]));
        map.fitBounds(bounds, { padding: 40, maxZoom: 13, animate: false });
      }
    });
  }

  setUpShare();
  setUpMap();
})();
