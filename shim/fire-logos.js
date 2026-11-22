/*
 * Lightkeeper on Fire TV: the logos for Fire TV's package names, beside webOS's ids.
 * Loaded after logos.js by the Home, and appended to /logos.js by the server for the settings.
 */
(function () {
  var FIRE = {
    'com.netflix.ninja': 'netflix',
    'com.amazon.firebat': 'primevideo',
    'com.amazon.avod.thirdpartyclient': 'primevideo',
    'org.smarttube.stable': 'youtube',
    'org.smarttube.beta': 'youtube',
    'com.amazon.firetv.youtube': 'youtube',
    'com.google.android.youtube.tv': 'youtube',
    'com.disney.disneyplus': 'disneyplus',
    'com.dazn': 'dazn',
    'com.canal.android.canal': 'canalplus',
    'gamepass.fire.tv': 'xbox',
    'com.hbo.hbonow': 'max',
    'com.wbd.stream': 'max',
    'com.skyshowtime.skyshowtime.google': 'skyshowtime',
    'tv.twitch.android.viewer': 'twitch',
    'org.xbmc.kodi': 'kodi',
    'com.plexapp.android': 'plex',
    'com.spotify.tv.android': 'spotify'
  };
  if (typeof LOGHI_APP === 'object') for (var k in FIRE) if (FIRE.hasOwnProperty(k)) LOGHI_APP[k] = FIRE[k];
})();
