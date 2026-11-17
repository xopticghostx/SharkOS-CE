<#
.SYNOPSIS
    SharkOS CE installer for Windows. Finds the BYD Shark 6 head unit on your network,
    walks you through switching on ADB, then installs the launcher and SharkOS-CE helper.

.DESCRIPTION
    Run "Install SharkOS CE.bat" (double-click), or run this script directly:
        powershell -ExecutionPolicy Bypass -File Install-SharkOS-CE.ps1

    Works with Windows PowerShell 5.1 and PowerShell 7. No admin rights needed.

    SharkOS CE is free software under the GNU General Public License, version 3.
    It comes with no warranty. Use at your own risk.

.PARAMETER CarIp
    IP address of the head unit. Skips the network scan.
.PARAMETER ApkPath
    Path to the launcher APK. Default: looks beside this script, then in the project's build
    output, then in the latest GitHub release.
.PARAMETER WakeHelperApk
    Path to the SharkOS-CE helper APK.
.PARAMETER SkipWakeHelper
    Don't install the SharkOS-CE helper.
.PARAMETER AdbPath
    Path to adb.exe. Default: looks beside this script, on PATH and in the Android SDK,
    then offers to download Google's platform-tools.
.PARAMETER Unattended
    Never ask questions: take the default answer each time. Needs -CarIp or a car the scan can find.
#>
[CmdletBinding()]
param(
    [string]$CarIp,
    [string]$ApkPath,
    [string]$WakeHelperApk,
    [switch]$SkipWakeHelper,
    [string]$AdbPath,
    [switch]$Unattended
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Pkg          = 'com.sharkhome.launcher'
$MainActivity = "$Pkg/.MainActivity"
$HelperPkg    = 'tunein.player'
$CarService   = 'com.ts.appservice.caradapter'   # BYD's car service: present on the Shark 6 head unit
$AdbPort      = 5555
$Repo         = 'xopticghostx/SharkOS-CE'
$ToolsUrl     = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DataDir   = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'SharkOS-CE' } else { Join-Path $HOME '.sharkos-ce' }
if (-not (Test-Path $DataDir)) { New-Item -ItemType Directory -Path $DataDir -Force | Out-Null }
$LogFile   = Join-Path $DataDir 'install.log'
$StateFile = Join-Path $DataDir 'last-car.txt'
$script:Adb = $null
$script:Serial = $null
$script:GuideShown = $false

# ---------------------------------------------------------------- output helpers

function Log([string]$Text) { Add-Content -Path $LogFile -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Text) }
function Say([string]$Text = '', [string]$Colour = 'Gray') { Write-Host $Text -ForegroundColor $Colour; if ($Text) { Log $Text } }
function Step([string]$Text) { Write-Host ''; Write-Host ("== {0}" -f $Text) -ForegroundColor Cyan; Log "== $Text" }
function Ok([string]$Text)   { Say "  [OK] $Text" 'Green' }
function Warn([string]$Text) { Say "  [!]  $Text" 'Yellow' }
function Bad([string]$Text)  { Say "  [X]  $Text" 'Red' }
function Info([string]$Text) { Say "       $Text" }

function Ask([string]$Question, [string]$Default = '') {
    if ($Unattended) { Log "ask (unattended): $Question -> $Default"; return $Default }
    $suffix = if ($Default) { " [$Default]" } else { '' }
    $answer = Read-Host ("  {0}{1}" -f $Question, $suffix)
    if ($null -eq $answer -or $answer.Trim() -eq '') { $answer = $Default }
    Log "ask: $Question -> $answer"
    return $answer.Trim()
}

function AskYesNo([string]$Question, [bool]$Default = $true) {
    $hint = if ($Default) { 'Y/n' } else { 'y/N' }
    while ($true) {
        $a = Ask "$Question ($hint)" ''
        if ($a -eq '') { return $Default }
        if ($a -match '^(y|yes)$') { return $true }
        if ($a -match '^(n|no)$')  { return $false }
    }
}

function Stop-Installer([string]$Message, [int]$Code = 1) {
    Write-Host ''
    if ($Code -eq 0) { Say $Message } else { Bad $Message }
    Info "Log file: $LogFile"
    exit $Code
}

# ---------------------------------------------------------------- adb

