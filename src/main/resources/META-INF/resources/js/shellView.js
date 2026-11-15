// Which chrome the shell draws. The route decides, not hidden state, so reload
// and bookmarks land in the same view. Only /my-access is the user view; the
// server still guards every admin route (requiresAdmin), so this is navigation,
// never a permission.
export const USER_VIEW_ROUTE = "my-access";

/** "admin" = admin sidebar and banners; "user" = the portal chrome a plain user sees. */
export function shellView(isAdmin, routeName) {
  return isAdmin && routeName !== USER_VIEW_ROUTE ? "admin" : "user";
}

/** Only an admin sitting in the user view gets the pill that leads back. */
export function showBackToAdmin(isAdmin, routeName) {
  return isAdmin && shellView(isAdmin, routeName) === "user";
}
