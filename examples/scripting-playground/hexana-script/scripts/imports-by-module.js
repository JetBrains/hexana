// imports-by-module.js
// Requires the Scripting v2 Phase 1 branch (feature/scripting-v2-phase1);
//   fails on master with "This script needs the Scripting v2 Phase 1 branch."
// Demonstrates: hexana.defineSlice, binary.imports(), console.debug for
//   progress lines (visible in the Log sub-tab, not the table).
// Intended binary: bin/hello.wasm (env.log import) or any Wasm with imports.
// Expected outcome: a slice tab "user:Imports by Module" grouping import counts
//   per module name. console.debug lines appear in the Log tab only.
// API version: v2 (feature/scripting-v2-phase1 only).

if (typeof hexana === "undefined") {
    print("This script needs the Scripting v2 Phase 1 branch.");
} else {
    hexana.defineSlice({
        name: "user:Imports by Module",
        formats: ["wasm"],
        columns: [
            { name: "module", type: "string" },
            { name: "count",  type: "int" },
            { name: "kinds",  type: "string" }
        ],
        rows: function(binary, params, paging) {
            // Only one page: aggregate all imports
            if (paging.offset > 0) {
                return { rows: [] };
            }

            console.debug("imports-by-module: fetching all imports...");
            var imports = binary.imports();
            console.debug("imports-by-module: " + imports.length + " import entries total");

            // Group by module
            var groups = {};
            for (var i = 0; i < imports.length; i++) {
                var imp = imports[i];
                if (!groups[imp.module]) {
                    groups[imp.module] = { count: 0, kinds: {} };
                }
                groups[imp.module].count += 1;
                groups[imp.module].kinds[imp.kind] = true;
            }

            var rows = [];
            var moduleNames = Object.keys(groups).sort();
            for (var j = 0; j < moduleNames.length; j++) {
                var mod = moduleNames[j];
                var g = groups[mod];
                var kindList = Object.keys(g.kinds).sort().join(", ");
                console.debug("imports-by-module: module=" + mod + " count=" + g.count);
                rows.push({
                    cells: [mod, g.count, kindList]
                });
            }

            return {
                rows: rows,
                total: rows.length
            };
        }
    });
}
