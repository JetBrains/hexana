// Diffs the open binary against another file on disk.
//
// Set OTHER below to the file you want to compare against — usually a previous
// build of the same thing — and re-run the tab. `open(path)` returns the same
// members as `binary`, so both sides are read exactly the same way, and the
// comparison stays format-agnostic: it lines up `structure` regions by name, so
// it works for two Wasm modules, two ELF objects, or a JAR against the JAR it
// replaced.
//
// `binary.dir` is the directory of the open file, so a nearby file can be named
// without hardcoding a path: `binary.dir + "/previous/app.wasm"`.
//
// `open(path)` needs a host that can read local files: every editor has it, the
// browser web app does not and says so. In a VS Code script tab the call has to
// sit at the top level, as it does below — the script pauses there while the
// extension reads the file, which it cannot do from inside a function.

const OTHER = "";
const TOP = 30;

// Helpers here only compute — print/table/row stay at the top level, because a
// script tab may run this as a generator to keep its output streaming.

// Regions are summed per name: several can share one (multiple custom sections,
// repeated archive entries), and a total is comparable where a single arbitrary
// match is not.
//
// Object.create(null), not {}: region names come out of the file, and a Wasm
// custom section or an archive entry may legitimately be called "constructor"
// or "__proto__". On a plain object those collide with Object.prototype —
// `sizes["constructor"] || 0` reads back a function, so the running total turns
// into a string, and `"toString" in sizes` is true for a region that is not
// there at all.
function regionSizes(target) {
    const sizes = Object.create(null);
    for (const region of target.structure) {
        sizes[region.name] = (sizes[region.name] || 0) + region.size;
    }
    return sizes;
}

function signed(value) {
    return value > 0 ? "+" + value : String(value);
}

let other = null;
let failure = null;
if (OTHER) {
    // One try/catch covers every way a path can fail — no such file, a path
    // outside the configured allowed roots, or a host that cannot read files at
    // all — and turns it into a line in the tab instead of a stack trace. It
    // works the same in every editor: where the read happens elsewhere, the
    // failure is thrown back in at the call.
    try {
        other = open(OTHER);
    } catch (error) {
        failure = error.message || String(error);
    }
}

if (!OTHER) {
    print("Set OTHER at the top of this script to the path of the file to compare against,");
    print("then re-run this tab. A previous build of " + (binary.name || "this binary") +
          " is the usual choice.");
    if (binary.dir) {
        print("");
        print("This file lives in " + binary.dir + ", so a nearby one can be written as:");
        print("    const OTHER = binary.dir + \"/previous/" + (binary.name || "binary") + "\";");
    }
} else if (other === null) {
    print("Could not open " + OTHER);
    print(failure);
} else {
    print("open:  " + (binary.name || "(unnamed)") + " — " + binary.formatName + ", " +
          binary.size + " bytes");
    print("other: " + (other.name || "(unnamed)") + " — " + other.formatName + ", " +
          other.size + " bytes");
    print("delta: " + signed(binary.size - other.size) + " bytes");
    print("");

    if (binary.format !== other.format) {
        print("The two files are different formats (" + binary.format + " and " + other.format +
              "), so only their sizes are comparable.");
    } else {
        const mine = regionSizes(binary);
        const theirs = regionSizes(other);
        const names = Object.keys(mine).concat(Object.keys(theirs).filter(name => !(name in mine)));
        const changed = names
            .map(name => ({ name: name, mine: mine[name] || 0, theirs: theirs[name] || 0 }))
            .filter(entry => entry.mine !== entry.theirs)
            .sort((a, b) => Math.abs(b.mine - b.theirs) - Math.abs(a.mine - a.theirs));

        print("Regions: " + names.length + " by name, " + changed.length + " differing in size");
        print("");

        if (changed.length > 0) {
            table("Region", "Open", "Other", "Delta");
            for (const entry of changed.slice(0, TOP)) {
                row(entry.name, entry.mine, entry.theirs, signed(entry.mine - entry.theirs));
            }
            if (changed.length > TOP) {
                print("… " + (changed.length - TOP) + " more; raise TOP to see them");
            }
            print("");
        }

        // Row counts come from the tables the editor itself renders, so "28 more
        // imports than the previous build" needs no format-specific knowledge.
        // Only tabs both files advertise are compared; the rest are listed below.
        const shared = binary.tabs.filter(name => other.tabs.indexOf(name) >= 0);
        const rowCounts = shared
            .map(name => ({ name: name, mine: binary.tab(name).rowCount, theirs: other.tab(name).rowCount }))
            .filter(entry => entry.mine !== entry.theirs);

        if (rowCounts.length > 0) {
            table("Tab", "Open rows", "Other rows", "Delta");
            for (const entry of rowCounts) {
                row(entry.name, entry.mine, entry.theirs, signed(entry.mine - entry.theirs));
            }
            print("");
        } else {
            print("Every tab both files have holds the same number of rows.");
            print("");
        }

        const onlyHere = binary.tabs.filter(name => other.tabs.indexOf(name) < 0);
        const onlyThere = other.tabs.filter(name => binary.tabs.indexOf(name) < 0);
        if (onlyHere.length > 0) {
            print("Only in the open binary: " + onlyHere.join(", "));
        }
        if (onlyThere.length > 0) {
            print("Only in the other binary: " + onlyThere.join(", "));
        }
    }
}
