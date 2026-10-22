$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force deps | Out-Null
$items=Get-Content (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
foreach($item in $items) {
    $target=Join-Path 'deps' $item.file
    if(!(Test-Path -LiteralPath $target)) {Invoke-WebRequest -Uri $item.url -OutFile $target}
    if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw "Dependency checksum mismatch: $($item.file)"}
    Write-Output "Verified $($item.file)"
}
