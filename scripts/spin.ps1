# Ring 0 self-host: bring the box up, publish a debug APK on the same origin.
# IPA is not produced here (no iOS tree, no Apple signing).
#
#   .\scripts\spin.ps1
#   .\scripts\spin.ps1 -NoTunnel

param(
    [switch]$NoTunnel
)
$Tunnel = -not $NoTunnel

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$DownloadDir = Join-Path $Root "dist-download"
New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $candidates = Get-ChildItem "C:\Program Files\Eclipse Adoptium","C:\Program Files\Microsoft" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-21*" }
    foreach ($resolved in $candidates) {
        if (Test-Path (Join-Path $resolved.FullName "bin\java.exe")) {
            return $resolved.FullName
        }
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $home = Split-Path (Split-Path $java.Source)
        if (Test-Path (Join-Path $home "bin\java.exe")) { return $home }
    }
    return $null
}

function Find-Cloudflared {
    $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @(
            "${env:ProgramFiles}\cloudflared\cloudflared.exe",
            "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe"
        )) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Get-LanIps {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.PrefixOrigin -ne "WellKnown"
        } |
        Select-Object -ExpandProperty IPAddress
}

function Write-DownloadPages {
    param(
        [string]$LocalUrl,
        [string]$PublicUrl,
        [string]$ApkSha,
        [string]$RelayImage,
        [string[]]$LanIps
    )
    $nl = [Environment]::NewLine
    if ($ApkSha) {
        $apkLine = "apk      sha256:$ApkSha${nl}file     /download/bleep.apk${nl}status   debug-signed. not Play-signed. not an audit."
        $btn = '<p><a class="dl" href="/download/bleep.apk">Download Android APK</a></p>'
    } else {
        $apkLine = "apk      not built (JDK/SDK missing or gradle failed)${nl}file     -"
        $btn = "<p>APK not on this spin.</p>"
    }
    $box = if ($PublicUrl) { $PublicUrl } else { $LocalUrl }
    if ($LanIps -and $LanIps.Count -gt 0) {
        $lan = ($LanIps | ForEach-Object { "http://${_}:8090" }) -join "$nl         "
    } else {
        $lan = "(none)"
    }
    $pub = if ($PublicUrl) { $PublicUrl } else { "(tunnel off)" }
    $html = @"
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Bleep - download + hashes</title>
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
  $btn
  <p>After install, allow unknown sources, create a Bleep ID, and paste this box as <strong>Public Bleep</strong>:</p>
  <pre><code>$box</code></pre>
  <pre><code>$apkLine
relay    $RelayImage
client   invite SPA in the web image (not the charter vault)
version  0.1.0 debug
ipa      not produced (no iOS tree; Apple signing needs a Mac)
local    $LocalUrl
public   $pub
lan      $lan</code></pre>
  <p class="note">Parents: BLEEP-TM-001 v0.1 · BLEEP-FC-001 v0.3. Quick-tunnel URLs change every spin. Do not bake them into the APK.</p>
</body>
</html>
"@
    Set-Content -Path (Join-Path $DownloadDir "hashes.html") -Value $html -Encoding utf8
    Set-Content -Path (Join-Path $DownloadDir "index.html") -Value $html -Encoding utf8
}

Write-Host "==> docker compose"
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { throw "docker compose failed" }

