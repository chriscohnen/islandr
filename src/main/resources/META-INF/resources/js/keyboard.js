// Shared keyboard-shortcut helpers. No central modal registry exists — each
// view owns its own dialog state — so these are small per-view bindings a
// view wires to its own close/action methods in mounted()/beforeUnmount(),
// rather than one global listener that would need to know every view's shape.

/** Escape closes the currently open modal. Call from mounted(), and call the
 * returned function from beforeUnmount() to remove the listener again. */
export function onEscape(closeFn) {
  const handler = (evt) => { if (evt.key === "Escape") closeFn(); };
  window.addEventListener("keydown", handler);
  return () => window.removeEventListener("keydown", handler);
}

function isTypingTarget(evt) {
  const el = evt.target;
  if (!el) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
}

/** "/" focuses the given search input, unless already typing somewhere else. */
export function onSlashFocus(inputRef) {
  const handler = (evt) => {
    if (evt.key !== "/" || isTypingTarget(evt)) return;
    const el = typeof inputRef === "function" ? inputRef() : inputRef;
    if (!el) return;
    evt.preventDefault();
    el.focus();
  };
  window.addEventListener("keydown", handler);
  return () => window.removeEventListener("keydown", handler);
}

/** Ctrl/Cmd+S triggers saveFn instead of the browser's save-page dialog. */
export function onSaveShortcut(saveFn) {
  const handler = (evt) => {
    if (!(evt.key === "s" || evt.key === "S") || !(evt.ctrlKey || evt.metaKey)) return;
    evt.preventDefault();
    saveFn();
  };
  window.addEventListener("keydown", handler);
  return () => window.removeEventListener("keydown", handler);
}
