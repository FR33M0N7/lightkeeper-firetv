#!/bin/sh
# Copies Lightkeeper's web app from upstream into the APK's assets, and puts the Fire TV
# shim in. Without an argument it fetches upstream at UPSTREAM_REF into ./upstream.
#   ./sync-assets.sh                     # upstream at the pinned tag
#   ./sync-assets.sh path/to/lightkeeper # a checkout of your own
#   UPSTREAM_REF=main ./sync-assets.sh   # another tag or branch
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
UPSTREAM_URL=https://github.com/chiotas/lightkeeper.git
UPSTREAM_REF=${UPSTREAM_REF:-v1.1.1}
if [ $# -ge 1 ]; then
  SRC="$1/app"
else
  if [ ! -d "$HERE/upstream/.git" ] || [ "$(git -C "$HERE/upstream" describe --tags --exact-match 2>/dev/null || git -C "$HERE/upstream" rev-parse --abbrev-ref HEAD)" != "$UPSTREAM_REF" ]; then
    rm -rf "$HERE/upstream"
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$UPSTREAM_REF" "$UPSTREAM_URL" "$HERE/upstream"
  fi
  SRC="$HERE/upstream/app"
fi
OUT="$HERE/app/src/main/assets/app"
rm -rf "$OUT"; mkdir -p "$OUT/service"
cp -R "$SRC/." "$OUT/"
# The webOS server and its scripts are replaced by the Java server.
rm -f "$OUT/service/server.js" "$OUT/service/"*.sh "$OUT/service/lightkeeper-init" "$OUT/appinfo.json"
# Godrevy Lighthouse is "all rights reserved", licensed only as part of Lightkeeper itself (PHOTO-LICENSE.md).
rm -f "$OUT/backgrounds/godrevy.jpg" "$OUT/backgrounds/thumbs/godrevy.jpg"
python3 -I - "$OUT/backgrounds/index.json" <<'PY'
import json, sys
f = sys.argv[1]
d = [b for b in json.load(open(f)) if b['id'] != 'godrevy']
open(f, 'w').write(json.dumps(d, indent=1, ensure_ascii=False) + '\n')
PY
cp "$HERE/shim/android-shim.js" "$HERE/shim/fire-logos.js" "$OUT/"
# The shim goes right after logos.js, before the page's own script.
grep -q 'android-shim.js' "$OUT/index.html" || \
  sed -i '' 's#<script src="logos.js"></script>#<script src="logos.js"></script>\n<script src="fire-logos.js"></script>\n<script src="android-shim.js"></script>#' "$OUT/index.html"
grep -c 'android-shim.js' "$OUT/index.html" >/dev/null || { echo "shim not inserted"; exit 1; }
# The logo names a tile may pick, as server.js reads them from logos.js.
node -e '
var vm = require("vm"), fs = require("fs"), ctx = {}, names = {};
vm.runInNewContext(fs.readFileSync(process.argv[1], "utf8"), ctx);
[ctx.SIMPLE_ICONS, ctx.IMMAGINI, ctx.ICONE].forEach(function (t) { Object.keys(t || {}).forEach(function (k) { names[k] = true; }); });
fs.writeFileSync(process.argv[2], JSON.stringify(Object.keys(names).sort()) + "\n");
' "$OUT/logos.js" "$OUT/catalog.json"
echo "assets synced from $SRC ($(cd "$SRC/.." && git rev-parse --short HEAD 2>/dev/null || echo '?'))"
