# TermDeck tray icon: the Windows side of the WSL desktop app (counterpart of the Android foreground
# service notification). Installed into %LOCALAPPDATA%\TermDeck by scripts/desktop_app.py and started
# hidden by launcher.vbs (Start Menu shortcut "TermDeck", or ./run.sh).
#
# - makes sure the TermDeck server in WSL answers (starts ./run.sh --no-browser when it does not)
# - opens TermDeck in its own Chrome/Edge app window, or focuses the window that is already open
# - keeps WSL alive while the tray runs, so background sessions survive like on Android
# - menu: Buka TermDeck, Kunci dan Putuskan, Hentikan dan keluar
#
# Keep this file ASCII: Windows PowerShell 5.1 reads files without a BOM as ANSI.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$Config = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'config.json') | ConvertFrom-Json
$Distro = [string]$Config.distro
$Repo = [string]$Config.repo
$Port = [int]$Config.port
$BaseUrl = "http://127.0.0.1:$Port/"
$ProfileDir = Join-Path $PSScriptRoot 'browser'
$IconPath = Join-Path $PSScriptRoot 'termdeck.ico'
$WindowTokenFile = Join-Path $PSScriptRoot "window-token-$Port.txt"
$StartTimeoutSeconds = 180

$script:Notify = $null
$script:Keeper = $null

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class TermDeckWin32 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
"@

function Show-Balloon([string]$Text) {
    if ($null -ne $script:Notify) {
        $script:Notify.ShowBalloonTip(4000, 'TermDeck', $Text, [System.Windows.Forms.ToolTipIcon]::Info)
    }
}

function Show-Error([string]$Text) {
    [void][System.Windows.Forms.MessageBox]::Show($Text, 'TermDeck', 'OK', 'Error')
}

function Invoke-Local([string]$Path, [string]$Method = 'GET', [string]$Token = '') {
    # Returns the response body, or $null when TermDeck does not answer.
    try {
        $req = [System.Net.HttpWebRequest]::Create($BaseUrl + $Path)
        $req.Method = $Method
        $req.Timeout = 3000
        $req.Proxy = $null
        if ($Token) { $req.Headers.Add('X-Token', $Token) }
        if ($Method -ne 'GET') { $req.ContentLength = 0 }
        $resp = $req.GetResponse()
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            return $reader.ReadToEnd()
        } finally {
            $resp.Close()
        }
    } catch {
        return $null
    }
}

function Get-PageToken {
    # The page token proves the port is served by TermDeck (and authorises lock-all).
    $html = Invoke-Local ''
    if ($null -eq $html) { return $null }
    $match = [regex]::Match($html, 'name="termdeck-token" content="([^"]+)"')
    if (-not $match.Success) { return $null }
    return [System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value)
}

function Start-Wsl([string]$Arguments, [switch]$Wait) {
    return Start-Process -FilePath 'wsl.exe' -ArgumentList $Arguments -WindowStyle Hidden -PassThru -Wait:$Wait
}

function Start-Server {
    if (Get-PageToken) { return $true }
    Show-Balloon 'Menyalakan TermDeck...'
    $proc = Start-Wsl "-d `"$Distro`" --cd `"$Repo`" -- ./run.sh --no-browser --port $Port"
    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Get-PageToken) { return $true }
        if ($proc.HasExited -and $proc.ExitCode -ne 0) { break }
        [System.Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 500
    }
    Show-Error ("TermDeck gagal dijalankan di WSL ($Distro).`n`n" +
        "Jalankan ./run.sh di terminal WSL folder $Repo untuk melihat pesan kesalahannya.")
    return $false
}

