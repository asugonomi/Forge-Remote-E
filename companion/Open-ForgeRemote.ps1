[CmdletBinding()]
param(
    [string]$ForgeRoot,
    [string]$Python,
    [ValidateRange(1,65535)][int]$ForgePort
)
$ErrorActionPreference = 'Stop'

function Resolve-ForgeFolder([string]$Path) {
    if (!$Path -or !(Test-Path -LiteralPath $Path -PathType Container)) { return }
    $dir = Get-Item -LiteralPath $Path
    if ($dir.PSProvider.Name -ne 'FileSystem') { return }
    if ((Test-Path -LiteralPath (Join-Path $dir.FullName 'launch.py') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $dir.FullName 'modules') -PathType Container) -and
        ((Test-Path -LiteralPath (Join-Path $dir.FullName 'modules_forge') -PathType Container) -or
         (Test-Path -LiteralPath (Join-Path $dir.FullName 'backend') -PathType Container))) {
        return $dir.FullName
    }
}

function Find-ForgeAncestor([string]$Path) {
    $node = Get-Item -LiteralPath $Path
    while ($null -ne $node) {
        $valid = Resolve-ForgeFolder $node.FullName
        if ($valid) { return $valid }
        $node = $node.Parent
    }
}

function Resolve-ForgePython([string]$Path) {
    if ($Path -and [IO.Path]::IsPathRooted($Path) -and
        ([IO.Path]::GetFileName($Path) -ieq 'python.exe') -and
        (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return (Get-Item -LiteralPath $Path).FullName
    }
}

function Get-ForgeProcessMatch($Process) {
    # Consider a directly invoked Forge entry script only. Never run the process's command line.
    $pythonFile = Resolve-ForgePython $Process.ExecutablePath
    if (!$pythonFile -or !$Process.CommandLine) { return }
    $argv = @([regex]::Matches($Process.CommandLine, '(?:^|\s)(?:"([^"]*)"|([^\s"]+))') | ForEach-Object {
        if ($_.Groups[1].Success) { $_.Groups[1].Value } else { $_.Groups[2].Value }
    })
    $i = 1
    while ($i -lt $argv.Count -and $argv[$i] -in @('-u','-B','-s','-E','-I','-O','-OO')) { $i++ }
    if ($i -ge $argv.Count) { return }
    $entry = $argv[$i]
    if ([IO.Path]::GetFileName($entry) -notin @('launch.py','webui.py')) { return }
    $valid=$null
    if ([IO.Path]::IsPathRooted($entry)) {
        $valid = Resolve-ForgeFolder ([IO.Path]::GetDirectoryName($entry))
    } elseif ($entry -match '^(?:\.[\\/])?(?:launch|webui)\.py$') {
        $scripts = (Get-Item -LiteralPath $pythonFile).Directory
        if ($scripts.Name -ine 'Scripts' -or !$scripts.Parent -or !$scripts.Parent.Parent) { return }
        $valid = Resolve-ForgeFolder $scripts.Parent.Parent.FullName
    }
    if (!$valid) { return }
    $port = 7860
    for ($j=$i+1; $j -lt $argv.Count; $j++) {
        $portText = $null
        if ($argv[$j] -eq '--port' -and $j+1 -lt $argv.Count) { $portText=$argv[$j+1] }
        elseif ($argv[$j] -match '^--port=(\d{1,5})$') { $portText=$Matches[1] }
        $number=0
        if ([int]::TryParse($portText,[ref]$number) -and $number -ge 1 -and $number -le 65535) { $port=$number }
    }
    [pscustomobject]@{ root=$valid; python=$pythonFile; port=$port }
}

function Get-LiveForge {
    try {
        $processes = Get-CimInstance Win32_Process -Filter "Name='python.exe'" -Property ExecutablePath,CommandLine -ErrorAction Stop
    } catch { return }
    foreach ($p in $processes) {
        try { Get-ForgeProcessMatch $p } catch { continue }
    }
}

function Read-ForgeLauncherConfig([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    try {
        $record = [IO.File]::ReadAllText($Path) | ConvertFrom-Json
        $root = Resolve-ForgeFolder $record.root
        if ($root) {
            $port = [int]$record.port
            if ($port -lt 1 -or $port -gt 65535) { $port=7860 }
            return [pscustomobject]@{root=$root; python=(Resolve-ForgePython $record.python); port=$port}
        }
    } catch { return }
}

function Save-ForgeLauncherConfig([string]$Path, $Record) {
    $dir=[IO.Path]::GetDirectoryName($Path)
    [void][IO.Directory]::CreateDirectory($dir)
    $temp=Join-Path $dir ('launcher-'+[guid]::NewGuid().ToString('N')+'.tmp')
    try {
        [IO.File]::WriteAllText($temp,($Record | ConvertTo-Json -Compress),(New-Object Text.UTF8Encoding($false)))
        if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temp,$Path,[System.Management.Automation.Language.NullString]::Value) }
        else {
            try { [IO.File]::Move($temp,$Path) }
            catch [IO.IOException] {
                if (![IO.File]::Exists($Path)) { throw }
                [IO.File]::Replace($temp,$Path,[System.Management.Automation.Language.NullString]::Value)
            }
        }
    } finally { if ([IO.File]::Exists($temp)) { [IO.File]::Delete($temp) } }
}