function Invoke-Adb {
    # Runs adb and returns its exit code and combined output. Never throws on a failed command.
    param([string[]]$Arguments, [int]$TimeoutSec = 120, [switch]$NoSerial)
    $all = @()
    if ($script:Serial -and -not $NoSerial) { $all += @('-s', $script:Serial) }
    $all += $Arguments
    $quoted = $all | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:Adb
    $psi.Arguments = ($quoted -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        try { $p.Kill() } catch { }
        Log "adb $($psi.Arguments) -> timed out after $TimeoutSec s"
        return [pscustomobject]@{ Code = -1; Output = "timed out after $TimeoutSec s" }
    }
    $p.WaitForExit()
    $text = (($outTask.Result + "`n" + $errTask.Result) -replace "`r", '').Trim()
    $logText = if ($text.Length -gt 400) { $text.Substring(0, 400) + ' ...(trimmed)' } else { $text }
    Log "adb $($psi.Arguments) -> [$($p.ExitCode)] $logText"
    return [pscustomobject]@{ Code = $p.ExitCode; Output = $text }
}

function Find-Adb {
    $exe = if ($env:OS -eq 'Windows_NT') { 'adb.exe' } else { 'adb' }
    $candidates = @()
    if ($AdbPath) { $candidates += $AdbPath }
    $candidates += (Join-Path $ScriptDir $exe)
    $candidates += (Join-Path (Join-Path $ScriptDir 'platform-tools') $exe)
    $candidates += (Join-Path (Join-Path $DataDir 'platform-tools') $exe)
    $onPath = Get-Command $exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates += $onPath.Source }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\$exe") }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\$exe") }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c -PathType Leaf)) { return (Resolve-Path $c).Path } }
    return $null
}

function Get-AdbTool {
    Step 'Step 1 of 5: ADB tool'
    $found = Find-Adb
    if ($AdbPath -and -not $found) { Stop-Installer "adb was not found at $AdbPath" }
    if (-not $found) {
        Info 'ADB (Android Debug Bridge) is the Google tool that talks to the head unit.'
        Info "It isn't on this PC yet. It can be downloaded from Google (about 7 MB):"
        Info $ToolsUrl
        if (-not (AskYesNo 'Download it now?' $true)) {
            Stop-Installer 'ADB is needed to continue. Put adb.exe beside this installer, or run it again and choose Y.'
        }
        $zip = Join-Path $DataDir 'platform-tools.zip'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $ToolsUrl -OutFile $zip -UseBasicParsing
            Expand-Archive -Path $zip -DestinationPath $DataDir -Force
            Remove-Item $zip -Force
        } catch {
            Stop-Installer "The download failed: $($_.Exception.Message). Check your internet connection, or download platform-tools yourself and put adb.exe beside this installer."
        }
        $found = Find-Adb
        if (-not $found) { Stop-Installer 'ADB was downloaded but adb.exe could not be found afterwards.' }
    }
    $script:Adb = $found
    $v = Invoke-Adb -Arguments @('version') -NoSerial
    if ($v.Code -ne 0) { Stop-Installer "adb would not run: $($v.Output)" }
    Ok ("Using {0} ({1})" -f $found, ($v.Output -split "`n")[0])
    [void](Invoke-Adb -Arguments @('start-server') -NoSerial -TimeoutSec 30)
}

# ---------------------------------------------------------------- APK files

function Find-Apk([string[]]$Patterns, [string[]]$Folders, [string]$Exclude) {
    foreach ($f in $Folders) {
        if (-not (Test-Path $f)) { continue }
        foreach ($pat in $Patterns) {
            $hit = Get-ChildItem -Path $f -Filter $pat -File -ErrorAction SilentlyContinue |
                Where-Object { -not $Exclude -or $_.Name -notmatch $Exclude } |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}

function Get-ReleaseApks {
    # Downloads the APKs from the latest GitHub release. Returns $true if any were saved.
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'SharkOS-CE-Installer' }
    } catch {
        Log "release lookup failed: $($_.Exception.Message)"
        return $false
    }
    $assets = @($rel.assets | Where-Object { $_.name -like '*.apk' })
    if ($assets.Count -eq 0) { return $false }
    $dir = Join-Path $DataDir 'apk'
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Get-ChildItem $dir -Filter '*.apk' -ErrorAction SilentlyContinue | Remove-Item -Force
    foreach ($a in $assets) {
        Info "Downloading $($a.name) from release $($rel.tag_name)..."
        Invoke-WebRequest -Uri $a.browser_download_url -OutFile (Join-Path $dir $a.name) -UseBasicParsing
    }
    return $true
}

function Get-ReleaseHelper([string[]]$Patterns) {
    # Downloads just the SharkOS-CE helper APK from the latest GitHub release. Returns its path, or $null.
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'SharkOS-CE-Installer' }
        $a = @($rel.assets | Where-Object { $_.name -like '*.apk' -and $_.name -match 'helper' }) | Select-Object -First 1
        if (-not $a) { Log 'no helper APK in the latest release'; return $null }
        $dir = Join-Path $DataDir 'apk'
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $target = Join-Path $dir $a.name
        Info "Downloading $($a.name) from release $($rel.tag_name)..."
        Invoke-WebRequest -Uri $a.browser_download_url -OutFile $target -UseBasicParsing
        return $target
    } catch {
        Log "helper download failed: $($_.Exception.Message)"
        return $null
    }
}

