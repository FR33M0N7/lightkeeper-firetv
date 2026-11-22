/*
 * Lightkeeper on Fire TV: stands in for webOS under the unchanged Home page.
 * Loaded after logos.js and before the page's own script (see sync-assets.sh).
 * ES5 only, like the page.
 */
(function () {
  var A = window.LightkeeperAndroid;

  // webOS's Luna bridge, answered by the Android app: launching apps, and the two
  // Homebrew Channel commands the page sends (the setup and the port file).
  function Bridge() {}
  Bridge.prototype.call = function (uri, params) {
    var self = this, p = {}, r = { returnValue: true };
    try { p = JSON.parse(params) || {}; } catch (e) {}
    if (!A) r = { returnValue: false, errorText: 'no Android bridge' };
    else if (/applicationManager\/launch$/.test(uri)) {
      r.returnValue = A.launch(String(p.id || ''), JSON.stringify(p.params || {}));
    } else if (/hbchannel\.service\/exec$/.test(uri)) {
      // "cat /tmp/lightkeeper.port" asks where the server is; the setup has nothing to do here.
      r.stdoutString = /^cat /.test(p.command || '') ? String(A.port()) : '{"ok":true}';
    } else {
      r = { returnValue: false, errorText: 'not on Fire TV: ' + uri };
    }
    setTimeout(function () { if (self.onservicecallback) self.onservicecallback(JSON.stringify(r)); }, 0);
  };
  window.PalmServiceBridge = Bridge;

  // Android's WebView draws its focus ring on the tile that has DOM focus (the first one, an
  // amber frame); webOS never focuses them. Lightkeeper shows the selection itself.
  var css = document.createElement('style');
  css.textContent = '*:focus,*:focus-visible{outline:none!important}';
  (document.head || document.documentElement).appendChild(css);


  // Back from Android: the open apps panel closes on 461, as on webOS.
  window.lightkeeperKey = function (code) {
    var e = document.createEvent('Event');
    e.initEvent('keydown', true, true);
    try { Object.defineProperty(e, 'keyCode', { value: code }); } catch (x) {}
    document.dispatchEvent(e);
  };
  // Home pressed with Lightkeeper already open, or coming back to it: as webOS's relaunch.
  window.lightkeeperRelaunch = function () {
    var e = document.createEvent('Event');
    e.initEvent('webOSRelaunch', true, true);
    document.dispatchEvent(e);
  };
})();
