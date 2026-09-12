<#
    Launch RuneLite with this plugin side-loaded.

    RuneLite only reads ~/.runelite/sideloaded-plugins when developer mode is on, and developer
    mode is deliberately unavailable to anything the official launcher starts:

        final boolean developerMode = options.has("developer-mode")
            && RuneLiteProperties.getLauncherVersion() == null;

    The launcher always sets that property, so --developer-mode passed through RuneLite.exe, its
    configure dialog or RUNELITE_ARGS is parsed and then AND-ed away. The only way in is to run
    the client jar yourself, which is what this does - using the JRE, the jars and the plugin the
    launcher has already downloaded, so there is nothing to install and nothing to keep in step.

    A Jagex account needs one extra thing first, once: see README, "Testing it in game".
#>
param(
    # extra client arguments, e.g. -ClientArgs '--debug'
    [string[]] $ClientArgs = @()
)

$ErrorActionPreference = 'Stop'

$repo = Join-Path $env:USERPROFILE '.runelite\repository2'
$java = Join-Path $env:LOCALAPPDATA 'RuneLite\jre\bin\java.exe'
$side = Join-Path $env:USERPROFILE '.runelite\sideloaded-plugins'
$jar  = Join-Path $PSScriptRoot 'build\libs\osrs-loadout-1.0.0.jar'

if (-not (Test-Path $java)) {
    throw "No JRE at $java. Launch RuneLite once through the Jagex Launcher so it installs one."
}
if (-not (Test-Path $repo)) {
    throw "No $repo. Launch RuneLite once through the Jagex Launcher so it downloads the client."
}

# Copy the jar we just built into the directory the client reads, so `gradlew build` then this
# script is the whole loop.
if (Test-Path $jar) {
    if (-not (Test-Path $side)) { New-Item -ItemType Directory -Path $side | Out-Null }
    Copy-Item $jar $side -Force
    Write-Host "side-loading  $(Split-Path $jar -Leaf)" -ForegroundColor Cyan
} else {
    Write-Host "no built jar at $jar - run .\gradlew.bat build first" -ForegroundColor Yellow
}

# repository2 keeps the previous release's artifacts beside the current one, so client-1.12.37.jar
# and runelite-api-1.12.37-runtime.jar sit next to the 1.12.38 pair and whichever comes first in
# the classpath wins. Only RuneLite's OWN three artifacts are versioned this way - everything else
# in here is a third-party library with a version in its name that has nothing to do with the
# client's, which is why this matches the prefix and not just a version number.
$own = '^(client|injected-client|runelite-api)-'
$client = Get-ChildItem "$repo\client-*.jar" |
    Sort-Object { [version]($_.BaseName -replace '^client-', '') } |
    Select-Object -Last 1
$version = $client.BaseName -replace '^client-', ''
# One pass, and the test is by name: two Get-ChildItem calls hand back different FileInfo objects
# for the same file, so filtering the second list against the first with -notin matches nothing at
# all and silently keeps the stale jars.
$isStale = { param($f) $f.Name -match $own -and $f.Name -notmatch [regex]::Escape($version) }
$jars = Get-ChildItem "$repo\*.jar"
$stale = $jars | Where-Object { & $isStale $_ }
$cp = ($jars | Where-Object { -not (& $isStale $_) } | ForEach-Object FullName) -join ';'

Write-Host "client        $version" -ForegroundColor Cyan
if ($stale) { Write-Host "ignoring      $($stale.Count) jar(s) from an older version" -ForegroundColor DarkGray }

$creds = Join-Path $env:USERPROFILE '.runelite\credentials.properties'
if (-not (Test-Path $creds)) {
    Write-Host ""
    Write-Host "No credentials.properties - a Jagex account cannot log in to this client yet." -ForegroundColor Yellow
    Write-Host "Do this once (see README, 'Testing it in game'):" -ForegroundColor Yellow
    Write-Host "  1. Start menu > RuneLite (configure)" -ForegroundColor Yellow
    Write-Host "  2. Client arguments: --insecure-write-credentials   then Save" -ForegroundColor Yellow
    Write-Host "  3. Play through the Jagex Launcher once, log in, close" -ForegroundColor Yellow
    Write-Host "  4. Go back into RuneLite (configure) and clear that box again" -ForegroundColor Yellow
    Write-Host ""
}

# -ea is not optional: with developer mode on and assertions off, RuneLite.main() shows
# "Developers should enable assertions" and returns before it ever builds the injector.
& $java -ea -cp $cp net.runelite.client.RuneLite --developer-mode @ClientArgs