function Get-Apks {
    Step 'Step 2 of 5: SharkOS CE files'
    $helperPatterns = @('*helper*.apk')
    $appPatterns    = @('*sharkos*.apk', '*shark*launcher*.apk', 'app-release*.apk', 'app-debug*.apk')
    $root = Split-Path -Parent $ScriptDir
    $appFolders = @($ScriptDir, (Join-Path $ScriptDir 'apk'),
        (Join-Path $root 'app\build\outputs\apk\release'), (Join-Path $root 'app\build\outputs\apk\debug'))
    $helperFolders = @($ScriptDir, (Join-Path $ScriptDir 'apk'),
        (Join-Path $root 'wakehelper\build\outputs\apk\release'), (Join-Path $root 'wakehelper\build\outputs\apk\debug'))

    $app = $ApkPath
    $helper = $WakeHelperApk
    if (-not $app)    { $app = Find-Apk $appPatterns $appFolders 'helper' }
    if (-not $helper) { $helper = Find-Apk $helperPatterns $helperFolders $null }

    if (-not $app) {
        Info 'No launcher APK beside the installer. Checking the latest GitHub release...'
        if (Get-ReleaseApks) {
            $dl = @(Join-Path $DataDir 'apk')
            $app = Find-Apk @('*.apk') $dl 'helper'
            if (-not $helper) { $helper = Find-Apk $helperPatterns $dl $null }
        } else {
            Warn "No release APK could be downloaded from github.com/$Repo."
        }
    }
    while (-not $app -or -not (Test-Path $app -PathType Leaf)) {
        if ($app) { Bad "Not found: $app" }
        if ($Unattended) { Stop-Installer 'No launcher APK found. Pass -ApkPath, or put the APK beside the installer.' }
        Info 'Put the SharkOS CE APK in the same folder as this installer, or type its full path.'
        $app = (Ask 'Path to the launcher APK (blank to quit)' '').Trim('"')
        if (-not $app) { Stop-Installer 'Stopped: no launcher APK.' }
    }
    Ok "Launcher:    $app"

    if (-not $SkipWakeHelper -and (-not $helper -or -not (Test-Path $helper -PathType Leaf))) {
        Info 'No SharkOS-CE helper APK beside the installer. Checking the latest GitHub release...'
        $helper = Get-ReleaseHelper $helperPatterns
    }

    if ($SkipWakeHelper) { $helper = $null; Info 'SharkOS-CE helper: skipped (-SkipWakeHelper).' }
    elseif ($helper -and (Test-Path $helper -PathType Leaf)) { Ok "SharkOS-CE helper: $helper" }
    else {
        $helper = $null
        Warn 'SharkOS-CE helper APK not found. The launcher will install, but it will not reopen by itself after the car sleeps.'
    }
    return [pscustomobject]@{ App = (Resolve-Path $app).Path; Helper = $helper }
}

# ---------------------------------------------------------------- finding the car

function Get-LocalSubnets {
    # The /24 around each of this PC's private IPv4 addresses, as 'a.b.c' prefixes.
    $prefixes = New-Object System.Collections.Generic.List[string]
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($nic.OperationalStatus -ne 'Up') { continue }
        if ($nic.NetworkInterfaceType -eq 'Loopback' -or $nic.NetworkInterfaceType -eq 'Tunnel') { continue }
        foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
            $ip = $ua.Address
            if ($ip.AddressFamily -ne 'InterNetwork') { continue }
            $b = $ip.GetAddressBytes()
            $private = ($b[0] -eq 10) -or ($b[0] -eq 192 -and $b[1] -eq 168) -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31)
            if (-not $private -and -not $env:SHARKOS_SCAN_ANY) { continue }
            $p = "{0}.{1}.{2}" -f $b[0], $b[1], $b[2]
            if (-not $prefixes.Contains($p)) { $prefixes.Add($p) }
        }
    }
    return $prefixes
}

