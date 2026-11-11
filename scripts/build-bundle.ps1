# Builds our patch bundle (bundle/) in the Docker toolbox and copies the .mpp into work/bundles.
# Needs: Docker running, image ytm-patches-tools (docker build -t ytm-patches-tools docker),
#        gh logged in with the read:packages scope (Morphe's Gradle packages).
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$work = Join-Path $root "work"
New-Item -ItemType Directory -Force "$work\gradle-home", "$work\bundles" | Out-Null

$env:GH_TOK = (gh auth token)
# Gradle logs on stderr; PowerShell 5.1 would treat that as errors, so judge by the exit code only.
$ErrorActionPreference = "Continue"
try {
    docker run --rm -e GITHUB_ACTOR=ayhamo -e GITHUB_TOKEN=$env:GH_TOK `
        -v "${root}\bundle:/src" -v "${work}\gradle-home:/gradle" -w /src `
        ytm-patches-tools sh -c "rm -f patches/build/libs/*.mpp && sh ./gradlew buildAndroid --no-daemon --console=plain -q"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Remove-Item Env:GH_TOK -ErrorAction SilentlyContinue
}

$mpp = Get-ChildItem "$root\bundle\patches\build\libs\patches-*.mpp" | Select-Object -First 1
Copy-Item $mpp.FullName "$work\bundles\ayham-$($mpp.Name)" -Force
Write-Host "Built $work\bundles\ayham-$($mpp.Name)"
