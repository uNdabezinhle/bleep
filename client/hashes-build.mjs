import { createHash } from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";

const f = readdirSync("dist/assets").find((x) => x.endsWith(".js"));
if (!f) throw new Error("no js bundle");
const h = createHash("sha256").update(readFileSync("dist/assets/" + f)).digest("hex");
const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Bleep — published hashes</title>
  <style>
    body { font: 16px/1.5 Segoe UI, sans-serif; background: #101114; color: #ece8df; max-width: 720px; margin: 40px auto; padding: 0 16px; }
    code { font-family: Consolas, monospace; color: #e0a94a; }
  </style>
</head>
<body>
  <h1>Hashes</h1>
  <p>T6: signed updates + a hash page. This invite build is not the Play APK. Do not call it audited or secure.</p>
  <p>Parents: BLEEP-TM-001 v0.1 · BLEEP-FC-001 v0.3</p>
  <pre><code>client js  sha256:${h}
file       ${f}
relay      docker inspect --format="{{.Id}}" bleep-relay
apk        pending Android package</code></pre>
</body>
</html>
`;
writeFileSync("dist/hashes.html", html);
console.log("wrote dist/hashes.html", h);