function Test-AdbPort([string[]]$Addresses, [int]$TimeoutMs = 1200) {
    # Tries port 5555 on every address at once.
    # State: Open = ADB is listening. Closed = a device answered but ADB is off. None = nothing there.
    $jobs = foreach ($a in $Addresses) {
        $c = New-Object System.Net.Sockets.TcpClient
        [pscustomobject]@{ Address = $a; Client = $c; Task = $c.ConnectAsync($a, $AdbPort) }
    }
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        if (-not ($jobs | Where-Object { -not $_.Task.IsCompleted })) { break }
        Start-Sleep -Milliseconds 50
    }
    foreach ($j in $jobs) {
        $state = 'None'
        if ($j.Task.IsCompleted) {
            if ($j.Task.IsFaulted) {
                $ex = $j.Task.Exception.GetBaseException()
                if ($ex -is [System.Net.Sockets.SocketException] -and $ex.SocketErrorCode -eq 'ConnectionRefused') { $state = 'Closed' }
            } elseif ($j.Client.Connected) { $state = 'Open' }
        }
        try { $j.Client.Close() } catch { }
        [pscustomobject]@{ Address = $j.Address; State = $state }
    }
}

function Get-HostNames([string[]]$Addresses, [int]$TimeoutMs = 2000) {
    $names = @{}
    $jobs = foreach ($a in $Addresses) { [pscustomobject]@{ Address = $a; Task = [System.Net.Dns]::GetHostEntryAsync($a) } }
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        if (-not ($jobs | Where-Object { -not $_.Task.IsCompleted })) { break }
        Start-Sleep -Milliseconds 50
    }
    foreach ($j in $jobs) {
        if ($j.Task.IsCompleted -and -not $j.Task.IsFaulted -and $j.Task.Result.HostName -and $j.Task.Result.HostName -ne $j.Address) {
            $names[$j.Address] = $j.Task.Result.HostName
        }
    }
    return $names
}

function Find-Devices {
    # Scans this PC's local networks. Returns the addresses with ADB on, and those that answered with ADB off.
    $subnets = @(Get-LocalSubnets)
    if ($subnets.Count -eq 0) {
        Warn 'This PC has no local network connection to scan. Connect it to the same Wi-Fi as the car.'
        return [pscustomobject]@{ Open = @(); Closed = @(); Subnets = @() }
    }
    $open = @(); $closed = @()
    foreach ($s in $subnets) {
        Info "Scanning $s.1 to $s.254 ..."
        $results = Test-AdbPort (1..254 | ForEach-Object { "$s.$_" })
        $open   += @($results | Where-Object { $_.State -eq 'Open' }   | ForEach-Object { $_.Address })
        $closed += @($results | Where-Object { $_.State -eq 'Closed' } | ForEach-Object { $_.Address })
    }
    Log ("scan: open=[{0}] closed=[{1}]" -f ($open -join ','), ($closed -join ','))
    return [pscustomobject]@{ Open = $open; Closed = $closed; Subnets = $subnets }
}

function Show-FindIpGuide {
    Say ''
    Say '  How to find the head unit''s IP address' 'White'
    Info '1. On the car screen, tap the Wi-Fi symbol at the top right.'
    Info '2. Connect to the SAME Wi-Fi network this PC is on.'
    Info '   No Wi-Fi in reach of the car? Turn on your phone''s hotspot and connect both the car and this PC to it.'
    Info '3. Once connected, the car shows its IP address (for example 192.168.1.123).'
    Say ''
}

function Show-EnableAdbGuide {
    if ($script:GuideShown) {
        Info 'Reminder: Car > System > Version, tap the words "Factory Reset" about 10 times, rotate to portrait,'
        Info 'then press the "CONNECT USB TO ENABLE DEBUGGING MODE" button. Full steps are above.'
        return
    }
    $script:GuideShown = $true
    Say ''
    Say '  How to switch on ADB (debugging) on the BYD Shark 6' 'White'
    Info 'Do this parked, with the car switched on.'
    Info '1. On the car screen go to  Car > System > Version.'
    Info '2. Tap the WORDS "Factory Reset" quickly, about 10 times (some firmware needs only 5).'
    Info '   Tap the text itself, NOT the "Reset" button on the right. Nothing is reset.'
    Info '3. A hidden menu opens. Rotate the screen to portrait: some options don''t show in landscape.'
    Info '4. Press the button at the top that starts "CONNECT USB TO ENABLE DEBUGGING MODE".'
    Info '5. A message says "Turn on ADB debugging !". That means ADB is now ON.'
    Info '   (Pressing the same button again turns it off.)'
    Info '6. Rotate the screen back to landscape.'
    Say ''
}

function Read-CarIp {
    while ($true) {
        $ip = Ask 'Type the car''s IP address (blank to go back)' ''
        if (-not $ip) { return $null }
        $ip = ($ip -replace ':\d+$', '')
        $parsed = $null
        if ($ip -match '^\d{1,3}(\.\d{1,3}){3}$' -and [System.Net.IPAddress]::TryParse($ip, [ref]$parsed)) { return $ip }
        Bad "'$ip' isn't an IP address. It should look like 192.168.1.123."
    }
}

