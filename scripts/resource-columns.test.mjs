// node --test scripts/resource-columns.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { resourceColumns, maxRowsPerColumn } from "../src/main/resources/META-INF/resources/js/resourceColumns.js";

const max = maxRowsPerColumn(150, 32, 620, 24);

test("the first ring decides the column height, not the view cap", () => {
  assert.equal(max, 9);
});

test("17 resources (the company's office) become two columns, not one tall one", () => {
  assert.deepEqual(resourceColumns(17, max), { numCols: 2, rowsPerCol: 9 });
});

test("short lists stay one column; the cap of 24 is three columns", () => {
  assert.deepEqual(resourceColumns(1, max), { numCols: 1, rowsPerCol: 1 });
  assert.deepEqual(resourceColumns(9, max), { numCols: 1, rowsPerCol: 9 });
  assert.deepEqual(resourceColumns(10, max), { numCols: 2, rowsPerCol: 5 });
  assert.deepEqual(resourceColumns(24, max), { numCols: 3, rowsPerCol: 8 });
});

test("a small ring never yields zero rows", () => {
  assert.equal(maxRowsPerColumn(10, 32, 620, 24), 1);
  assert.deepEqual(resourceColumns(5, 0), { numCols: 5, rowsPerCol: 1 });
});
