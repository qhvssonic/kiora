param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
# Official pretrained weights are for non-commercial research only.
$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
$models = Join-Path $destination 'buffalo_sc'
if (Test-Path -LiteralPath $models) { throw "目标已存在，请使用新的输出目录：$models" }
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$archive = Join-Path $destination 'buffalo_sc.zip'
Invoke-WebRequest -Uri 'https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_sc.zip' -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath $models
$expected = @{
    'det_500m.onnx' = '5e4447f50245bbd7966bd6c0fa52938c61474a04ec7def48753668a9d8b4ea3a'
    'w600k_mbf.onnx' = '9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f'
}
foreach ($name in $expected.Keys) {
    $actual = (Get-FileHash -LiteralPath (Join-Path $models $name) -Algorithm SHA256).Hash
    if ($actual -ne $expected[$name]) { throw "模型校验失败：$name" }
}
Write-Host "已验证模型：$models"
Write-Host '权重仅供非商业研究；在 Companion 中选择上面的整个 buffalo_sc 目录。'
