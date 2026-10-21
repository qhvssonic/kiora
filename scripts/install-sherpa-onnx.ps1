param(
    [string]$Repository = (Join-Path $PSScriptRoot '..\..\artifacts\maven')
)

$ErrorActionPreference = 'Stop'
$version = '1.13.8'
$expectedSha256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
$artifactDir = Join-Path $Repository 'com\k2fsa\sherpa\onnx\sherpa-onnx-android\1.13.8'
$aar = Join-Path $artifactDir "sherpa-onnx-android-$version.aar"
$pending = "$aar.pending"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
try {
    if (-not (Test-Path -LiteralPath $aar)) {
        Invoke-WebRequest -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar" -OutFile $pending -UseBasicParsing
        $actual = (Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expectedSha256) { throw "sherpa-onnx SHA-256 mismatch: $actual" }
        Move-Item -LiteralPath $pending -Destination $aar
    }
    $actual = (Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expectedSha256) { throw "Existing sherpa-onnx AAR SHA-256 mismatch: $actual" }
    @"
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>com.k2fsa.sherpa.onnx</groupId><artifactId>sherpa-onnx-android</artifactId><version>$version</version><packaging>aar</packaging></project>
"@ | Set-Content -LiteralPath (Join-Path $artifactDir "sherpa-onnx-android-$version.pom") -Encoding utf8
    Write-Output "Verified sherpa-onnx $version in $Repository"
} finally {
    if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending }
}
