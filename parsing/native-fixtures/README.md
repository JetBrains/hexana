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
`src/clr/Sample.cs` with an embedded resource from `src/clr/hello.txt`. The Docker image has no C#
compiler, so build it with Roslyn `csc` (for example from Mono) when it needs refreshing:

```bash
cd parsing/native-fixtures/src/clr
csc -nologo -deterministic -optimize+ -debug- \
  -resource:hello.txt,Hexana.Sample.hello.txt \
  -out:../../test-data/pe/sample-clr.exe Sample.cs
```

The parser tests cross-check it against `monodis --typedef`, `--method`, `--memberref` and `--manifest` output,
and the IL tests against `ikdasm sample-clr.exe`.

`test-data/pe/sample-r2r.dll` is a ReadyToRun (crossgen) image for macOS ARM64, built from
`src/clr/r2r/` with `dotnet publish -c Release` (needs the `microsoft.netcore.app.crossgen2.osx-arm64`
package) and copied from `bin/Release/net9.0/osx-arm64/publish/`.

 Update `ClrFixtureParserTest`, `ClrIlDecoderTest`,
`ClrTableTabsTest` and `ClrIlTextTest` together with the fixture, and regenerate the base64 copy in
`parsing/pe/src/commonTest/.../clr/ClrSampleFixture.kt` that the JS and Wasm tests use.