Write-Host "==> wait for relay health"
$healthy = $false
for ($i = 0; $i -lt 45; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8090/v1/health" -Headers @{ "X-Bleep-Region" = "ZA-JHB" } -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $healthy) { throw "relay did not become healthy on :8090" }

$apkSha = $null
$javaHome = Find-JavaHome
$gradlew = Join-Path $Root "android\gradlew.bat"
if ($javaHome -and (Test-Path $gradlew)) {
    Write-Host "==> assembleDebug (JAVA_HOME=$javaHome)"
    $env:JAVA_HOME = $javaHome
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
    & $gradlew -p (Join-Path $Root "android") assembleDebug --quiet
    $built = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
    if ($LASTEXITCODE -eq 0 -and (Test-Path $built)) {
        Copy-Item $built (Join-Path $DownloadDir "bleep.apk") -Force
        $hash = Get-FileHash -Algorithm SHA256 (Join-Path $DownloadDir "bleep.apk")
        $apkSha = $hash.Hash.ToLower()
        Set-Content -Path (Join-Path $DownloadDir "bleep.apk.sha256") -Value $apkSha -Encoding ascii
        Write-Host "    apk sha256:$apkSha"
    } else {
        Write-Warning "gradle finished without an APK; box is up, download page will say pending"
    }
} else {
    Write-Warning "JDK or gradlew missing; skipping APK. Box is still up."
}

$relayImage = "unknown"
try {
    $relayImage = (docker inspect --format "{{.Image}}" bleep-relay-1).Trim()
} catch { }

$localUrl = "http://localhost:8090"
$lanIps = @(Get-LanIps)
$publicUrl = $null

if ($Tunnel) {
    $cf = Find-Cloudflared
    if (-not $cf) {
        Write-Warning "cloudflared not installed; skip tunnel. winget install Cloudflare.cloudflared"
    } else {
        Write-Host "==> cloudflared quick tunnel"
        $outLog = Join-Path $DownloadDir "cloudflared.out.log"
        $errLog = Join-Path $DownloadDir "cloudflared.err.log"
        foreach ($f in @($outLog, $errLog)) { if (Test-Path $f) { Remove-Item $f -Force } }
        $cfProc = Start-Process -FilePath $cf -ArgumentList @("tunnel", "--url", "http://localhost:8090") `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Seconds 1
            $text = ""
            foreach ($f in @($outLog, $errLog)) {
                if (Test-Path $f) { $text += Get-Content $f -Raw -ErrorAction SilentlyContinue }
            }
            if ($text -match "https://[a-z0-9-]+\.trycloudflare\.com") {
                $publicUrl = $Matches[0]
                break
            }
            if ($cfProc.HasExited) { break }
        }
        if ($publicUrl) {
            Write-Host "    public $publicUrl"
        } else {
            Write-Warning "tunnel started (pid $($cfProc.Id)) but URL not parsed yet; check dist-download/cloudflared.*.log"
        }
    }
}

Write-DownloadPages -LocalUrl $localUrl -PublicUrl $publicUrl -ApkSha $apkSha -RelayImage $relayImage -LanIps $lanIps

Write-Host "==> publish files into web container"
docker exec bleep-web-1 mkdir -p /usr/share/nginx/html/download
docker cp (Join-Path $DownloadDir "hashes.html") "bleep-web-1:/usr/share/nginx/html/download/hashes.html"
docker cp (Join-Path $DownloadDir "index.html") "bleep-web-1:/usr/share/nginx/html/download/index.html"
docker cp (Join-Path $DownloadDir "hashes.html") "bleep-web-1:/usr/share/nginx/html/hashes.html"
$apkOut = Join-Path $DownloadDir "bleep.apk"
if (Test-Path $apkOut) {
    docker cp $apkOut "bleep-web-1:/usr/share/nginx/html/download/bleep.apk"
}

Write-Host ""
Write-Host "Bleep box is up (self-host / Ring 0)."
Write-Host "  local   $localUrl"
Write-Host "  alice   $localUrl/?p=alice"
Write-Host "  bob     $localUrl/?p=bob"
if ($publicUrl) {
    Write-Host "  public  $publicUrl"
    Write-Host "  apk     $publicUrl/download/bleep.apk"
    Write-Host "  hashes  $publicUrl/hashes.html"
} else {
    Write-Host "  apk     $localUrl/download/bleep.apk"
    Write-Host "  hashes  $localUrl/hashes.html"
}
foreach ($ip in $lanIps) {
    Write-Host "  lan     http://${ip}:8090"
}
Write-Host ""
Write-Host "On the phone: install the APK (unknown sources), then paste the public/LAN URL as Public Bleep."
Write-Host "No IPA. Keep this PC awake if you are using the tunnel."
