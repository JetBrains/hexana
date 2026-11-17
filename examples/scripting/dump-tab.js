// Dumps any editor tab as a table, so you can post-process it in a script.
//
// `binary.tabs` lists every table Hexana can render for the open file, and
// `binary.tab(name)` reads one using the same column names you see in the editor.
// Change TAB below to any name that `binary.tabs` reports.

const TAB = binary.tabs[0];
const MAX_ROWS = 500;

print("Available tabs: " + binary.tabs.join(", "));
print("");

const tab = binary.tab(TAB);
print(tab.name + ": " + tab.rowCount + " row(s), " + tab.columns.length + " column(s)");
print("");

// A tab the format advertises can legitimately have nothing to show for this
// binary — Problems on a module with no parse problems, for instance.
if (tab.columns.length === 0) {
    print("This tab has no content for this binary.");
} else {
    table.apply(null, tab.columns);
    const shown = Math.min(tab.rowCount, MAX_ROWS);
    for (let i = 0; i < shown; i++) {
        row.apply(null, tab.row(i));
    }
    if (shown < tab.rowCount) {
        print("… " + (tab.rowCount - shown) + " more row(s) not shown; raise MAX_ROWS to see them");
    }
}
