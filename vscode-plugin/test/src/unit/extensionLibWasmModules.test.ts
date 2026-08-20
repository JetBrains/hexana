import * as fs from 'fs';
import * as path from 'path';
import {expect} from 'chai';

/**
 * Contract test for the `WasmModuleIndex` surface the Wasm debug view uses.
 *
 * The view resolves every stop through the Kotlin/JS boundary, so a renamed
 * `@JsExport` member, a list where an array was expected, or a factory that
 * stopped being reachable would break it silently. This test loads the real
 * webpacked bundle and exercises it against two fixtures whose per-instruction
 * ground truth is documented elsewhere in the repo:
 *
 * ```
 * wasmtime-tiny.wasm  (core module)  func[0] add: 0x4e local.get 0; 0x50 local.get 1;
 *                                                 0x52 i32.add;    0x53 end
 * add.component.wasm  (component, embedded magic at 0xb)
 *                     func[0] add: 0x38 local.get 0; 0x3a local.get 1;
 *                                  0x3c i32.add;     0x3d end      (module-relative)
 * ```
 *
 * See `kb/wiki/entities/wasm-component-gdbstub-offsets.md` for why the component's
 * offsets are module-relative rather than component-absolute.
 *
 * It is skipped when the bundle has not been built
 * (`./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack`).
 */
