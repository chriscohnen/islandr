// node --test scripts/ring-angles.test.mjs
// No dependencies, like check-templates.mjs: node's own test runner.
import test from "node:test";
import assert from "node:assert/strict";
import { freeArcAngles } from "../src/main/resources/META-INF/resources/js/ringAngles.js";

// The dashboard's obstacles: the hub label straight down, plus one per spoke.
const HUB_LABEL = { angle: Math.PI / 2, half: 0.94 };
const spokes = (k) => Array.from({ length: k }, (_, i) => ({ angle: -Math.PI / 2 + (2 * Math.PI * i) / Math.max(k, 1), half: 0.30 }));

test("every peer gets a real angle, for 1–40 peers and 0–6 spokes", () => {
  for (let k = 0; k <= 6; k++) {
    for (let n = 1; n <= 40; n++) {
      const angles = freeArcAngles(n, [HUB_LABEL, ...spokes(k)]);
      assert.equal(angles.length, n, `${n} peers, ${k} spokes`);
      for (const a of angles) assert.ok(Number.isFinite(a), `${n} peers, ${k} spokes: ${a}`);
    }
  }
});

test("four spokes and five peers: the case seen on the dashboard", () => {
  assert.equal(freeArcAngles(5, [HUB_LABEL, ...spokes(4)]).length, 5);
});

test("no peers, no angles", () => {
  assert.deepEqual(freeArcAngles(0, [HUB_LABEL]), []);
});

test("obstacles covering the whole ring fall back to an even spread", () => {
  const angles = freeArcAngles(3, [{ angle: 0, half: Math.PI }]);
  assert.equal(angles.length, 3);
  for (const a of angles) assert.ok(Number.isFinite(a));
});
