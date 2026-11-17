/**
 * Pairs a VS Code diff tab with the two files it compares, for diffs VS Code reports without
 * their sides (a diff of two files a custom editor claims). Kept free of the `vscode` module so
 * it is unit-testable.
 */

/** How VS Code labels a diff tab: `original ↔ modified`. */
export const DIFF_LABEL_SEPARATOR = " ↔ ";

/**
 * The (original, modified) sides of a diff tab labelled [label], chosen from [candidates]
 * (in the order they were resolved) by [nameOf], their file name.
 *
 * VS Code labels a diff `original ↔ modified` by file name — also when both names are the
 * same, putting the directories only into a description the Tab API does not expose. Two
 * same-named sides are therefore taken in resolution order, which may swap them. Returns
 * undefined unless both sides are found unambiguously.
 */
export function pairSidesForLabel<T>(label: string, candidates: T[], nameOf: (candidate: T) => string): [T, T] | undefined {
    const parts = label.split(DIFF_LABEL_SEPARATOR);
    if (parts.length !== 2) return undefined;
    const [leftName, rightName] = parts.map(part => part.trim());
    const byName = (name: string) => candidates.filter(candidate => nameOf(candidate) === name);
    if (leftName !== rightName) {
        const left = byName(leftName);
        const right = byName(rightName);
        return left.length === 1 && right.length === 1 ? [left[0], right[0]] : undefined;
    }
    const same = byName(leftName);
    return same.length === 2 ? [same[0], same[1]] : undefined;
}
