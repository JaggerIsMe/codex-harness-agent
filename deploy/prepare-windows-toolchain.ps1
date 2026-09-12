param(
    [Parameter(Mandatory=$true)][ValidateSet('Node','Java','Maven')][string]$Kind,
    [Parameter(Mandatory=$true)][string]$SourceHome,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference='Stop'
$sourceRoot=(Resolve-Path -LiteralPath $SourceHome).Path
$targetRoot=[IO.Path]::GetFullPath($Destination)
if(Test-Path -LiteralPath $targetRoot){throw 'Destination must be a new dedicated runtime directory.'}
if($targetRoot.StartsWith($sourceRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Destination must be outside the source installation.'}
if($targetRoot -eq [IO.Path]::GetPathRoot($targetRoot)){throw 'A volume root cannot be used as a runtime directory.'}
$required=switch($Kind){'Node' {'node.exe'} 'Java' {'bin/java.exe'} 'Maven' {'bin/mvn.cmd'}}
if(-not(Test-Path -LiteralPath (Join-Path $sourceRoot $required) -PathType Leaf)){throw "Source does not contain $required"}
New-Item -ItemType Directory -Path $targetRoot | Out-Null
switch($Kind) {
    'Node' {
        Copy-Item -LiteralPath (Join-Path $sourceRoot 'node.exe') -Destination $targetRoot
        foreach($name in @('npm.cmd','npx.cmd')) {
            if(Test-Path -LiteralPath (Join-Path $sourceRoot $name)){Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination $targetRoot}
        }
        if(Test-Path -LiteralPath (Join-Path $sourceRoot 'node_modules/npm')) {
            New-Item -ItemType Directory -Path (Join-Path $targetRoot 'node_modules') | Out-Null
            Copy-Item -LiteralPath (Join-Path $sourceRoot 'node_modules/npm') -Destination (Join-Path $targetRoot 'node_modules') -Recurse
        }
    }
    'Java' {
        foreach($name in @('bin','lib','conf','release')){Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination $targetRoot -Recurse}
    }
    'Maven' {
        foreach($name in @('bin','boot','lib')){Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination $targetRoot -Recurse}
        New-Item -ItemType Directory -Path (Join-Path $targetRoot 'conf') | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $targetRoot 'conf/logging') | Out-Null
        Set-Content -LiteralPath (Join-Path $targetRoot 'conf/settings.xml') -Value '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" />' -Encoding UTF8
    }
}
if($Kind -eq 'Maven') {
    Write-Warning 'Maven 3.9.16 currently fails the Windows LPAC canonical-path check. This copy is for compatibility diagnostics; do not advertise Maven as supported.'
} else {
    Write-Output "Dedicated $Kind runtime prepared at $targetRoot. Configure harness.agent.windows-tools with this home and the relative executable."
}