function Select-ForgeFolder {
    Add-Type -AssemblyName System.Windows.Forms
    $picker = New-Object Windows.Forms.FolderBrowserDialog
    $picker.Description = '普段使っているForgeのフォルダーを選んでください。launch.py が入ったフォルダー、またはその1つ上のフォルダーを選べます。'
    $picker.ShowNewFolderButton = $false
    try {
        while ($picker.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) {
            $selected=Resolve-ForgeFolder $picker.SelectedPath
            if ($selected) { return $selected }
            $children=@(Get-ChildItem -LiteralPath $picker.SelectedPath -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Resolve-ForgeFolder $_.FullName })
            if ($children.Count -eq 1) { return $children[0] }
            [void][Windows.Forms.MessageBox]::Show('launch.py と modules フォルダーが入っているForge本体のフォルダーを選んでください。','Forge Remote E')
        }
    } finally { $picker.Dispose() }
}

function Select-ForgePython([string]$Root) {
    Add-Type -AssemblyName System.Windows.Forms
    $picker=New-Object Windows.Forms.OpenFileDialog
    $picker.Title='Forgeが使用する python.exe を選択してください'
    $picker.Filter='ForgeのPython (python.exe)|python.exe'
    $picker.InitialDirectory=$Root
    try {
        if ($picker.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) {
            return Resolve-ForgePython $picker.FileName
        }
    } finally { $picker.Dispose() }
}

function Get-ForgeLauncherSettings([string]$ForgeRoot, [string]$Python, [int]$ForgePort) {
    $config=Join-Path $env:LOCALAPPDATA 'ForgeRemoteE\launcher.json'
    $saved=Read-ForgeLauncherConfig $config
    if ($ForgeRoot) {
        $root=Resolve-ForgeFolder $ForgeRoot
        if (!$root) { throw '選択したフォルダーにForge本体が見つかりません。' }
    } elseif ($saved) { $root=$saved.root }
    else { $root=Find-ForgeAncestor $PSScriptRoot }

    $live=@(Get-LiveForge)
    if (!$root) {
        $found=@($live | ForEach-Object { $_.root } | Sort-Object -Unique)
        if ($found.Count -eq 1) { $root=$found[0] }
        else { $root=Select-ForgeFolder }
        if (!$root) { return }
    }
    $matched=@($live | Where-Object { $_.root -eq $root })
    $interpreter=$null
    if ($Python) {
        $interpreter=Resolve-ForgePython $Python
        if (!$interpreter) { throw '指定した python.exe が見つかりません。' }
    } elseif ($saved -and $saved.root -eq $root) { $interpreter=$saved.python }
    if (!$interpreter -and $matched.Count -gt 0) { $interpreter=$matched[0].python }
    if (!$interpreter) {
        foreach ($sub in 'venv\Scripts\python.exe','.venv\Scripts\python.exe') {
            $interpreter=Resolve-ForgePython (Join-Path $root $sub)
            if ($interpreter) { break }
        }
    }
    if (!$interpreter) {
        Write-Host 'ForgeのPythonを選択してください。分からない場合は普段のForgeを起動してから、もう一度このファイルを開いてください。'
        $interpreter=Select-ForgePython $root
        if (!$interpreter) { return }
    }
    $port=7860
    if ($ForgePort) { $port=$ForgePort }
    elseif ($matched.Count -gt 0) { $port=$matched[0].port }
    elseif ($saved -and $saved.root -eq $root) { $port=$saved.port }
    $record=[pscustomobject]@{root=$root;python=$interpreter;port=$port}
    Save-ForgeLauncherConfig $config $record
    return $record
}

function Open-ForgeRemote {
    $record=Get-ForgeLauncherSettings $ForgeRoot $Python $ForgePort
    if (!$record) { return }
    Write-Host 'Forge Remote E（Everyone）'
    Write-Host 'ブラウザーにQRコードを表示します。スマートフォンのカメラで読み取り、Forge Remote Eで開いてください。'
    Write-Host '使っている間は最初のウィンドウを開いたままにしてください。別のスマートフォンを登録するときは、同じファイルをもう一度ダブルクリックします。'
    & (Join-Path $PSScriptRoot 'Start-Network.ps1') -ForgeRoot $record.root -Python $record.python -ForgePort $record.port -Pair
    if ($LASTEXITCODE -ne 0) { throw '接続を開始できませんでした。上に表示されたメッセージを確認してください。' }
}

if ($MyInvocation.InvocationName -ne '.') {
    try { Open-ForgeRemote }
    catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }
}