function Find-Car {
    Step 'Step 3 of 5: Find the car'
    Info 'The car must be switched on and connected to the same Wi-Fi network as this PC.'
    $target = $CarIp
    $firstPass = $true

    while ($true) {
        if (-not $target -and $firstPass -and (Test-Path $StateFile)) {
            $last = (Get-Content $StateFile -TotalCount 1).Trim()
            if ($last) {
                $r = Test-AdbPort @($last) 1500
                if ($r.State -eq 'Open') { Ok "The car used last time is answering at $last."; return $last }
                if ($r.State -eq 'Closed') {
                    Warn "The car used last time ($last) is on the network, but ADB is switched OFF."
                    Show-EnableAdbGuide
                    if (-not $Unattended) { [void](Ask 'Press Enter when ADB is on') ; $target = $last }
                }
            }
        }
        $firstPass = $false

        if ($target) {
            # A specific address: say exactly what is wrong with it.
            $r = Test-AdbPort @($target) 2500
            if ($r.State -eq 'Open') { Ok "ADB is on at $target."; return $target }
            if ($r.State -eq 'Closed') {
                Warn "Found a device at $target, but ADB is NOT switched on."
                Show-EnableAdbGuide
            } else {
                Bad "Nothing answered at $target."
                Info 'Check that:'
                Info '- the car is switched on and its screen is awake'
                Info '- the IP address is typed correctly (it can change when the car reconnects)'
                Info '- the car and this PC are on the same Wi-Fi (a "guest" network often blocks devices from seeing each other)'
            }
            if ($Unattended) { Stop-Installer "Could not reach ADB at $target." }
            $choice = Ask '[T]ry again, [S]can the network, type a different [I]P, or [Q]uit' 'T'
            switch -Regex ($choice) {
                '^[Tt]' { }
                '^[Ss]' { $target = $null }
                '^[Ii]' { Show-FindIpGuide; $new = Read-CarIp; if ($new) { $target = $new } }
                '^[Qq]' { Stop-Installer 'Stopped.' 0 }
            }
            continue
        }

        $scan = Find-Devices
        if ($scan.Open.Count -eq 1) { Ok "Found a device with ADB on at $($scan.Open[0])."; return $scan.Open[0] }
        if ($scan.Open.Count -gt 1) {
            Ok "Found $($scan.Open.Count) devices with ADB on:"
            for ($i = 0; $i -lt $scan.Open.Count; $i++) { Info ("{0}. {1}" -f ($i + 1), $scan.Open[$i]) }
            # The Shark 6 is the one running BYD's car service.
            foreach ($a in $scan.Open) {
                $s = "${a}:$AdbPort"
                [void](Invoke-Adb -Arguments @('connect', $s) -NoSerial -TimeoutSec 15)
                $chk = Invoke-Adb -Arguments @('-s', $s, 'shell', 'pm', 'list', 'packages', $CarService) -NoSerial -TimeoutSec 15
                if ($chk.Output -match [regex]::Escape("package:$CarService")) { Ok "$a is the BYD head unit."; return $a }
                [void](Invoke-Adb -Arguments @('disconnect', $s) -NoSerial -TimeoutSec 10)
            }
            if ($Unattended) { Stop-Installer 'More than one ADB device found and none could be confirmed as the BYD. Pass -CarIp.' }
            $n = Ask 'Which one is the car? Type its number' '1'
            $idx = 0
            if ([int]::TryParse($n, [ref]$idx) -and $idx -ge 1 -and $idx -le $scan.Open.Count) { return $scan.Open[$idx - 1] }
            continue
        }

        # Nothing has ADB on.
        Warn 'No device with ADB switched on was found.'
        if ($scan.Closed.Count -gt 0) {
            $names = Get-HostNames $scan.Closed
            $likely = @($scan.Closed | Where-Object { $names[$_] -match 'byd|dilink|android' })
            if ($likely.Count -gt 0) {
                Warn 'This device could be the car, but ADB is switched OFF on it:'
                foreach ($a in $likely) { Info ("- {0}  ({1})" -f $a, $names[$a]) }
            } else {
                Info "$($scan.Closed.Count) other device(s) answered on the network, all with ADB off. One of them may be the car."
            }
            Info 'If you have not switched on ADB in the car yet, do that now:'
        } else {
            Info 'Nothing on the network looked like the car. It may be on a different Wi-Fi, or ADB is off.'
        }
        Show-EnableAdbGuide
        if ($Unattended) { Stop-Installer 'The car was not found. Switch on ADB, or pass -CarIp.' }
        $choice = Ask '[S]can again, type the car''s [I]P, or [Q]uit' 'S'
        switch -Regex ($choice) {
            '^[Ii]' { Show-FindIpGuide; $new = Read-CarIp; if ($new) { $target = $new } }
            '^[Qq]' { Stop-Installer 'Stopped.' 0 }
        }
    }
}

