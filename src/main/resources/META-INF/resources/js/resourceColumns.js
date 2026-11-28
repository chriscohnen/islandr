// How an expanded network's resources split into columns. Pure, no Vue, so
// scripts/resource-columns.test.mjs can run it under plain node.

/**
 * `n` rows, at most `maxRows` per column, balanced across the fewest columns
 * that fit — 17 rows at 9 per column are two columns (9 + 8), not 9 + 8 + 0.
 */
export function resourceColumns(n, maxRows) {
  const cap = Math.max(1, Math.floor(maxRows));
  const numCols = Math.max(1, Math.ceil(n / cap));
  return { numCols, rowsPerCol: Math.max(1, Math.ceil(n / numCols)) };
}

/**
 * Rows per column for a list next to a hub: no taller than the first ring
 * (hub in the middle, spokes at ±firstRing), so the diagram stays about as
 * tall as it is without the list, and never taller than the view allows.
 */
export function maxRowsPerColumn(firstRing, rowH, viewH, pad) {
  const ringRows = Math.floor((2 * firstRing) / rowH);
  const viewRows = Math.floor((viewH - 2 * pad) / rowH);
  return Math.max(1, Math.min(ringRows, viewRows));
}
