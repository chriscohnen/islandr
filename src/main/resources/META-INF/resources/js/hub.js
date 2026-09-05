// hub.js — deployment facts every admin view needs but none of them owns.
//
// The WireGuard interface name is set once at deploy time via
// ISLANDR_WG_INTERFACE and never changes at runtime, but it appears in copy
// all over the Admin Console ("Import from wg0", "wg show wg0 public-key").
// Hardcoding "wg0" there is wrong on any hub deployed with another name, and
// wrong in the worst way: the text confidently names an interface that does
// not exist, so an operator following it runs the wrong command.
//
// Loaded once per page load, lazily, and shared reactively. "wg0" is the
// placeholder until the answer arrives — it is also the default the backend
// uses, so the window where it could be wrong is a single fetch long.

import { reactive } from "vue";

export const hub = reactive({ wgInterface: "wg0" });

let inflight = null;

export function loadHub() {
  if (!inflight) {
    inflight = fetch("/api/v1/settings")
      .then((r) => (r.ok ? r.json() : null))
      .then((s) => { if (s && s.wgInterface) hub.wgInterface = s.wgInterface; })
      .catch(() => { /* keep the default; the views must render regardless */ });
  }
  return inflight;
}
