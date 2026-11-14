// Edge aggregation for the Atlas diagram. No imports on purpose: the module is
// plain data in, plain data out, so scripts/atlas-edges.test.mjs can load it
// under node without the browser-only Vue build.
//
// With ~20 users and ~60 resources the default view drew one line per grant,
// all of them leaving the same "Mobile / Roaming" circle — a bouquet. Until the
// viewer asks about something specific, several grants from one subject into
// one site are drawn as a single line carrying the count; hover, selection and
// zoom bring the individual lines back. The data is untouched — only what is
// drawn changes.
//
// One group per (subject, target circle, kind). Kinds are never merged: each
// keeps its own colour and stroke, so a role grant and a direct grant into the
// same site stay two visible lines instead of one swallowing the other.

/** Zoom factor at and above which every grant is drawn individually. */
export const EXPAND_SCALE = 1.6;

/**
 * @param {Array} edges  graph edges ({ subjectId, resourceId, kind, ... })
 * @param {Object} ctx
 * @param {(id: string) => (string|undefined)} ctx.circleOfSubject  circle holding the subject node
 * @param {(id: string) => (string|undefined)} ctx.circleOfResource circle holding the resource node
 * @param {(edge: Object) => boolean} [ctx.expand] true = draw this edge individually
 * @returns {{ singles: Array, groups: Array<{key: string, subjectId: string, circleId: string, kind: string, edges: Array, count: number}> }}
 */
export function aggregateEdges(edges, { circleOfSubject, circleOfResource, expand = () => false }) {
  const singles = [];
  const byKey = new Map();
  const order = []; // one slot per edge, so singles keep the input order
  for (const e of edges) {
    const from = circleOfSubject(e.subjectId);
    const to = e.kind === "network-grant" ? undefined : circleOfResource(e.resourceId);
    const mergeable = e.kind !== "network-grant" && !expand(e) && from && to && from !== to;
    if (!mergeable) {
      order.push({ single: e });
      continue;
    }
    const key = [e.subjectId, to, e.kind].join("|");
    let g = byKey.get(key);
    if (!g) {
      g = { key, subjectId: e.subjectId, circleId: to, kind: e.kind, edges: [], count: 0 };
      byKey.set(key, g);
    }
    g.edges.push(e);
    g.count++;
    order.push({ group: g, edge: e });
  }
  const groups = [];
  for (const o of order) {
    if (o.single) singles.push(o.single);
    else if (o.group.count === 1) singles.push(o.edge); // a count of one says nothing a line does not
  }
  for (const g of byKey.values()) if (g.count > 1) groups.push(g);
  return { singles, groups };
}
