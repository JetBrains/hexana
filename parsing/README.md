# Native Binary Parsing

`parsing` contains native binary parser modules that are independent from IDE UI and provider code.

## Modules

- `parsing:common` owns shared parser primitives: byte ranges, binary format detection, checked binary reading, diagnostics, parse results, and unresolved-record metadata.
- `parsing:elf` owns ELF structures and parsing.
- `parsing:macho` owns Mach-O and universal Mach-O structures and parsing.
- `parsing:pe` owns PE image, COFF object, COFF object relocation, and COFF archive structures and parsing.
- `parsing/native-fixtures` owns committed native parser fixture binaries plus reproducible C/C++ source inputs and Docker tooling for refreshing them.

## Parser Contract

Format modules expose two levels of API:

- focused parsers such as `parseHeader`, `parseSections`, and `parseSymbols`;
- aggregate parsers such as `parse(...)` that return a typed file model through `NativeParseResult`.

Aggregate parsers should fail only when the core container cannot be trusted. Optional tables should prefer a partial file model with warning diagnostics.

Unknown but well-bounded records should be preserved as `NativeUnresolvedRecord` instead of being silently discarded.

## Testing

Run the native parser JVM tests with:

```bash
./gradlew :parsing:common:jvmTest :parsing:elf:jvmTest :parsing:macho:jvmTest :parsing:pe:jvmTest
```