describe('extension-lib wasm module index', function () {
    interface DisasmRow { offset: number; instruction: string }
    interface DisasmFunctionRange { index: number; start: number; end: number }
    interface StackSlot { type: string; provenance: string; localRef: number; globalRef: number }
    interface Disassembly {
        functionCount(): number;
        functionRangeForOffset(offset: number): DisasmFunctionRange | null;
        rowsForFunction(localIndex: number): DisasmRow[];
    }
    interface StackShape {
        stackAt(offset: number): { slots: StackSlot[] | null; unavailableReason: string | null };
    }
    interface ModuleIndex {
        readonly moduleCount: number;
        readonly isComponent: boolean;
        disassemblyFor(moduleId: number, offset: number): Disassembly | null;
        stackShapeFor(moduleId: number, offset: number): StackShape | null;
    }
    interface Lib {
        createWasmModuleIndex(bytes: Int8Array): ModuleIndex | null;
        wasmOffsetForGdbPcString(pc: string): number;
        wasmModuleIdForGdbPcString(pc: string): number;
    }

    let lib: Lib;
    let coreModule: ModuleIndex;
    let component: ModuleIndex;

    before(function () {
        const bundle = findUpwards(
            'vscode-plugin/extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js',
        );
        const corePath = findUpwards('plugins-shared/src/jvmTest/resources/wasmtime-tiny.wasm');
        const componentPath = findUpwards('vscode-plugin/test/fixtures/add.component.wasm');
        if (!bundle || !corePath || !componentPath) {
            this.skip();
            return;
        }
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const mod = require(bundle) as {
            hexanaExtensionLib: { org: { jetbrains: { hexana: { vscode: { lib: Lib } } } } };
        };
        lib = mod.hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;

        // A copy that owns its ArrayBuffer at offset 0, exactly as `readWasmBytes`
        // produces: Kotlin/JS reads `.buffer` and ignores `byteOffset`.
        const createdCore = lib.createWasmModuleIndex(new Int8Array(fs.readFileSync(corePath)));
        expect(createdCore, 'wasmtime-tiny.wasm must be indexable').to.not.equal(null);
        coreModule = createdCore!;

        const createdComponent = lib.createWasmModuleIndex(new Int8Array(fs.readFileSync(componentPath)));
        expect(createdComponent, 'add.component.wasm must be indexable').to.not.equal(null);
        component = createdComponent!;
    });

    it('treats a core module as a single module with id 0', function () {
        expect(coreModule.isComponent).to.equal(false);
        expect(coreModule.moduleCount).to.equal(1);
        const disasm = coreModule.disassemblyFor(0, 0x52);
        expect(disasm?.rowsForFunction(0).map((row) => [row.offset, row.instruction])).to.deep.equal([
            [0x4e, 'local.get 0'],
            [0x50, 'local.get 1'],
            [0x52, 'i32.add'],
            [0x53, 'end'],
        ]);
    });

    it('deduces the module when the id is unknown and rejects offsets outside the code', function () {
        // The view passes -1 for the DWARF-line fallback path, which has no module id.
        expect(coreModule.disassemblyFor(-1, 0x52)?.functionRangeForOffset(0x52)?.index).to.equal(0);
        // An id that cannot be right still resolves via the unique match.
        expect(coreModule.disassemblyFor(7, 0x52)).to.not.equal(null);
        // Offset 0 is the magic, long before the code section.
        expect(coreModule.disassemblyFor(0, 0)).to.equal(null);
    });

    it('hands out the same disassembly instance per module, so its cache survives', function () {
        expect(coreModule.disassemblyFor(0, 0x4e)).to.equal(coreModule.disassemblyFor(0, 0x53));
    });

    it('reconstructs the operand stack of the resolved module', function () {
        const shape = coreModule.stackShapeFor(0, 0x52)?.stackAt(0x52);
        expect(shape?.unavailableReason).to.equal(null);
        expect(shape?.slots?.map((slot) => slot.provenance)).to.deep.equal(['local 0', 'local 1']);
    });

    /**
     * A component is a container: the stub addresses its embedded core modules
     * individually and counts offsets from each embedded module's own magic (which
     * sits at component offset 0xb here), so the index has to slice them out.
     */
    it('indexes the embedded core modules of a component', function () {
        expect(component.isComponent).to.equal(true);
        expect(component.moduleCount).to.equal(1);
        const disasm = component.disassemblyFor(0, 0x3c);
        expect(disasm, 'the embedded module must own the module-relative offset').to.not.equal(null);
        const range = disasm!.functionRangeForOffset(0x3c)!;
        expect(disasm!.rowsForFunction(range.index).map((row) => [row.offset, row.instruction])).to.deep.equal([
            [0x38, 'local.get 0'],
            [0x3a, 'local.get 1'],
            [0x3c, 'i32.add'],
            [0x3d, 'end'],
        ]);
        expect(component.stackShapeFor(0, 0x3c)?.stackAt(0x3c).slots?.map((slot) => slot.provenance))
            .to.deep.equal(['local 0', 'local 1']);
    });

    it('does not read a component offset as a component-file offset', function () {
        // 0x47 is the same i32.add counted from the component's magic instead of the
        // embedded module's — not something the stub ever reports.
        expect(component.disassemblyFor(0, 0x47)).to.equal(null);
    });

    it('splits a gdbstub program counter into module id and offset', function () {
        // Live-observed wasmtime 44 pc: module 1, offset 0x1656.
        expect(lib.wasmModuleIdForGdbPcString('0x4000000100001656')).to.equal(1);
        expect(lib.wasmOffsetForGdbPcString('0x4000000100001656')).to.equal(0x1656);
        // A core module is always module 0.
        expect(lib.wasmModuleIdForGdbPcString('0x4000000000000052')).to.equal(0);
        expect(lib.wasmOffsetForGdbPcString('0x4000000000000052')).to.equal(0x52);
        // Decimal is accepted; anything else reads as -1 rather than 0.
        expect(lib.wasmOffsetForGdbPcString('82')).to.equal(82);
        expect(lib.wasmOffsetForGdbPcString('not a pc')).to.equal(-1);
        expect(lib.wasmModuleIdForGdbPcString('not a pc')).to.equal(-1);
    });
});

/** Walks up from this file looking for [relative], so the repo layout is not hardcoded. */
function findUpwards(relative: string): string | undefined {
    let dir = __dirname;
    for (let i = 0; i < 10; i++) {
        const candidate = path.join(dir, relative);
        if (fs.existsSync(candidate)) {
            return candidate;
        }
        const parent = path.dirname(dir);
        if (parent === dir) {
            break;
        }
        dir = parent;
    }
    return undefined;
}
