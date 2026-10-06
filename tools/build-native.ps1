param([string]$Sdk='D:\SapnapBoy\gpt\android-sdk')
$ErrorActionPreference='Stop'
$compiler=Join-Path $Sdk 'ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$projectRoot=Split-Path -Parent $PSScriptRoot
$output=Join-Path $projectRoot 'app\src\main\assets\lightcore'
$sysroot=Join-Path (Split-Path (Split-Path $compiler)) 'sysroot'
$compileArgs=@('--target=aarch64-linux-android31',"--sysroot=$sysroot",'-Os','-fPIE','-pie','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-Wl,--build-id=none','-Wl,-z,max-page-size=16384','-s','-Wall','-Wextra',(Join-Path $projectRoot 'native\lightcore.c'),'-o',$output)
& $compiler @compileArgs
if($LASTEXITCODE -ne 0){throw 'Native worker compilation failed'}
