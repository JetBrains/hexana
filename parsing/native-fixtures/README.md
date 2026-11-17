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
- `test-data/pe/sample-clr-v2.exe` (see below; not built by the Docker image)
- `test-data/pe/sample-clr-review-base.dll`, `sample-clr-review-head.dll` (see below; not built by the Docker image)
- `test-data/pe/sample-clr-portable.exe` + `sample-clr-portable.pdb` (see below; not built by the Docker image)
- `test-data/pe/sample-clr-embedded.exe` (see below; not built by the Docker image)
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

`test-data/pe/sample-clr-v2.exe` is the head side of the assembly diff tests (`ClrDiffProviderTest` in
`plugins-shared`): `src/clr/diff/SampleV2.cs` is `Sample.cs` with a changed method body, a removed property, an added
`switch` case, enum member and type. Build it the same way, with `-nowarn:67 -out:../../test-data/pe/sample-clr-v2.exe
diff/SampleV2.cs`.

`test-data/pe/sample-clr-review-base.dll` and `sample-clr-review-head.dll` are `src/clr/diff/Review.cs` built as a
library without and with `-define:HEAD` (`-target:library`, same flags otherwise); they pin assembly diff cases: the
same short type name from another namespace, a user string reading like an RVA comment, a method moved between types.

The parser tests cross-check it against `monodis --typedef`, `--method`, `--memberref` and `--manifest` output,
and the IL tests against `ikdasm sample-clr.exe`. Update `ClrFixtureParserTest`, `ClrIlDecoderTest`,
`ClrTableTabsTest` and `ClrIlTextTest` together with the fixture, and regenerate the base64 copy in
`parsing/pe/src/commonTest/.../clr/ClrSampleFixture.kt` that the JS and Wasm tests use.

### Windows PDB reference dump

`test-data/pe/sample.pdb` is the PDB `lld-link` writes next to `sample.exe` (they share GUID
`{AEBD2BE3-…}` and age 1). `sample.pdb.llvm-pdbutil.txt` is what `debugInfo/pdb`'s `PdbFixtureTest`
is checked against:

```bash
cd parsing/native-fixtures/test-data/pe
llvm-pdbutil dump -summary -streams -modules -files -section-contribs -types -ids -l -xme -string-table sample.pdb > sample.pdb.llvm-pdbutil.txt
```

### Portable PDB fixture

`test-data/pe/sample-clr-portable.exe` and `sample-clr-portable.pdb` are a debug build of the same
`src/clr/Sample.cs` with a Portable PDB (the assembly's CodeView record names the PDB's id).
`sample-clr-portable.pdb.dump.txt` is what the runtime's own `System.Reflection.Metadata` reader reports
for it (`src/clr/pdb/DumpPortablePdb.cs`: PDB id, tables, documents and every sequence point), and is what
`ClrPortablePdbTest` checks. `sample-clr-embedded.exe` is the same build with `-debug:embedded` (the PDB
deflated into debug directory entry 17); its dump comes from the same tool given the `.exe`. Deterministic
builds give both variants the same PDB id.

```bash
cd parsing/native-fixtures/src/clr
T=$(cd ../../test-data/pe && pwd)
cp "$T/sample.resources" /tmp/strings.resources
csc -nologo -deterministic -optimize- -debug:portable -pathmap:"$PWD"=/src/clr,"$T"=/out/pe \
  -resource:hello.txt,Hexana.Sample.hello.txt \
  -resource:/tmp/strings.resources,Hexana.Sample.Strings.resources \
  -out:"$T/sample-clr-portable.exe" -pdb:"$T/sample-clr-portable.pdb" Sample.cs
csc -nologo -deterministic -optimize- -debug:embedded -pathmap:"$PWD"=/src/clr,"$T"=/out/pe \
  -resource:hello.txt,Hexana.Sample.hello.txt \
  -resource:/tmp/strings.resources,Hexana.Sample.Strings.resources \
  -out:"$T/sample-clr-embedded.exe" Sample.cs
dotnet run --project pdb -- "$T/sample-clr-portable.pdb" > "$T/sample-clr-portable.pdb.dump.txt"
dotnet run --project pdb -- "$T/sample-clr-embedded.exe" > "$T/sample-clr-embedded.exe.dump.txt"
```

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
