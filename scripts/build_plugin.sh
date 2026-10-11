#!/usr/bin/env bash
#
# Builds the Paper plugin end to end: the native FFM shim, its staged jar
# resource, and the jar itself. One entry point, so there is nothing to remember
# about which half of the build is C and which half is Java.
#
#     ./scripts/build_plugin.sh              # same as `package`
#     ./scripts/build_plugin.sh test         # any Maven goals you like
#
# `build_plugin.ps1` only forwards to this script, so the two cannot drift.
#
# The C half is GNU make over an MSYS2 makefile with MSYS paths, so this is a
# bash script on purpose and has to run in a MINGW64 shell.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugin="$root/java/plugin"

if [[ "${MSYSTEM:-}" != "MINGW64" ]]; then
    echo "error: build this from an MSYS2 MINGW64 shell (the blue one)." >&2
    echo "       MSYSTEM is '${MSYSTEM:-unset}', so make would use the wrong toolchain." >&2
    exit 1
fi

# Echoes the first usable JDK, or nothing. Version 25 is the floor: Paper
# 26.1.2 ships Java 25 bytecode and FFM is only final there.
find_jdk() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
        echo "$JAVA_HOME"
        return
    fi
    # `sort -Vr` puts 25.0.2 ahead of 25, so the newest wins rather than
    # whichever the glob happened to expand first.
    local candidate
    while IFS= read -r candidate; do
        if [[ -x "$candidate/bin/javac" ]]; then
            echo "$candidate"
            return
        fi
    done < <(
        { ls -d /c/Program\ Files/Java/jdk-25* /c/Program\ Files/Eclipse\ Adoptium/jdk-25* 2>/dev/null || true; } | sort -Vr
    )
}

# Echoes a Maven launcher to run, or nothing. The Windows zip ships both
# bin/mvn, a POSIX script, and bin/mvn.cmd; the former is preferred because it
# needs no cmd.exe round trip and no argument path mangling.
find_maven() {
    local home candidate
    for home in "${MAVEN_HOME:-}" "${M2_HOME:-}"; do
        if [[ -n "$home" && -x "$home/bin/mvn" ]]; then
            echo "$home/bin/mvn"
            return
        fi
    done
    if command -v mvn >/dev/null 2>&1; then
        command -v mvn
        return
    fi
    while IFS= read -r candidate; do
        if [[ -x "$candidate" ]]; then
            echo "$candidate"
            return
        fi
    done < <(
        {
            ls -d "$root"/../maven/apache-maven-*/bin/mvn \
                "$HOME"/maven/apache-maven-*/bin/mvn \
                /c/maven/apache-maven-*/bin/mvn \
                /c/tools/apache-maven-*/bin/mvn 2>/dev/null || true
        } | sort -Vr
    )
    while IFS= read -r candidate; do
        if [[ -f "$candidate" ]]; then
            echo "$candidate"
            return
        fi
    done < <(
        {
            ls -d "$root"/../maven/apache-maven-*/bin/mvn.cmd \
                "$HOME"/maven/apache-maven-*/bin/mvn.cmd \
                /c/maven/apache-maven-*/bin/mvn.cmd \
                /c/tools/apache-maven-*/bin/mvn.cmd 2>/dev/null || true
        } | sort -Vr
    )
}

jdk="$(find_jdk)"
if [[ -z "$jdk" ]]; then
    echo "error: no Java 25 JDK found. Set JAVA_HOME to one." >&2
    exit 1
fi
export JAVA_HOME="$jdk"

maven="$(find_maven)"
if [[ -z "$maven" ]]; then
    echo "error: no Maven found. Set MAVEN_HOME, or put mvn on PATH." >&2
    exit 1
fi

# A .cmd launcher cannot be exec'd directly out of MSYS, so it goes through the
# command interpreter. `//c` is doubled so MSYS does not read the /c as a path.
run_maven() {
    if [[ "$maven" == *.cmd || "$maven" == *.bat ]]; then
        cmd //c "$maven" "$@"
    else
        "$maven" "$@"
    fi
}

goals=("$@")
if [[ ${#goals[@]} -eq 0 ]]; then
    goals=(package)
fi

echo "==> JDK: $("$jdk/bin/javac" -version 2>&1)"
echo "==> Maven: $maven"

echo "==> building native shim"
make -C "$root" abi

# Name the library the way the plugin expects to find it, which encodes OS and
# architecture so a wrong-arch jar fails with a clear message instead of
# crashing the server.
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) suffix=".dll"; os_tag="windows" ;;
    Darwin*)                      suffix=".dylib"; os_tag="macos" ;;
    *)                           suffix=".so"; os_tag="linux" ;;
esac

case "$(uname -m)" in
    x86_64 | amd64) arch_tag="x64" ;;
    aarch64 | arm64) arch_tag="aarch64" ;;
    *) arch_tag="$(uname -m | tr -cd 'a-z0-9')" ;;
esac

prefix=""
if [[ "$suffix" == ".so" ]]; then
    prefix="lib"
fi

shopt -s nullglob
# `suffix` already carries its dot. The existence check is explicit rather than
# a length test because nullglob only eats patterns, and with an empty suffix
# this would be a plain path that never matches.
built=("$root"/build/"$prefix"griefprot_ffi"$suffix")
if [[ ${#built[@]} -eq 0 || ! -f "${built[0]}" ]]; then
    echo "error: make abi produced no ${prefix}griefprot_ffi${suffix} in $root/build" >&2
    exit 1
fi

stage_dir="$plugin/src/main/resources/native"
mkdir -p "$stage_dir"
target="$stage_dir/griefprot_${os_tag}_${arch_tag}${suffix}"
cp "${built[0]}" "$target"
echo "==> staged $(basename "${built[0]}") as ${target#"$plugin"/}"

echo "==> building plugin (${goals[*]})"
cd "$plugin"
if [[ -x ./mvnw ]]; then
    ./mvnw clean "${goals[@]}"
else
    run_maven clean "${goals[@]}"
fi

shopt -s nullglob
jars=("$plugin"/target/*.jar)
for jar in "${jars[@]}"; do
    case "$(basename "$jar")" in
        original-* | *-sources.jar | *-javadoc.jar) continue ;;
    esac
    echo "==> built ${jar#"$root"/}"
done