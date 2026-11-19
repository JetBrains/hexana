# 14 — .NET Assemblies

## Prerequisites
- `parsing/native-fixtures/test-data/pe/sample-clr.exe` — small IL-only .NET Framework assembly
  (`src/clr/Sample.cs`: generics, nested types, `switch`, `try`/`catch`/`finally`, an embedded resource)
- `parsing/native-fixtures/test-data/pe/sample-r2r-win-x64.dll` — the same kind of code compiled ReadyToRun
- A large ReadyToRun framework assembly: `System.Private.CoreLib.dll` from any .NET 8+ runtime
  (`dotnet --list-runtimes` shows where; ~15 MB, ~38k methods)
- An application with many ReadyToRun assemblies that reference each other, e.g. a portable
  paint.net 5.x: `ComputeSharp.Core.dll`, `PaintDotNet.Core.dll`
- A native PE (`parsing/native-fixtures/test-data/pe/sample.exe`) for comparison

## Test Cases

### 14.1 Tabs of an IL-only assembly
**Steps:** Open `sample-clr.exe`.
**Expected:** The file type reads `PE32 (.NET)`. Tabs: **Headers**, **.NET**, **Types**, **Methods**, **Assembly Refs**,
**Sections**, …, **.NET Metadata** (Streams, Tables, Type Refs, Member Refs, User Strings, Resources), **Top**,
**IL Disassembly**, with **Debug Context** last. There are **no Disassembly** tabs. The editor and its tab
placeholders appear immediately; the tabs fill in afterwards.

### 14.2 .NET overview
**Steps:** On `sample-clr.exe` open **.NET**; then open `sample-r2r-win-x64.dll` and `CoreLib`.
**Expected:** Platform `AnyCPU`, entry point `Hexana.Sample.Program::Main`, target framework `.NETFramework,Version=v4.8`,
assembly `sample-clr 1.2.3.4`. The ReadyToRun files show `ReadyToRun: yes` and a platform such as
`x64 (ReadyToRun, Windows)` or `ARM64 (ReadyToRun, macOS)` — never a bare hex machine value.

### 14.3 Types and methods
**Steps:** In **Types**, expand `Hexana.Sample.Box<T>`; click its `Map` method. In **Methods**, search for `Describe`.
**Expected:** Fields first, then methods, with C#-style signatures (`U Map<U>(Func<T, U> map)`). Clicking a method
selects its IL body in the hex view. Abstract interface methods have no IL size.

### 14.4 IL Disassembly
**Steps:** Open **IL Disassembly**; expand `Hexana.Sample.Program`; click the offset of an instruction in `Describe`.
**Expected:** Every type starts collapsed. `Describe` shows `.maxstack`, `.locals init (string V_0)`, a
`switch (IL_0014, IL_001c, IL_0024)`, string literals in quotes, `callvirt string Box<int>::Map<string>(…)`, and
two `.try` lines (catch `System.FormatException`, finally). The click selects exactly that instruction's bytes.
Searching `Describe` in the panel jumps to the type.

### 14.5 IL view on a large assembly
**Steps:** Open `System.Private.CoreLib.dll` → **IL Disassembly**; expand `System.String` and `System.Array`; switch to
another tab while a type is still rendering and come straight back; expand another type.
**Expected:** The tab opens without a noticeable freeze; each type renders in well under a second. Switching away
mid-render and back neither errors (check `idea.log`) nor shows garbled IL.

### 14.6 Disassembly tabs
**Steps:** Open `sample-clr.exe`, `sample-r2r-win-x64.dll`, `ComputeSharp.Core.dll`, then the native `sample.exe`.
**Expected:** The three managed files show **IL Disassembly** and no **Disassembly** / **Disassembly (virtualized)**
tabs (their native code, if any, is compiled from IL). The native file keeps both Disassembly tabs and has no
.NET tabs.

### 14.7 Top — IL-only
**Steps:** On `sample-clr.exe` open **Top** and expand `.text`.
**Expected:** `.text` breaks down into **IL method bodies** (namespace → type → method), **CLR metadata** (streams,
and `#~` per table), **Managed resources** (`Hexana.Sample.hello.txt`, 22 B), CLI header, strong-name signature,
PE data directories and **Padding**. Each level's rows add up to its parent.

