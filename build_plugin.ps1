# Forwards to scripts/build_plugin.sh so there is only one implementation.
#
# Kept because a Windows shell is where you land by default, but it holds no
# logic of its own: the build needs the MSYS2 MINGW64 toolchain and an MSYS
# makefile, neither of which PowerShell can drive, so it hands straight over.
#
# Maven and the JVM both write warnings to stderr, and under Stop that surfaces
# as a terminating NativeCommandError before the real exit status is ever read.
# The script's exit code is the verdict, so failures are left to propagate.
$ErrorActionPreference = "Continue"

$bash = @("C:\msys64\usr\bin\bash.exe", "C:\msys2\usr\bin\bash.exe") |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1

if (-not $bash) {
    throw "MSYS2 bash was not found. Build from a MINGW64 shell with scripts/build_plugin.sh instead."
}

$root = $PSScriptRoot -replace '\\', '/'

& $bash -lc "cd '$root' && ./scripts/build_plugin.sh $args"
exit $LASTEXITCODE