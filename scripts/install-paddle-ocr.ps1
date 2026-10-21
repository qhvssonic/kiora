param(
    [string]$Aar = (Join-Path $PSScriptRoot '..\..\artifacts\PaddleOCR\deploy\ppocr-android\ppocr-sdk\build\outputs\aar\ppocr-sdk-release.aar'),
    [string]$Repository = (Join-Path $PSScriptRoot '..\..\artifacts\maven')
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $Aar).Path
$entries = @(tar -tf $source)
foreach ($entry in @('assets/models/det/inference.onnx', 'assets/models/rec/inference.onnx',
        'assets/models/rec/inference.yml')) {
    if ($entries -notcontains $entry) { throw "PaddleOCR AAR is missing $entry" }
}

# This is a local coordinate for the official PaddleOCR Android source at dab3fe3.
# PaddlePaddle does not publish this AAR under this coordinate.
$version = '0.1.0-dab3fe3'
$artifactDir = Join-Path $Repository "com\paddle\ocr\ppocr-sdk-android\$version"
$target = Join-Path $artifactDir "ppocr-sdk-android-$version.aar"
$pom = Join-Path $artifactDir "ppocr-sdk-android-$version.pom"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
if (Test-Path -LiteralPath $target) {
    $existing = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    $incoming = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ($existing -ne $incoming) { throw "A different PaddleOCR AAR already exists at $target" }
} else {
    Copy-Item -LiteralPath $source -Destination $target
}

$pomText = @"
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>com.paddle.ocr</groupId><artifactId>ppocr-sdk-android</artifactId><version>$version</version><packaging>aar</packaging><dependencies>
<dependency><groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime-android</artifactId><version>1.21.1</version></dependency>
<dependency><groupId>org.opencv</groupId><artifactId>opencv</artifactId><version>4.12.0</version></dependency>
<dependency><groupId>org.jetbrains.kotlinx</groupId><artifactId>kotlinx-coroutines-android</artifactId><version>1.9.0</version></dependency>
<dependency><groupId>androidx.core</groupId><artifactId>core-ktx</artifactId><version>1.15.0</version></dependency>
</dependencies></project>
"@
if (Test-Path -LiteralPath $pom) {
    if ((Get-Content -LiteralPath $pom -Raw).Trim() -ne $pomText.Trim()) {
        throw "A different PaddleOCR POM already exists at $pom"
    }
} else {
    $pomText | Set-Content -LiteralPath $pom -Encoding utf8
}
Write-Output "Installed PaddleOCR Android SDK $version into $Repository"