### 14.8 Top — ReadyToRun
**Steps:** On `sample-r2r-win-x64.dll`, then `ComputeSharp.Core.dll`, open **Top** and expand `.text` → **Native code
(ReadyToRun)**.
**Expected:** Compiled methods grouped namespace → type → method (`int Fib(int n)` = 51 B in the sample;
instantiations such as `Box<int>::ToString` under `Box<T>`). In ComputeSharp, **Compiled from other assemblies →
System.Private.CoreLib** holds most of the native code. **ReadyToRun data** lists named tables (RuntimeFunctions,
DebugInfo, Unwind and GC info, Import cells, …). **Unattributed** is absent in the sample and small elsewhere.
The treemap shows the same groups.

### 14.9 .resources files
**Steps:** Open `parsing/native-fixtures/test-data/pe/sample.resources`; then a satellite resource file such as
paint.net's `PaintDotNet.Strings.3.de.resources`. In `sample-clr.exe` open **.NET Metadata → Resources** and expand
`Hexana.Sample.Strings.resources`.
**Expected:** The file type reads `.NET Resources`. **Header** shows `System.Resources.ResourceReader…`, format version 2
and one user type (`Margin, MakeResources, …`). **Resources** lists 19 entries: `Greeting` = `Hello from Hexana`,
`Price` = `12345.6789`, `Released` = `2026-10-20T13:45:30Z`, `Pixel` = `12 bytes (PNG image)`, `Margin` as a serialized
value; selecting `Pixel` highlights only its 12 payload bytes. The paint.net file lists its translated strings. The
embedded set expands to the same 19 entries.

