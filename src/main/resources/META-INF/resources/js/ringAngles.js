// Where things sit on a ring around the hub. Pure functions, no Vue, so
// scripts/ring-angles.test.mjs can run them under plain node.

export function angleAt(index, total, startDeg = -90) {
  const start = (startDeg * Math.PI) / 180;
  return start + (2 * Math.PI * index) / Math.max(total, 1);
}

/**
 * Angles for `n` items on a ring, placed in the arcs the `obstacles`
 * ({angle, half}) leave free — each arc gets a share of the items in
 * proportion to its length, spread evenly inside it. Returned in reading
 * order, clockwise from the upper left, so a sorted list stays sorted on
 * screen. Falls back to an even spread when the obstacles cover everything.
 */
export function freeArcAngles(n, obstacles, startDeg = -135) {
  if (n === 0) return [];
  const TAU = 2 * Math.PI;
  const norm = (a) => ((a % TAU) + TAU) % TAU;
  const blocked = [];
  for (const o of obstacles) {
    const s = norm(o.angle - o.half);
    const e = s + 2 * o.half;
    if (e <= TAU) blocked.push([s, e]);
    else { blocked.push([s, TAU]); blocked.push([0, e - TAU]); }
  }
  blocked.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const b of blocked) {
    const last = merged[merged.length - 1];
    if (last && b[0] <= last[1]) last[1] = Math.max(last[1], b[1]);
    else merged.push([...b]);
  }
  const free = [];
  let cur = 0;
  for (const [s, e] of merged) {
    if (s > cur) free.push([cur, s]);
    cur = Math.max(cur, e);
  }
  if (cur < TAU) free.push([cur, TAU]);
  // An arc running through 0 arrives as two pieces; join them.
  if (free.length > 1 && free[0][0] === 0 && free[free.length - 1][1] === TAU) {
    const tail = free.pop();
    free[0] = [tail[0], free[0][1] + TAU];
  }
  const total = free.reduce((sum, [a, b]) => sum + (b - a), 0);
  if (total < 1e-6) return Array.from({ length: n }, (_, i) => angleAt(i, n, startDeg));
  // Largest-remainder split of n across the arcs.
  const quotas = free.map(([a, b]) => (n * (b - a)) / total);
  const counts = quotas.map(Math.floor);
  const order = quotas.map((q, i) => [q - Math.floor(q), i]).sort((x, y) => y[0] - x[0]);
  // Count what is left once, up front: a bound recomputed per pass shrinks as
  // the counts grow and hands out too few places (peers ended up at NaN).
  const leftover = n - counts.reduce((sum, c) => sum + c, 0);
  for (let k = 0; k < leftover; k++) counts[order[k][1]]++;
  const out = [];
  free.forEach(([a, b], i) => {
    for (let j = 0; j < counts[i]; j++) out.push(a + ((b - a) * (j + 0.5)) / counts[i]);
  });
  const start = norm((startDeg * Math.PI) / 180);
  return out.sort((x, y) => norm(x - start) - norm(y - start));
}
