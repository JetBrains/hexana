// large-unnamed-functions.js
// Requires the Scripting v2 Phase 1 branch (feature/scripting-v2-phase1);
//   fails on master with "This script needs the Scripting v2 Phase 1 branch."
// Demonstrates: hexana.defineSlice, binary.functions({unnamed, minBodySize}),
//   paginated rows, anchor-to-funcidx, total count.
// Intended binary: bin/many-functions.wasm (run gen-many-functions.sh first).
//   Also works on any Wasm binary; with few functions the table will just be short.
// Expected outcome: a slice tab named "user:Unnamed Functions" listing unnamed
//   functions above a configurable minimum body size, with hex-offset anchors.
// API version: v2 (feature/scripting-v2-phase1 only).

if (typeof hexana === "undefined") {
    print("This script needs the Scripting v2 Phase 1 branch.");
} else {
    hexana.defineSlice({
        name: "user:Unnamed Functions",
        formats: ["wasm"],
        params: [
            { name: "minSize", type: "int", default: 1 }
        ],
        columns: [
            { name: "funcidx", type: "funcidx" },
            { name: "name",    type: "string" },
            { name: "size",    type: "int" },
            { name: "offset",  type: "offset" }
        ],
        rows: function(binary, params, paging) {
            var minSize = typeof params.minSize === "number" ? params.minSize : 1;
            var page = binary.functions({
                unnamed: true,
                minBodySize: minSize,
                offset: paging.offset,
                limit: paging.limit
            });

            var rows = [];
            for (var i = 0; i < page.items.length; i++) {
                var fn = page.items[i];
                rows.push({
                    anchor: fn.codeOffset !== undefined ? fn.codeOffset : fn.funcidx,
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
