// Zoom controls shared by the Atlas and the dashboard topology. Their view
// models differ (the Atlas transforms its content inside a fixed viewBox, the
// topology sizes the viewBox to its content), so only the step and the keys
// are shared — not the state.
//
// No wheel zoom anywhere: removed from the Atlas on 2026-08-11 as too twitchy
// on a trackpad, and the same holds for the topology. Buttons and keys are
// the way for everyone, touch included.

export const BUTTON_ZOOM_STEP = 1.6; // per +/- click or keypress

/** "in" | "out" | "reset" for a zoom key, null otherwise or while typing. */
export function zoomKey(evt) {
  const el = evt.target;
  const tag = (el && el.tagName) || "";
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || (el && el.isContentEditable)) return null;
  if (evt.ctrlKey || evt.metaKey || evt.altKey) return null;
  if (evt.key === "+" || evt.key === "=") return "in";
  if (evt.key === "-" || evt.key === "_") return "out";
  if (evt.key === "0") return "reset";
  return null;
}
