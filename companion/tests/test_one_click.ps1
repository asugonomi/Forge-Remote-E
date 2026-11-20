# Run under Windows PowerShell 5.1 or newer. Both the interpreter and the gateway are fixtures.
$ErrorActionPreference='Stop'
$source=Split-Path -Parent $PSScriptRoot
$base=Join-Path ([IO.Path]::GetTempPath()) ('forge-e-launcher-test-'+[guid]::NewGuid().ToString('N'))
$root=Join-Path $base 'テスト Forge [demo] 100%! & setup'
$copy=Join-Path $root 'tools\remote'
$other=Join-Path $base 'other interpreter'
$localBefore=$env:LOCALAPPDATA
$fixtureBefore=$env:FORGE_E_LAUNCHER_FIXTURE
$first=$null
try {
    foreach($path in @($copy,$other,(Join-Path $root 'modules'),(Join-Path $root 'modules_forge'),
        (Join-Path $root 'venv\Scripts'))) { [void][IO.Directory]::CreateDirectory($path) }
    $env:LOCALAPPDATA=Join-Path $base 'user'
    $env:FORGE_E_LAUNCHER_FIXTURE=$base
    $launch=Join-Path $root 'launch.py'
    $venv=Join-Path $root 'venv\Scripts\python.exe'
    $custom=Join-Path $other 'python.exe'
    foreach($path in @($launch,$venv,$custom)){[IO.File]::WriteAllBytes($path,@())}
    $tokens=$null; $errors=$null
    $helper=Join-Path $source 'Open-ForgeRemote.ps1'
    [void][System.Management.Automation.Language.Parser]::ParseFile($helper,[ref]$tokens,[ref]$errors)
    if($errors){throw 'Windows PowerShell syntax error in the launcher'}
    . $helper
    if((Find-ForgeAncestor $copy) -ne $root){throw 'Ancestor root not detected'}
    if(Resolve-ForgeFolder $other){throw 'Unrelated folder accepted'}
    $proc=[pscustomobject]@{ExecutablePath=$venv;CommandLine="""" + $venv + """ -u launch.py --port 8672"}
    $detected=Get-ForgeProcessMatch $proc
    if($detected.root -ne $root -or $detected.port -ne 8672){throw 'Venv-relative running Forge not detected'}
    $proc.ExecutablePath=$custom
    $proc.CommandLine='"'+$custom+'" "'+$launch+'" --port=8701'
    $detected=Get-ForgeProcessMatch $proc
    if($detected.root -ne $root -or $detected.python -ne $custom -or $detected.port -ne 8701){throw 'Explicit running interpreter not detected'}
    $proc.CommandLine='"'+$custom+'" -c "print(123)" "'+$launch+'"'
    if(Get-ForgeProcessMatch $proc){throw 'Unrelated Python accepted'}
    $proc.CommandLine='"'+$custom+'" relative\launch.py'
    if(Get-ForgeProcessMatch $proc){throw 'Unresolved working directory accepted'}
    $standaloneConfig=Join-Path $base 'prefs\test.json'
    $record=[pscustomobject]@{root=$root;python=$venv;port=7860}
    Save-ForgeLauncherConfig $standaloneConfig $record
    $record.python=$custom
    Save-ForgeLauncherConfig $standaloneConfig $record
    if((Read-ForgeLauncherConfig $standaloneConfig).python -ne $custom){throw 'Saved preference update failed'}
    [IO.File]::WriteAllText($standaloneConfig,'{}')
    if(Read-ForgeLauncherConfig $standaloneConfig){throw 'Invalid saved folder accepted'}

    $cmd='Forge Remote E.cmd'
    foreach($name in @($cmd,'Open-ForgeRemote.ps1')){
        [IO.File]::Copy((Join-Path $source $name),(Join-Path $copy $name))
    }
    # The second invocation must return while the first gateway process remains alive.
    $stub=@'
param($ForgeRoot,$Python,[int]$ForgePort,[switch]$Pair)
$dir=$env:FORGE_E_LAUNCHER_FIXTURE
if(!$Pair -or $ForgePort -ne 7860 -or ![IO.File]::Exists($Python)){exit 92}
$first=Join-Path $dir 'first'
if(![IO.File]::Exists($first)){
    [IO.File]::WriteAllText($first,$ForgeRoot)
    $deadline=[DateTime]::UtcNow.AddSeconds(35)
    while(![IO.File]::Exists((Join-Path $dir 'finish')) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 150}
    if(![IO.File]::Exists((Join-Path $dir 'finish'))){exit 93}
} else {
    [IO.File]::WriteAllText((Join-Path $dir 'second'),$ForgeRoot)
}
exit 0
'@
    [IO.File]::WriteAllText((Join-Path $copy 'Start-Network.ps1'),$stub)
    # A fixed command with a quoted batch filename; all variable path data goes through WorkingDirectory.
    $start=New-Object Diagnostics.ProcessStartInfo
    $start.FileName=Join-Path $env:SystemRoot 'System32\cmd.exe'
    $start.Arguments='/d /c ""Forge Remote E.cmd""'
    $start.WorkingDirectory=$copy
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $first=[Diagnostics.Process]::Start($start)
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    while(![IO.File]::Exists((Join-Path $base 'first')) -and [DateTime]::UtcNow -lt $deadline -and !$first.HasExited){Start-Sleep -Milliseconds 150}
    if(![IO.File]::Exists((Join-Path $base 'first'))){throw 'Initial one-click launch failed'}
    $next=[Diagnostics.Process]::Start($start)
    if(!$next.WaitForExit(15000) -or $next.ExitCode -ne 0 -or $first.HasExited){throw 'Second launch did not reuse the running gateway'}
    if([IO.File]::ReadAllText((Join-Path $base 'second')) -ne $root){throw 'Quoted path forwarding failed'}
    $remembered=Read-ForgeLauncherConfig (Join-Path $env:LOCALAPPDATA 'ForgeRemoteE\launcher.json')
    if($remembered.root -ne $root -or $remembered.python -ne $venv){throw 'First-run preferences not retained'}
    [IO.File]::WriteAllText((Join-Path $base 'finish'),'ok')
    if(!$first.WaitForExit(5000) -or $first.ExitCode -ne 0){throw 'First gateway was interrupted'}
    Write-Output 'PASS one-click launch and re-open, saved preference update, Unicode paths and custom Forge Python detection.'
} finally {
    if([IO.Directory]::Exists($base)){[IO.File]::WriteAllText((Join-Path $base 'finish'),'done')}
    if($first){[void]$first.WaitForExit(5000)}
    $env:LOCALAPPDATA=$localBefore
    $env:FORGE_E_LAUNCHER_FIXTURE=$fixtureBefore
    $target=[IO.Path]::GetFullPath($base)
    $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if([IO.Path]::GetDirectoryName($target) -eq $parent -and
        [IO.Path]::GetFileName($target) -match '^forge-e-launcher-test-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