### 14.10 PDB files
**Steps:** Open `parsing/native-fixtures/test-data/pe/sample.pdb`, then `sample-clr-portable.pdb`; then a text file
named `1crn.pdb` (any Protein Data Bank entry, or a line `HEADER    PLANT PROTEIN`).
**Expected:** `sample.pdb` reads `Windows PDB`: **PDB** shows GUID `AEBD2BE3-8B00-6D1E-4C4C-44205044422E`, age 1,
machine AMD64; **Streams** lists 15 streams (stream 3 = DBI, 11 = module `/tmp/sample-5bae4d.o`); **Source Files**
lists `/src/src/sample.c`. `sample-clr-portable.pdb` reads `Portable PDB`: **Documents** lists `/src/clr/Sample.cs`
(C#, SHA-256), **Tables** has 14 MethodDebugInformation rows. The text `.pdb` opens in the text editor, not Hexana.

### 14.11 Source lines from PDBs
**Steps:** Open `sample.exe` (its `sample.pdb` is next to it) and open **Disassembly**, **Debug Context** and **Debug Info**.
Then open `sample-clr-portable.exe` and `sample-clr-embedded.exe`: **IL Disassembly** (expand `Hexana.Sample.Greeter`),
**Methods**, **Debug Info**. Finally copy `sample.exe` alone to an empty directory with an unrelated `sample.pdb`
(for example `sample-clr-portable.pdb` renamed) and open it.
**Expected:** `sample.exe`: instructions show `/src/src/sample.c` lines; **Debug Context** PDB row is `found`,
"Loaded: the PDB matches the image (AEBD2BE3-…, age 1)"; **Debug Info** shows 12 line rows. Both managed samples:
`.line 19,19 : 9,38 '/src/clr/Sample.cs'` before the first instruction of the `Greeter` constructor, whose **Methods → Source** reads
`Sample.cs:19`; the embedded one's **Debug Info** says "Portable PDB embedded in the image". The copy with the wrong
PDB: status `does not match`, "Not loaded: the image expects the other kind of PDB"; no **Debug Info** tab.

### 14.12 Malformed input
**Steps:** Copy `sample-clr.exe`, truncate the copy to 3,400 bytes, and open it.
**Expected:** The file opens; tabs that need missing data are empty or absent, **Top** shows `.text` with only the
bytes present. No exception in `idea.log`.

### 14.13 Blazor WebAssembly (Webcil)
**Steps:** Open `parsing/native-fixtures/test-data/pe/sample-clr.webcil.wasm` and open **.NET**, **Types** and
**IL Disassembly** (expand `Hexana.Sample.Greeter`). Then open `sample-clr-portable.webcil.wasm` (its
`sample-clr-portable.pdb` is next to it) and `sample-clr-embedded.webcil.wasm`. Optionally `dotnet publish` a
`dotnet new blazorwasm` app and open a few `wwwroot/_framework/*.wasm` files, including `System.Private.CoreLib.*.wasm`.
**Expected:** The file type reads `Core Wasm (.NET Webcil)`. The .NET tabs come before **Data**/**Exports**; **.NET**
shows `Container: Webcil 0.0 in a Wasm wrapper (browser-wasm)` and assembly `sample-clr`, with no Platform row.
**Types** matches `sample-clr.exe`. Clicking an IL instruction selects bytes inside the wrapper's data segment.
Both PDB samples show `.line 19,19 : 9,38 '/src/clr/Sample.cs'` in the `Greeter` constructor. Published assemblies
all open with types and methods; `dotnet.native.*.wasm` stays a plain Wasm module.

### 14.14 Symbol map names for `dotnet.native.wasm`
**Steps:** Copy `dotnet.native.wasm` and `dotnet.native.js.symbols` from
`~/.nuget/packages/microsoft.netcore.app.runtime.mono.browser-wasm/<version>/runtimes/browser-wasm/native/` into one
directory and open the `.wasm`; open **Functions** and **WAT (virtualized)**. Then put a symbol map of a different
build (or an edited copy with `0:wrong_name` on every import line) beside it and reopen.
**Expected:** Functions carry Mono names (`mono_wasm_load_runtime`, `mono_runtime_invoke`, …) instead of bare indices. The
mismatching map is ignored and the functions stay unnamed.

### 14.15 Blazor App tab
**Steps:** `dotnet publish -c Release` a `dotnet new blazorwasm` app and open
`bin/…/publish/wwwroot/_framework/System.Private.CoreLib.*.wasm`, then `dotnet.native.*.wasm`. Open **Blazor App**.
Then copy the `_framework` directory, change one byte of the app's own `app.*.wasm` copy, delete one `System.*.wasm`,
and open the changed file.
**Expected:** The tab lists every manifest file (runtime first, then core assemblies and assemblies by download size,
ICU data, JS modules) with Size, Brotli and Gzip columns; Integrity reads `matches` for all. The text above says the
manifest is `dotnet.<hash>.js (embedded, .NET 10+)` (or `blazor.boot.json (.NET 8/9)` for a .NET 9 app), the startup
download (about 2 MB Brotli for the template app), the three ICU shards, `Globalization sharded, trimmed, debugging off`,
and which file is open. In the edited copy the changed file's row is tinted and reads `differs from the manifest`, the
deleted one `file missing`, and both are named above the table. A `.wasm` that is not in the manifest gets no tab.

### 14.16 .NET AOT tab
**Steps:** With the `wasm-tools` workload installed, `dotnet publish -c Release -p:RunAOTCompilation=true
-p:WasmEmitSymbolMap=true` a `dotnet new blazorwasm` app (default output location) and open
`bin/Release/<tfm>/publish/wwwroot/_framework/dotnet.native.*.wasm`. Open **.NET AOT**; expand Managed code (AOT) →
`app` → `app.Pages` → `Counter`. Then open the `dotnet.native.*.wasm` of a publish without AOT.
**Expected:** The module is ~13 MB and its functions carry names (the map was found under `obj/…/wasm/for-publish/`).
**.NET AOT** reads "Ahead-of-time compiled .NET app: 10.0 MB of code in a 13.4 MB module" and lists, largest first,
Managed code (AOT) (~47%), Generic instances (~25%), Mono runtime and other native code, ICU, Wrappers and trampolines,
Native libraries. Under managed code System.Private.CoreLib comes first; `Counter` lists
`void BuildRenderTree(RenderTreeBuilder __builder)` and `void IncrementCount()`; selecting a method highlights its code.
The treemap shows the same tree. The interpreter-only module has no **.NET AOT** tab.