# ---------------------------------------------------------------- connect

function Get-DeviceState {
    $d = Invoke-Adb -Arguments @('devices') -NoSerial -TimeoutSec 15
    foreach ($line in ($d.Output -split "`n")) {
        if ($line -match ('^' + [regex]::Escape($script:Serial) + '\s+(\S+)')) { return $Matches[1] }
    }
    return 'missing'
}

function Connect-Car([string]$Ip) {
    Step 'Step 4 of 5: Connect'
    $script:Serial = "${Ip}:$AdbPort"
    $promptShown = $false
    for ($try = 1; $try -le 40; $try++) {
        $c = Invoke-Adb -Arguments @('connect', $script:Serial) -NoSerial -TimeoutSec 20
        $state = Get-DeviceState
        if ($state -eq 'device') { break }
        if ($state -eq 'unauthorized' -or $c.Output -match 'unauthorized|failed to authenticate') {
            if (-not $promptShown) {
                Warn 'The car is asking for permission.'
                Info 'Look at the car screen: a box says "Allow USB debugging?".'
                Info 'Tick "Always allow from this computer", then tap Allow (or OK).'
                Info 'Waiting for you to tap Allow...'
                $promptShown = $true
            }
            Start-Sleep -Seconds 3
            if ($try % 5 -eq 0) { [void](Invoke-Adb -Arguments @('disconnect', $script:Serial) -NoSerial -TimeoutSec 10) }
            continue
        }
        if ($try -ge 3 -and -not $promptShown) {
            Stop-Installer "Could not connect to $($script:Serial): $($c.Output)"
        }
        Start-Sleep -Seconds 2
    }
    if ((Get-DeviceState) -ne 'device') {
        Stop-Installer 'The car never allowed the connection. Run the installer again and tap Allow on the car screen. If no box appears, turn ADB off and on again in the hidden menu.'
    }
    Ok "Connected to $($script:Serial)."
    Set-Content -Path $StateFile -Value $Ip

    $model = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')).Output
    $android = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.release')).Output
    $fw = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.display.id')).Output
    Info "Device: $model, Android $android, build $fw"
    $svc = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', $CarService)
    if ($svc.Output -match [regex]::Escape("package:$CarService")) {
        Ok 'This is a BYD head unit (BYD''s car service is present).'
    } else {
        Warn 'This does not look like a BYD Shark 6 head unit: BYD''s car service was not found on it.'
        Warn 'It may be a phone, TV box or another Android device on your network.'
        if (-not (AskYesNo 'Install on this device anyway?' $false)) { Stop-Installer 'Stopped: wrong device.' 0 }
    }
    if ($fw -and $fw -notmatch 'SOC_260811_S') {
        Info 'Note: SharkOS CE has only been tested on firmware SOC_260811_S. Other versions may behave differently.'
    }
}

# ---------------------------------------------------------------- install

