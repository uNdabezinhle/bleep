#!/usr/bin/env bash
# Ring 0 self-host for a Linux/OpenStack VM. Same idea as spin.ps1.
#   ./scripts/spin.sh
#   TUNNEL=0 ./scripts/spin.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
DOWNLOAD="$ROOT/dist-download"
mkdir -p "$DOWNLOAD"
TUNNEL="${TUNNEL:-1}"

write_pages() {
  local local_url="$1" public_url="$2" apk_sha="$3" relay_image="$4" lan="$5"
  local apk_line box pub btn
  if [[ -n "$apk_sha" ]]; then
    apk_line="apk      sha256:${apk_sha}
file     /download/bleep.apk
status   debug-signed. not Play-signed. not an audit."
    btn='<p><a class="dl" href="/download/bleep.apk">Download Android APK</a></p>'
  else
    apk_line="apk      not built (JDK/SDK missing or gradle failed)
file     —"
    btn="<p>APK not on this spin.</p>"
  fi
  box="${public_url:-$local_url}"
  pub="${public_url:-(tunnel off)}"
  cat > "$DOWNLOAD/hashes.html" <<EOF
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Bleep — download + hashes</title>
  <style>
    body { font: 16px/1.5 Segoe UI, sans-serif; background: #101114; color: #ece8df; max-width: 720px; margin: 40px auto; padding: 0 16px; }
    code, pre { font-family: Consolas, monospace; color: #e0a94a; }
    pre { background: #1a1c22; padding: 14px; border-radius: 10px; overflow: auto; }
    a { color: #7eb0d6; }
    a.dl { display: inline-block; margin: 12px 0; padding: 10px 16px; background: #c9a227; color: #101114; font-weight: 700; text-decoration: none; border-radius: 8px; }
    .note { color: #9a958a; }
  </style>
</head>
<body>
  <h1>Hashes + Android</h1>
  <p>T6: published hashes for the file this box is serving. This is a self-host / Ring 0 spin. Do not call it audited or secure.</p>
  <p class="note">The vault is the APK on the phone (or the invite browser profile). Relays hold sealed envelopes only.</p>
  ${btn}
  <p>After install, allow unknown sources, create a Bleep ID, and paste this box as <strong>Public Bleep</strong>:</p>
  <pre><code>${box}</code></pre>
  <pre><code>${apk_line}
relay    ${relay_image}
client   invite SPA in the web image (not the charter vault)
version  0.1.0 debug
ipa      not produced (no iOS tree; Apple signing needs a Mac)
local    ${local_url}
public   ${pub}
lan      ${lan}</code></pre>
  <p class="note">Parents: BLEEP-TM-001 v0.1 · BLEEP-FC-001 v0.3. Quick-tunnel URLs change every spin — do not bake them into the APK.</p>
</body>
</html>
EOF
  cp "$DOWNLOAD/hashes.html" "$DOWNLOAD/index.html"
}

echo "==> docker compose"
docker compose up --build -d

echo "==> wait for relay health"
ok=0
for i in $(seq 1 45); do
  if curl -fsS -H "X-Bleep-Region: ZA-JHB" "http://localhost:8090/v1/health" >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 2
done
[[ "$ok" = 1 ]] || { echo "relay did not become healthy on :8090" >&2; exit 1; }

apk_sha=""
if [[ -x "$ROOT/android/gradlew" ]] && command -v java >/dev/null 2>&1; then
  echo "==> assembleDebug"
  if "$ROOT/android/gradlew" -p "$ROOT/android" assembleDebug --quiet; then
    built="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    if [[ -f "$built" ]]; then
      cp "$built" "$DOWNLOAD/bleep.apk"
      apk_sha="$(sha256sum "$DOWNLOAD/bleep.apk" | awk '{print $1}')"
      echo "$apk_sha" > "$DOWNLOAD/bleep.apk.sha256"
      echo "    apk sha256:$apk_sha"
    fi
  else
    echo "warning: gradle failed; box is up" >&2
  fi
else
  echo "warning: JDK or gradlew missing; skipping APK" >&2
fi

relay_image="$(docker inspect --format '{{.Image}}' bleep-relay-1 2>/dev/null || echo unknown)"
local_url="http://localhost:8090"
lan="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
public_url=""

if [[ "$TUNNEL" = "1" ]] && command -v cloudflared >/dev/null 2>&1; then
  echo "==> cloudflared quick tunnel"
  cloudflared tunnel --url http://localhost:8090 >"$DOWNLOAD/cloudflared.out.log" 2>"$DOWNLOAD/cloudflared.err.log" &
  for i in $(seq 1 30); do
    sleep 1
    public_url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$DOWNLOAD/cloudflared.out.log" "$DOWNLOAD/cloudflared.err.log" 2>/dev/null | head -n1 || true)"
    [[ -n "$public_url" ]] && break
  done
fi

write_pages "$local_url" "$public_url" "$apk_sha" "$relay_image" "${lan:-none}"

echo "==> publish files into web container"
docker exec bleep-web-1 mkdir -p /usr/share/nginx/html/download
docker cp "$DOWNLOAD/hashes.html" bleep-web-1:/usr/share/nginx/html/download/hashes.html
docker cp "$DOWNLOAD/index.html" bleep-web-1:/usr/share/nginx/html/download/index.html
docker cp "$DOWNLOAD/hashes.html" bleep-web-1:/usr/share/nginx/html/hashes.html
if [[ -f "$DOWNLOAD/bleep.apk" ]]; then
  docker cp "$DOWNLOAD/bleep.apk" bleep-web-1:/usr/share/nginx/html/download/bleep.apk
fi

echo
echo "Bleep box is up (self-host / Ring 0)."
echo "  local   $local_url"
if [[ -n "$public_url" ]]; then
  echo "  public  $public_url"
  echo "  apk     $public_url/download/bleep.apk"
  echo "  hashes  $public_url/hashes.html"
else
  echo "  apk     $local_url/download/bleep.apk"
  echo "  hashes  $local_url/hashes.html"
fi
echo "On the phone: install the APK (unknown sources), then paste the public/LAN URL as Public Bleep."
echo "No IPA. Keep this machine awake if you are using the tunnel."
