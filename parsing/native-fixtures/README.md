# Native Parser Fixtures

This directory contains reproducible source inputs and checked-in native binary parser fixtures.

Fixture binaries live in `parsing/native-fixtures/test-data/` and are committed so parser tests can run without regenerating native binaries on every machine. Use the generator only when intentionally refreshing the fixture set.

## Docker Build

Run from the repository root:

```bash
docker build -t hexana-native-fixtures:build parsing/native-fixtures
mkdir -p parsing/native-fixtures/test-data
docker run --rm \
  -v "$PWD/parsing/native-fixtures":/src:ro \
  -v "$PWD/parsing/native-fixtures/test-data":/out \
  hexana-native-fixtures:build
```

Expected outputs include:

- `test-data/elf/sample.o`
- `test-data/elf/sample.elf`
- `test-data/elf/libsample.so`
- `test-data/elf/sample_cpp.o`
- `test-data/macho/sample-x86_64.o`
- `test-data/macho/sample-arm64.o`
- `test-data/macho/sample_cpp-x86_64.o`
- `test-data/pe/sample.coff`
- `test-data/pe/sample.exe`
- `test-data/pe/sample.lib`
- `test-data/pe/sample.pdb`
- `test-data/pe/sample_cpp.coff`
- `test-data/pe/sample-clr.exe` (see below; not built by the Docker image)
- `test-data/manifest.txt`

When LLVM inspection tools are available, the build also writes `*.llvm-readobj.txt` reference dumps next to the generated binaries.

## Local Build

The same script can be run locally:

```bash
parsing/native-fixtures/build-native-fixtures.sh
```

Set `SRC_DIR` and `OUT_DIR` to override source and output directories.

## .NET Fixture

`test-data/pe/sample-clr.exe` is a managed (.NET Framework, IL-only, AnyCPU) assembly built from
`src/clr/Sample.cs`, embedding `src/clr/hello.txt` and the `.resources` fixture below. The Docker image
has no C# compiler, so build it with Roslyn `csc` (for example from Mono) when it needs refreshing:

```bash
cd parsing/native-fixtures/src/clr
cp ../../test-data/pe/sample.resources strings.resources
csc -nologo -deterministic -optimize+ -debug- \
  -resource:hello.txt,Hexana.Sample.hello.txt \
  -resource:strings.resources,Hexana.Sample.Strings.resources \
  -out:../../test-data/pe/sample-clr.exe Sample.cs
rm strings.resources
```

The parser tests cross-check it against `monodis --typedef`, `--method`, `--memberref` and `--manifest` output,
and the IL tests against `ikdasm sample-clr.exe`. Update `ClrFixtureParserTest`, `ClrIlDecoderTest`,
`ClrTableTabsTest` and `ClrIlTextTest` together with the fixture, and regenerate the base64 copy in
`parsing/pe/src/commonTest/.../clr/ClrSampleFixture.kt` that the JS and Wasm tests use.

### .resources fixture

`test-data/pe/sample.resources` is a resource set with one value of every `ResourceTypeCode` plus a
serialized user type, written by `src/clr/resources/MakeResources.cs` through `ResourceWriter`. Mono is
used because .NET 8+ refuses to serialize user types. `sample.resources.dump.txt` is what the runtime's
own `ResourceReader` reports for it (`DumpResources.cs`), and is what `ClrResourcesParserTest` checks:

```bash
cd parsing/native-fixtures/src/clr/resources
csc -nologo -out:/tmp/MakeResources.exe MakeResources.cs
csc -nologo -r:/tmp/MakeResources.exe -out:/tmp/DumpResources.exe DumpResources.cs
mono /tmp/MakeResources.exe ../../../test-data/pe/sample.resources
mono /tmp/DumpResources.exe ../../../test-data/pe/sample.resources | sort > ../../../test-data/pe/sample.resources.dump.txt
```

### ReadyToRun fixtures

`test-data/pe/sample-r2r*.dll` are ReadyToRun (crossgen2) images of `src/clr/r2r/Sample.cs` for four
targets, each next to the perfmap crossgen2 wrote for it (`*.perfmap.txt`: RVA, length and name of every
compiled method), which `ClrReadyToRunTest` compares against:

| Fixture | Target | Function lengths come from |
|---|---|---|
| `sample-r2r.dll` | macOS ARM64 | `.xdata` unwind records |
| `sample-r2r-win-x64.dll` | Windows x64 | the RuntimeFunctions table |
| `sample-r2r-win-x86.dll` | Windows x86 | the GC info header |
| `sample-r2r-linux-arm.dll` | Linux ARM (Thumb) | `.xdata` unwind records |

Rebuild them with `src/clr/r2r/build-r2r-fixtures.sh` (needs the .NET SDK and the 8.0.1
`microsoft.netcore.app.crossgen2.osx-arm64` / `microsoft.netcore.app.runtime.osx-arm64` packages in the
NuGet cache). The output is deterministic. To see what crossgen2 placed where, add `--map --mapcsv` to its
command line; `ClrSizeAnalysisTest` quotes sizes from that map.
