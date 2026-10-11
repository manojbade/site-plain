// Google Analytics 4 setup for Site Plain. Loaded as a file so pages need no inline script.
window.dataLayer = window.dataLayer || [];
function gtag() {
  window.dataLayer.push(arguments);
}
gtag('js', new Date());
(function () {
  // Results URLs carry an encrypted link, or on old links an address and coordinates: never send
  // their query string to Google, as the page URL or as the next page's referrer.
  var RESULTS_PATH = /^\/results(\/|$)/;
  var config = {};
  if (RESULTS_PATH.test(window.location.pathname)) {
    config.page_location = window.location.origin + window.location.pathname;
  }
  try {
    var referrer = document.referrer ? new URL(document.referrer) : null;
    if (referrer && referrer.origin === window.location.origin && RESULTS_PATH.test(referrer.pathname)) {
      config.page_referrer = referrer.origin + referrer.pathname;
    }
  } catch (e) {
    // An unparseable referrer is left to gtag's default.
  }
  gtag('config', 'G-0574GW6TCV', config);
})();
