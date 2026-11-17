#!/usr/bin/env bash
# Rebuilds the ReadyToRun fixtures in test-data/pe from Sample.cs: one IL build, then crossgen2
# per target. Needs the .NET SDK plus the microsoft.netcore.app.crossgen2.osx-arm64 and
# microsoft.netcore.app.runtime.osx-arm64 8.0.1 packages in the NuGet cache (any restore of an
# osx-arm64 PublishReadyToRun project fetches them). The perfmap next to each image lists every
# compiled method's RVA and length, and is what the parser tests compare against.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../test-data/pe"
packages="${NUGET_PACKAGES:-$HOME/.nuget/packages}"
crossgen="$packages/microsoft.netcore.app.crossgen2.osx-arm64/8.0.1/tools/crossgen2"
framework="$packages/microsoft.netcore.app.runtime.osx-arm64/8.0.1/runtimes/osx-arm64/lib/net8.0"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Build from a copy so obj/ and bin/ never land in the source tree.
mkdir -p "$work/src"
cp "$here/R2rSample.csproj" "$here/Sample.cs" "$work/src/"
dotnet build "$work/src/R2rSample.csproj" -c Release --nologo -v q -o "$work/il"

build() { # <targetos> <targetarch> <fixture name>
  local dir="$work/$1-$2"
  mkdir -p "$dir"
  "$crossgen" "$work/il/R2rSample.dll" -o "$dir/R2rSample.dll" -r "$framework/*.dll" \
    --targetos:"$1" --targetarch:"$2" -O --perfmap --perfmap-path:"$dir"
  cp "$dir/R2rSample.dll" "$out/$3.dll"
  cp "$dir"/R2rSample.ni.*.map "$out/$3.perfmap.txt"
}

build osx arm64 sample-r2r
build windows x64 sample-r2r-win-x64
build windows x86 sample-r2r-win-x86
build linux arm sample-r2r-linux-arm