$Permissions = @(
    'android.car.permission.CAR_INFO',
    'android.car.permission.CAR_ENERGY',
    'android.car.permission.CAR_ENERGY_PORTS',
    'android.car.permission.CAR_POWERTRAIN',
    'android.car.permission.CAR_EXTERIOR_ENVIRONMENT',
    'android.car.permission.READ_CAR_DISPLAY_UNITS',
    'android.car.permission.CAR_CONTROL_AUDIO_VOLUME',
    'android.car.permission.CAR_CONTROL_AUDIO_SETTINGS',
    'car.permission.CARPLAY',
    'car.permission.CARPLAY_APP',
    'android.permission.BYDAUTO_AC_COMMON',
    'android.permission.BYDAUTO_AC_GET',
    'android.permission.BYDAUTO_AC_SET',
    'android.permission.BYDAUTO_ADAS_COMMON',
    'android.permission.BYDAUTO_ADAS_GET',
    'android.permission.BYDAUTO_AUDIO_COMMON',
    'android.permission.BYDAUTO_AUDIO_GET',
    'android.permission.BYDAUTO_AUDIO_SET',
    'android.permission.BYDAUTO_BODYWORK_COMMON',
    'android.permission.BYDAUTO_BODYWORK_GET',
    'android.permission.BYDAUTO_BODYWORK_SET',
    'android.permission.BYDAUTO_CHARGING_COMMON',
    'android.permission.BYDAUTO_CHARGING_GET',
    'android.permission.BYDAUTO_DOOR_LOCK_COMMON',
    'android.permission.BYDAUTO_DOOR_LOCK_GET',
    'android.permission.BYDAUTO_ENGINE_COMMON',
    'android.permission.BYDAUTO_ENGINE_GET',
    'android.permission.BYDAUTO_GEARBOX_COMMON',
    'android.permission.BYDAUTO_GEARBOX_GET',
    'android.permission.BYDAUTO_INSTRUMENT_COMMON',
    'android.permission.BYDAUTO_INSTRUMENT_GET',
    'android.permission.BYDAUTO_LIGHT_COMMON',
    'android.permission.BYDAUTO_LIGHT_GET',
    'android.permission.BYDAUTO_LIGHT_SET',
    'android.permission.BYDAUTO_LOCATION_COMMON',
    'android.permission.BYDAUTO_LOCATION_GET',
    'android.permission.BYDAUTO_PANORAMA_COMMON',
    'android.permission.BYDAUTO_PANORAMA_GET',
    'android.permission.BYDAUTO_PM2P5_COMMON',
    'android.permission.BYDAUTO_PM2P5_GET',
    'android.permission.BYDAUTO_POWER_COMMON',
    'android.permission.BYDAUTO_POWER_GET',
    'android.permission.BYDAUTO_RADAR_COMMON',
    'android.permission.BYDAUTO_RADAR_GET',
    'android.permission.BYDAUTO_REAR_VIEW_MIRROR_COMMON',
    'android.permission.BYDAUTO_REAR_VIEW_MIRROR_GET',
    'android.permission.BYDAUTO_REAR_VIEW_MIRROR_SET',
    'android.permission.BYDAUTO_REMINDER_COMMON',
    'android.permission.BYDAUTO_REMINDER_GET',
    'android.permission.BYDAUTO_SAFETY_BELT_COMMON',
    'android.permission.BYDAUTO_SAFETY_BELT_GET',
    'android.permission.BYDAUTO_SENSOR_COMMON',
    'android.permission.BYDAUTO_SENSOR_GET',
    'android.permission.BYDAUTO_SETTING_COMMON',
    'android.permission.BYDAUTO_SETTING_GET',
    'android.permission.BYDAUTO_SETTING_SET',
    'android.permission.BYDAUTO_SPEED_COMMON',
    'android.permission.BYDAUTO_SPEED_GET',
    'android.permission.BYDAUTO_STATISTIC_COMMON',
    'android.permission.BYDAUTO_STATISTIC_GET',
    'android.permission.BYDAUTO_TIME_COMMON',
    'android.permission.BYDAUTO_TIME_GET',
    'android.permission.BYDAUTO_TYRE_COMMON',
    'android.permission.BYDAUTO_TYRE_GET',
    'android.permission.BYDAUTO_VERSION_COMMON',
    'android.permission.BYDAUTO_VERSION_GET',
    'android.permission.BYDAUTO_OTA_COMMON',
    'android.permission.BYDAUTO_OTA_GET',
    'android.permission.BYDAUTO_YUN_GET',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.WRITE_SECURE_SETTINGS'
)

function Install-Apk([string]$Path, [string]$PackageName, [string]$Label, [string]$IncompatibleHelp) {
    Info "Installing $Label (this can take a minute)..."
    $r = Invoke-Adb -Arguments @('install', '-r', '-g', $Path) -TimeoutSec 300
    if ($r.Output -match 'Success') { Ok "$Label installed."; return $true }
    if ($r.Output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_VERSION_DOWNGRADE') {
        Warn "A different build of $PackageName is already on the car and can't be updated in place."
        if ($IncompatibleHelp) { Info $IncompatibleHelp }
        if (AskYesNo "Remove the existing $PackageName and install this one?" $false) {
            [void](Invoke-Adb -Arguments @('uninstall', $PackageName) -TimeoutSec 60)
            $r = Invoke-Adb -Arguments @('install', '-r', '-g', $Path) -TimeoutSec 300
            if ($r.Output -match 'Success') { Ok "$Label installed."; return $true }
        } else { Warn "$Label was not installed."; return $false }
    }
    Bad "$Label did not install:"
    Info (($r.Output -split "`n") | Select-Object -Last 3 | Out-String).Trim()
    return $false
}

