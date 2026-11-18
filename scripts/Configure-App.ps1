[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$setupPath = Join-Path $projectRoot 'app-setup.json'

if (-not (Test-Path -LiteralPath $setupPath -PathType Leaf)) {
    throw "Missing setup file: $setupPath"
}

$setup = Get-Content -LiteralPath $setupPath -Raw | ConvertFrom-Json
foreach ($property in @('appName', 'appId', 'webDir', 'entryHtml', 'versionCode', 'versionName')) {
    if (-not $setup.PSObject.Properties[$property]) {
        throw "app-setup.json is missing '$property'."
    }
}

if ([string]::IsNullOrWhiteSpace([string]$setup.appName)) {
    throw 'appName must not be empty.'
}
if ([string]$setup.appId -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
    throw 'appId must be a reverse-DNS identifier, such as com.example.myapp.'
}
if ([string]::IsNullOrWhiteSpace([string]$setup.versionName)) {
    throw 'versionName must not be empty and versionCode must be a positive integer.'
}
if ([string]::IsNullOrWhiteSpace([string]$setup.webDir) -or
    [System.IO.Path]::IsPathRooted([string]$setup.webDir) -or
    ([string]$setup.webDir -split '[\\/]') -contains '..') {
    throw 'webDir must be a project-relative folder without parent-directory segments.'
}
if ([System.IO.Path]::IsPathRooted([string]$setup.entryHtml) -or
    [System.IO.Path]::GetFileName([string]$setup.entryHtml) -ne [string]$setup.entryHtml -or
    [System.IO.Path]::GetExtension([string]$setup.entryHtml) -notmatch '(?i)^\.(html?)$') {
    throw 'entryHtml must be an .html or .htm file name located directly inside webDir.'
}
$versionCode = 0
if (-not [int]::TryParse([string]$setup.versionCode, [ref]$versionCode) -or $versionCode -lt 1) {
    throw 'versionCode must be a positive integer.'
}

$webDir = Join-Path $projectRoot ([string]$setup.webDir)
$entryPath = Join-Path $webDir ([string]$setup.entryHtml)
$indexPath = Join-Path $webDir 'index.html'
if (-not (Test-Path -LiteralPath $webDir -PathType Container)) {
    throw "Web folder does not exist: $webDir"
}
if (-not (Test-Path -LiteralPath $entryPath -PathType Leaf)) {
    throw "HTML entry file does not exist: $entryPath"
}
if ([System.IO.Path]::GetFullPath($entryPath) -ne [System.IO.Path]::GetFullPath($indexPath)) {
    Copy-Item -LiteralPath $entryPath -Destination $indexPath -Force
}

$capacitorPath = Join-Path $projectRoot 'capacitor.config.json'
$gradlePath = Join-Path $projectRoot 'android\app\build.gradle'
$manifestPath = Join-Path $projectRoot 'android\app\src\main\AndroidManifest.xml'
$stringsPath = Join-Path $projectRoot 'android\app\src\main\res\values\strings.xml'
foreach ($path in @($capacitorPath, $gradlePath, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required Capacitor Android file is missing: $path"
    }
}

$capacitorConfig = Get-Content -LiteralPath $capacitorPath -Raw | ConvertFrom-Json
$capacitorConfig.appId = [string]$setup.appId
$capacitorConfig.appName = [string]$setup.appName
$capacitorConfig.webDir = ([string]$setup.webDir -replace '\\', '/')
$capacitorJson = $capacitorConfig | ConvertTo-Json -Depth 50
$utf8NoBom = New-Object System.Text.UTF8Encoding -ArgumentList $false
[System.IO.File]::WriteAllText(
    $capacitorPath,
    $capacitorJson + [Environment]::NewLine,
    $utf8NoBom
)

$gradleText = Get-Content -LiteralPath $gradlePath -Raw
$applicationIdPattern = '(?m)^([ \t]*applicationId[ \t]+)"[^"]+"'
$applicationIdMatches = [regex]::Matches($gradleText, $applicationIdPattern)
if ($applicationIdMatches.Count -ne 1) {
    throw 'Expected exactly one applicationId declaration in android\app\build.gradle.'
}
$match = $applicationIdMatches[0]
$replacement = $match.Groups[1].Value + '"' + [string]$setup.appId + '"'
$gradleText = $gradleText.Substring(0, $match.Index) + $replacement +
    $gradleText.Substring($match.Index + $match.Length)

$versionCodePattern = '(?m)^([ \t]*versionCode[ \t]+)\d+'
$versionNamePattern = '(?m)^([ \t]*versionName[ \t]+)"[^"]+"'
$versionCodeMatches = [regex]::Matches($gradleText, $versionCodePattern)
$versionNameMatches = [regex]::Matches($gradleText, $versionNamePattern)
if ($versionCodeMatches.Count -ne 1 -or $versionNameMatches.Count -ne 1) {
    throw 'Expected one versionCode and one versionName declaration in android\app\build.gradle.'
}
$match = $versionCodeMatches[0]
$replacement = $match.Groups[1].Value + $versionCode
$gradleText = $gradleText.Substring(0, $match.Index) + $replacement +
    $gradleText.Substring($match.Index + $match.Length)
$match = [regex]::Matches($gradleText, $versionNamePattern)[0]
$replacement = $match.Groups[1].Value + '"' + [string]$setup.versionName + '"'
$gradleText = $gradleText.Substring(0, $match.Index) + $replacement +
    $gradleText.Substring($match.Index + $match.Length)
[System.IO.File]::WriteAllText(
    $gradlePath,
    $gradleText,
    $utf8NoBom
)

$strings = New-Object System.Xml.XmlDocument
$strings.PreserveWhitespace = $true
if (Test-Path -LiteralPath $stringsPath -PathType Leaf) {
    $strings.Load($stringsPath)
} else {
    $resources = $strings.CreateElement('resources')
    [void]$strings.AppendChild($resources)
}

foreach ($item in @(
    @{ Name = 'app_name'; Value = [string]$setup.appName },
    @{ Name = 'title_activity_main'; Value = [string]$setup.appName },
    @{ Name = 'package_name'; Value = [string]$setup.appId },
    @{ Name = 'custom_url_scheme'; Value = [string]$setup.appId }
)) {
    $node = $strings.SelectSingleNode("/resources/string[@name='$($item.Name)']")
    if (-not $node) {
        $node = $strings.CreateElement('string')
        [void]$node.SetAttribute('name', $item.Name)
        [void]$strings.DocumentElement.AppendChild($node)
    }
    $node.InnerText = $item.Value
}
$strings.Save($stringsPath)

$manifest = New-Object System.Xml.XmlDocument
$manifest.PreserveWhitespace = $true
$manifest.Load($manifestPath)
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$namespaces = New-Object System.Xml.XmlNamespaceManager($manifest.NameTable)
$namespaces.AddNamespace('android', $androidNamespace)
$application = $manifest.SelectSingleNode('/manifest/application')
$activity = $manifest.SelectSingleNode('/manifest/application/activity[@android:name=".MainActivity"]', $namespaces)
if (-not $application -or -not $activity) {
    throw 'Could not find the application and .MainActivity elements in AndroidManifest.xml.'
}
[void]$application.SetAttribute('label', $androidNamespace, '@string/app_name')
[void]$activity.SetAttribute('label', $androidNamespace, '@string/app_name')
$manifest.Save($manifestPath)

Write-Host "Configured $($setup.appName) ($($setup.appId))"
Write-Host "Web entry: $($setup.webDir)\index.html"
Write-Host "Version: $($setup.versionName) ($($setup.versionCode))"
Write-Host 'Next run: npm install; npx cap sync android'
