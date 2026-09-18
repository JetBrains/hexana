// params-demo.js
// Requires the Scripting v2 Phase 1 branch (feature/scripting-v2-phase1);
//   fails on master with "This script needs the Scripting v2 Phase 1 branch."
// Demonstrates: slice params with a default value. The IDE shows a toolbar with
//   a "minSize" input field pre-filled with 16. Changing it triggers a recompute.
//   In the CLI, params are not interactive; the default is used automatically.
// Binary: any Wasm binary.
// Expected outcome: a slice tab showing code functions whose body is >= minSize bytes.
// API version: v2 (feature/scripting-v2-phase1 only).

if (typeof hexana === "undefined") {
    print("This script needs the Scripting v2 Phase 1 branch.");
} else {
    hexana.defineSlice({
        name: "user:Functions by Min Body Size",
        formats: ["wasm"],
        params: [
            { name: "minSize", type: "int", default: 16 }
        ],
        columns: [
            { name: "funcidx",  type: "funcidx" },
            { name: "name",     type: "string" },
            { name: "bodySize", type: "int" },
            { name: "offset",   type: "offset" }
        ],
        rows: function(binary, params, paging) {
            var minSize = typeof params.minSize === "number" ? params.minSize : 16;

            var page = binary.functions({
                minBodySize: minSize,
                offset: paging.offset,
                limit: paging.limit
            });

            var rows = [];
            for (var i = 0; i < page.items.length; i++) {
                var fn = page.items[i];
                if (fn.source !== "code") continue; // minBodySize only applies to code funcs
                rows.push({
                    anchor: fn.codeOffset !== undefined ? fn.codeOffset : undefined,
                    cells: [
                        fn.funcidx,
                        fn.name !== null ? fn.name : "(unnamed)",
                        fn.codeSize !== undefined ? fn.codeSize : 0,
                        fn.codeOffset !== undefined ? fn.codeOffset : 0
                    ]
                });
            }

            return {
                rows: rows,
                nextOffset: page.nextOffset,
                total: page.total
            };
        }
    });
}