function Install-SharkOS($Apks) {
    Step 'Step 5 of 5: Install'
    $stock = (Invoke-Adb -Arguments @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-c', 'android.intent.category.HOME', '-a', 'android.intent.action.MAIN')).Output
    $stockHome = (($stock -split "`n") | Where-Object { $_ -match '/' } | Select-Object -Last 1)
    if ($stockHome) { $stockHome = $stockHome.Trim() }
    if ($stockHome -and $stockHome -notmatch [regex]::Escape($Pkg)) {
        Set-Content -Path (Join-Path $DataDir 'stock-launcher.txt') -Value $stockHome
        Info "Current home screen: $stockHome (it will stay as it is)"
    }

    $help = 'Removing it deletes that build''s saved settings and home screen layout.'
    if (-not (Install-Apk $Apks.App $Pkg 'SharkOS CE launcher' $help)) { Stop-Installer 'The launcher could not be installed.' }

    Info 'Granting permissions...'
    foreach ($perm in $Permissions) {
        [void](Invoke-Adb -Arguments @('shell', 'pm', 'grant', $Pkg, $perm) -TimeoutSec 30)
    }
    [void](Invoke-Adb -Arguments @('shell', 'appops', 'set', $Pkg, 'SYSTEM_ALERT_WINDOW', 'allow'))
    [void](Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'allow_listener', "$Pkg/com.sharkhome.launcher.media.MediaListenerService"))
    # Count what the car really reports as granted, not what each grant command said
    # (the install itself already grants most of them, so the grant command has nothing to do).
    $dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Pkg) -TimeoutSec 30).Output
    $grantedNow = @{}
    foreach ($m in [regex]::Matches($dump, '(?m)^\s*([A-Za-z0-9_.]+): granted=true')) { $grantedNow[$m.Groups[1].Value] = $true }
    $missing = @($Permissions | Where-Object { -not $grantedNow.ContainsKey($_) })
    $granted = $Permissions.Count - $missing.Count
    Log ('permissions not granted: ' + ($missing -join ', '))
    if ($missing.Count -eq 0) { Ok "All $($Permissions.Count) permissions are granted." }
    else {
        Ok "$granted of $($Permissions.Count) permissions are granted."
        Info "$($missing.Count) are not available on this car's firmware. SharkOS CE still installs; a feature that needs one may not work."
        Info 'Their names are in the log file.'
    }

    $helperInstalled = $false
    if ($Apks.Helper) {
        $help = 'That package name is also used by the real TuneIn Radio app. Only one can be installed.'
        $helperInstalled = Install-Apk $Apks.Helper $HelperPkg 'SharkOS-CE helper' $help
    }

    Info 'Starting SharkOS CE on the car...'
    $s = Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $MainActivity)
    if ($s.Output -match 'Error') { Warn 'The app is installed but did not start. Open "SharkOS-CE" from the car''s app list.' }
    else { Ok 'SharkOS CE is open on the car screen.' }

    return [pscustomobject]@{ Helper = $helperInstalled; StockHome = $stockHome }
}

# ---------------------------------------------------------------- main

try {
    Clear-Host
} catch { }
Log '---------------- installer started ----------------'
Say ''
Say '  SharkOS CE installer for the BYD Shark 6' 'Cyan'
Say '  ----------------------------------------' 'Cyan'
Say '  This will:'
Say '    1. get the ADB tool      2. find the SharkOS CE files      3. find your car on the network'
Say '    4. connect to it         5. install the launcher, its permissions and the helper'
Say ''
Say '  Before you start: park the car, switch it on, and connect it to the same Wi-Fi as this PC.' 'White'
Say '  SharkOS CE is an unofficial community project with no warranty. It is not made by BYD.' 'DarkGray'
Say '  Your BYD home screen is not changed: SharkOS CE is installed as an extra app.' 'DarkGray'
if (-not (AskYesNo 'Ready to start?' $true)) { Stop-Installer 'Stopped.' 0 }

try {
    Get-AdbTool
    $apks = Get-Apks
    $ip = Find-Car
    Connect-Car $ip
    $result = Install-SharkOS $apks
} catch {
    Log ("unexpected error: {0}`n{1}" -f $_.Exception.Message, $_.ScriptStackTrace)
    Stop-Installer "Something went wrong: $($_.Exception.Message)"
}

Write-Host ''
Say '  All done.' 'Green'
Say ''
Say '  What to do next, on the car screen:' 'White'
Info '1. SharkOS CE is open on the car screen. Your BYD home screen has not been changed.'
Info '   To open SharkOS CE later, find "SharkOS-CE" in the car''s app list.'
Info '2. In SharkOS CE open  Vehicle > Diagnostics  and check the car connection shows Connected.'
if ($result.Helper) { Info '3. The SharkOS-CE helper is installed: SharkOS CE reopens by itself when the car wakes up.' }
else { Info '3. The SharkOS-CE helper is NOT installed: after the car sleeps, open SharkOS CE from the app list.' }
Say ''
Say '  Good to know:' 'White'
Info 'Leave ADB switched on if you want the in-app volume buttons, app installer and permission manager'
Info '(they use the head unit''s own ADB). Otherwise switch it off again in the hidden menu.'
Info "To remove SharkOS CE:  adb uninstall $Pkg   and   adb uninstall $HelperPkg"
Info "Log file: $LogFile"
[void](Invoke-Adb -Arguments @('disconnect', $script:Serial) -NoSerial -TimeoutSec 10)
exit 0
