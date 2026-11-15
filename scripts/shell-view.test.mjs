// node --test scripts/shell-view.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { shellView, showBackToAdmin } from "../src/main/resources/META-INF/resources/js/shellView.js";

test("an admin on an admin route gets the admin chrome and no way-back pill", () => {
  assert.equal(shellView(true, "dashboard"), "admin");
  assert.equal(showBackToAdmin(true, "dashboard"), false);
});
test("an admin on /my-access gets the plain user chrome and the pill back", () => {
  assert.equal(shellView(true, "my-access"), "user");
  assert.equal(showBackToAdmin(true, "my-access"), true);
});
test("a non-admin never sees the admin chrome or the pill, on any route", () => {
  for (const r of ["my-access", "dashboard", "peers"]) {
    assert.equal(shellView(false, r), "user");
    assert.equal(showBackToAdmin(false, r), false);
  }
});
