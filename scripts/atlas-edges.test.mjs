// node --test scripts/atlas-edges.test.mjs
// No dependencies, like check-templates.mjs: node's own test runner.
import test from "node:test";
import assert from "node:assert/strict";
import { aggregateEdges, EXPAND_SCALE } from "../src/main/resources/META-INF/resources/js/AtlasEdges.js";

const circles = { u1: "mobile", u2: "mobile", gw: "siteA", r1: "siteA", r2: "siteA", r3: "siteB", r4: "siteB" };
const ctx = (extra = {}) => ({
  circleOfSubject: (id) => circles[id],
  circleOfResource: (id) => circles[id],
  ...extra,
});
const edge = (subjectId, resourceId, kind = "role-grant", roleId = "") => ({ subjectType: "user", subjectId, resourceId, kind, roleId });

test("several grants from one user into one site become one counted group", () => {
  const { singles, groups } = aggregateEdges([edge("u1", "r1"), edge("u1", "r2")], ctx());
  assert.equal(singles.length, 0);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].count, 2);
  assert.equal(groups[0].circleId, "siteA");
  assert.equal(groups[0].edges.length, 2);
});

test("grants into different sites stay separate groups", () => {
  const { groups } = aggregateEdges([edge("u1", "r1"), edge("u1", "r2"), edge("u1", "r3"), edge("u1", "r4")], ctx());
  assert.deepEqual(groups.map((g) => [g.circleId, g.count]).sort(), [["siteA", 2], ["siteB", 2]]);
});

test("kinds are never merged: each keeps its own line", () => {
  const { groups } = aggregateEdges([
    edge("u1", "r1", "role-grant"), edge("u1", "r2", "role-grant"),
    edge("u1", "r1", "user-grant"), edge("u1", "r2", "user-grant"),
  ], ctx());
  assert.equal(groups.length, 2);
  assert.deepEqual(groups.map((g) => g.kind).sort(), ["role-grant", "user-grant"]);
});

test("a single grant stays an ordinary line, no count of one", () => {
  const { singles, groups } = aggregateEdges([edge("u1", "r1"), edge("u2", "r2")], ctx());
  assert.equal(groups.length, 0);
  assert.equal(singles.length, 2);
});

test("network grants are never aggregated", () => {
  const ng = (s) => ({ subjectType: "user", subjectId: s, siteId: "siteA", kind: "network-grant" });
  const { singles, groups } = aggregateEdges([ng("u1"), ng("u2")], ctx());
  assert.equal(groups.length, 0);
  assert.equal(singles.length, 2);
});

test("expand() draws the matching edges individually and aggregates the rest", () => {
  const edges = [edge("u1", "r1"), edge("u1", "r2"), edge("u2", "r1"), edge("u2", "r2")];
  const { singles, groups } = aggregateEdges(edges, ctx({ expand: (e) => e.subjectId === "u1" }));
  assert.deepEqual(singles.map((e) => e.subjectId), ["u1", "u1"]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].subjectId, "u2");
});

test("a subject inside the target circle is not aggregated into a self-loop", () => {
  const { singles, groups } = aggregateEdges([edge("gw", "r1", "site-grant"), edge("gw", "r2", "site-grant")], ctx());
  assert.equal(groups.length, 0);
  assert.equal(singles.length, 2);
});

test("edges to nodes the layout does not know stay singles", () => {
  const { singles, groups } = aggregateEdges([edge("u1", "ghost"), edge("u1", "ghost2")], ctx());
  assert.equal(groups.length, 0);
  assert.equal(singles.length, 2);
});

test("the input order of singles is kept", () => {
  const { singles } = aggregateEdges([edge("u1", "r1"), edge("u2", "r3"), edge("u1", "r2")], ctx({ expand: (e) => e.subjectId === "u2" }));
  assert.equal(singles.length, 1); // u1→r1 + u1→r2 aggregate, u2→r3 expanded
  assert.equal(singles[0].subjectId, "u2");
});

test("the expand scale is above the default view scale", () => {
  assert.ok(EXPAND_SCALE > 1);
});

import { networkGrantShown } from "../src/main/resources/META-INF/resources/js/AtlasEdges.js";

const ng = { kind: "network-grant", subjectId: "u1", siteId: "siteA", roleId: "everyone" };
const none = { selectedUserId: null, selectedSiteId: null, selectedRoleId: "", hovered: false };

test("a network grant stays out of the default view", () => {
  assert.equal(networkGrantShown(ng, none), false);
});

test("it appears for its user, its site, its table row, and its role picked in the dropdown", () => {
  assert.equal(networkGrantShown(ng, { ...none, selectedUserId: "u1" }), true);
  assert.equal(networkGrantShown(ng, { ...none, selectedSiteId: "siteA" }), true);
  assert.equal(networkGrantShown(ng, { ...none, hovered: true }), true);
  assert.equal(networkGrantShown(ng, { ...none, selectedRoleId: "everyone" }), true);
});

test("another role, user or site does not bring it in; other edge kinds are never affected", () => {
  assert.equal(networkGrantShown(ng, { ...none, selectedRoleId: "ops" }), false);
  assert.equal(networkGrantShown(ng, { ...none, selectedUserId: "u2", selectedSiteId: "siteB" }), false);
  assert.equal(networkGrantShown({ kind: "role", subjectId: "u1" }, none), true);
});
