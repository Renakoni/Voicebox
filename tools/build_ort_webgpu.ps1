param(
    [string]$SourceDir = "$PSScriptRoot/../../ref/onnxruntime-webgpu",
    [string]$BuildDir = "$PSScriptRoot/../../artifacts/ort-webgpu/build",
    [string]$BuildEnv = "$PSScriptRoot/../../assets/ort-build-env",
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$AndroidNdk = "$AndroidSdk/ndk/28.2.13676358",
    [string]$Protoc = "$PSScriptRoot/../../assets/downloads/ort-build/protoc-21.12-win64/bin/protoc.exe",
    [switch]$Update
)

# ORT v1.27.0, arm64 Android API 30. The Java/JNI binding is reused from
# the matching official AAR; this builds its C API runtime replacement.
$ErrorActionPreference = 'Stop'
# The reverse check returns nonzero on a fresh checkout. Handle native exit codes below.
$PSNativeCommandUseErrorActionPreference = $false
$SourceDir = (Resolve-Path -LiteralPath $SourceDir).Path
$BuildEnv = (Resolve-Path -LiteralPath $BuildEnv).Path
$Protoc = (Resolve-Path -LiteralPath $Protoc).Path
$BuildDir = [IO.Path]::GetFullPath($BuildDir)
$adrenoPatch = (Resolve-Path -LiteralPath "$PSScriptRoot/../Android/model/gptsovits/patches/onnxruntime-1.27-webgpu-adreno660.patch").Path
& git -C $SourceDir apply --quiet --reverse --check $adrenoPatch
if ($LASTEXITCODE -ne 0) {
    & git -C $SourceDir apply $adrenoPatch
    if ($LASTEXITCODE -ne 0) { throw "Could not apply the Adreno 660 WebGPU fix to $SourceDir." }
}
. "$BuildEnv/Scripts/Activate.ps1"
$env:PYTHONIOENCODING = 'utf-8'
$buildArgs = @(
    'tools/ci_build/build.py', '--config', 'Release', '--build',
    '--skip_tests', '--skip_submodule_sync', '--cmake_generator', 'Ninja',
    '--parallel', '2', '--android', '--android_abi', 'arm64-v8a',
    '--android_api', '30', '--android_sdk_path', $AndroidSdk,
    '--android_ndk_path', $AndroidNdk, '--use_webgpu', '--build_shared_lib',
    '--use_xnnpack', '--use_nnapi', '--path_to_protoc_exe', $Protoc,
    '--cmake_path', "$BuildEnv/Scripts/cmake.exe", '--build_dir', $BuildDir,
    '--target', 'onnxruntime', '--cmake_extra_defines', 'onnxruntime_BUILD_UNIT_TESTS=OFF'
)
if ($Update) { $buildArgs += '--update' }
Push-Location -LiteralPath $SourceDir
try {
    & "$BuildEnv/Scripts/python.exe" @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "ORT build failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
