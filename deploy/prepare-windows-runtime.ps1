param(
    [Parameter(Mandatory=$true)][string]$SourcePythonHome,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $SourcePythonHome).Path
$targetRoot = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $targetRoot) { throw 'Destination must be a new dedicated runtime directory.' }
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot 'python.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $sourceRoot 'Lib/encodings'))) {
    throw 'Source must be a full CPython installation with python.exe and Lib/encodings.'
}
if ($targetRoot.StartsWith($sourceRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Destination must be outside the source installation.'
}
New-Item -ItemType Directory -Path $targetRoot | Out-Null
Get-ChildItem -LiteralPath $sourceRoot -File | Where-Object {
    $_.Name -eq 'python.exe' -or $_.Name -match '^python3\d*\.dll$' -or $_.Name -match '^vcruntime.*\.dll$'
} | Copy-Item -Destination $targetRoot
Copy-Item -LiteralPath (Join-Path $sourceRoot 'DLLs') -Destination $targetRoot -Recurse
$libraryTarget = Join-Path $targetRoot 'Lib'
New-Item -ItemType Directory -Path $libraryTarget | Out-Null
Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'Lib') | Where-Object {
    $_.Name -ne 'site-packages' -and $_.Name -ne '__pycache__'
} | Copy-Item -Destination $libraryTarget -Recurse
Write-Output "Set harness.agent.windows-python to $(Join-Path $targetRoot 'python.exe')"
