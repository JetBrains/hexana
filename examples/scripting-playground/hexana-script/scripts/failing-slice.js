// failing-slice.js
// Requires the Scripting v2 Phase 1 branch (feature/scripting-v2-phase1);
//   fails on master with "This script needs the Scripting v2 Phase 1 branch."
// Demonstrates: a slice whose rows() callback throws on the second page,
//   exercising Hexana's error row in the table view.
// Binary: any binary with at least one function.
// Expected outcome: first page loads normally; scrolling to load more produces
//   a problem row with "second page fails deliberately".
// API version: v2 (feature/scripting-v2-phase1 only).

if (typeof hexana === "undefined") {
    print("This script needs the Scripting v2 Phase 1 branch.");
} else {
    hexana.defineSlice({
        name: "user:Failing Slice Demo",
        formats: ["wasm"],
        columns: [
            { name: "funcidx", type: "funcidx" },
            { name: "source",  type: "string" },
            { name: "name",    type: "string" }
        ],
        rows: function(binary, params, paging) {
            if (paging.offset > 0) {
                // Deliberately fail on any page after the first
                throw new Error("second page fails deliberately");
            }

            // First page: return up to limit functions
            var page = binary.functions({
                offset: 0,
                limit: paging.limit
            });

            var rows = [];
            for (var i = 0; i < page.items.length; i++) {
                var fn = page.items[i];
                rows.push({
                    anchor: fn.codeOffset !== undefined ? fn.codeOffset : undefined,
                    cells: [
                        fn.funcidx,
                        fn.source,
                        fn.name !== null ? fn.name : "(unnamed)"
                    ]
                });
            }

            // Signal there is a next page (even if there isn't) to trigger the error path
            return {
                rows: rows,
                nextOffset: page.nextOffset !== undefined ? page.nextOffset : (page.items.length > 0 ? paging.limit : undefined),
                total: page.total
            };
        }
    });
}
