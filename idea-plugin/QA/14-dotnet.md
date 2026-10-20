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

### 14.10 Malformed input
**Steps:** Copy `sample-clr.exe`, truncate the copy to 3,400 bytes, and open it.
**Expected:** The file opens; tabs that need missing data are empty or absent, **Top** shows `.text` with only the
bytes present. No exception in `idea.log`.