function Start-Keeper {
    # WSL shuts its VM down once no Windows-side process uses it; this keeps background sessions alive.
    if ($null -eq $script:Keeper -or $script:Keeper.HasExited) {
        $script:Keeper = Start-Wsl "-d `"$Distro`" -- sleep infinity"
    }
}

function Find-Browser {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe')
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

function Get-AppProcesses {
    # Browser processes of the dedicated TermDeck profile (never the user's normal browser windows).
    Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe' OR Name = 'msedge.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ProfileDir) }
}

function Set-AppWindowFocus {
    foreach ($cim in @(Get-AppProcesses)) {
        if ($cim.CommandLine.Contains('--type=')) { continue }
        $proc = Get-Process -Id $cim.ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $proc -or $proc.MainWindowHandle -eq [IntPtr]::Zero) { continue }
        if ([TermDeckWin32]::IsIconic($proc.MainWindowHandle)) {
            [void][TermDeckWin32]::ShowWindow($proc.MainWindowHandle, 9)
        }
        [void][TermDeckWin32]::SetForegroundWindow($proc.MainWindowHandle)
        return $true
    }
    return $false
}

function Open-AppWindow {
    if (-not (Start-Server)) { return $false }
    # The page token changes on every server start: a window opened for an older server would only
    # get "Token tidak valid", so it is replaced instead of focused.
    $token = Get-PageToken
    $previous = $null
    if (Test-Path -LiteralPath $WindowTokenFile) {
        $previous = (Get-Content -Raw -LiteralPath $WindowTokenFile).Trim()
    }
    if ($token -and $token -eq $previous) {
        if (Set-AppWindowFocus) { return $true }
    } else {
        Close-AppWindows
    }
    $browser = Find-Browser
    if ($browser) {
        $arguments = "--app=$BaseUrl --user-data-dir=`"$ProfileDir`" --no-first-run --no-default-browser-check"
        Start-Process -FilePath $browser -ArgumentList $arguments
    } else {
        Start-Process $BaseUrl
    }
    if ($token) { Set-Content -LiteralPath $WindowTokenFile -Value $token -Encoding ASCII }
    return $true
}

function Close-AppWindows {
    foreach ($cim in @(Get-AppProcesses)) {
        Stop-Process -Id $cim.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Lock-TermDeck {
    $token = Get-PageToken
    if (-not $token) {
        Show-Balloon 'TermDeck tidak sedang berjalan.'
        return
    }
    if ($null -eq (Invoke-Local 'api/auth/lock-all' 'POST' $token)) {
        Show-Balloon 'Gagal mengunci TermDeck.'
        return
    }
    Show-Balloon 'TermDeck dikunci dan semua terminal diputus. Masukkan PIN untuk membuka lagi.'
}

function Stop-All {
    Close-AppWindows
    if (Get-PageToken) {
        [void](Start-Wsl "-d `"$Distro`" --cd `"$Repo`" -- ./stop.sh --port $Port" -Wait)
    }
    if ($null -ne $script:Keeper -and -not $script:Keeper.HasExited) {
        Stop-Process -Id $script:Keeper.Id -Force -ErrorAction SilentlyContinue
    }
    $script:Notify.Visible = $false
    $script:Notify.Dispose()
    [System.Windows.Forms.Application]::Exit()
}

# ---- single instance: a second launch only opens/focuses the window ---------------------------

$createdNew = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\TermDeckTray-$Port", [ref]$createdNew)
if (-not $createdNew) {
    [void](Open-AppWindow)
    exit 0
}

# ---- tray icon -------------------------------------------------------------------------------

$script:Notify = New-Object System.Windows.Forms.NotifyIcon
try {
    $script:Notify.Icon = New-Object System.Drawing.Icon($IconPath, 16, 16)
} catch {
    $script:Notify.Icon = [System.Drawing.SystemIcons]::Application
}
$script:Notify.Text = "TermDeck (port $Port)"

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$openItem = $menu.Items.Add('Buka TermDeck')
$openItem.Font = New-Object System.Drawing.Font($openItem.Font, [System.Drawing.FontStyle]::Bold)
$openItem.add_Click({ [void](Open-AppWindow) })
$lockItem = $menu.Items.Add('Kunci dan Putuskan')
$lockItem.add_Click({ Lock-TermDeck })
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$exitItem = $menu.Items.Add('Hentikan TermDeck dan keluar')
$exitItem.add_Click({ Stop-All })
$script:Notify.ContextMenuStrip = $menu
$script:Notify.add_MouseClick({
    param($sender, $e)
    if ($e.Button -eq [System.Windows.Forms.MouseButtons]::Left) { [void](Open-AppWindow) }
})
$script:Notify.Visible = $true

if (-not (Open-AppWindow)) {
    $script:Notify.Visible = $false
    $script:Notify.Dispose()
    exit 1
}
Start-Keeper
[System.Windows.Forms.Application]::Run()
$mutex.ReleaseMutex()
