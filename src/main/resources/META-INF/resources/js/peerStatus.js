// Maps a Peer's server-computed `connectionStatus` (see
// PeerConnectionStatus.java) to a badge CSS class and an i18n key. Only
// meaningful when the peer is enabled — callers show the existing
// "Deaktiviert" badge instead when `p.enabled` is false.

export const CONNECTION_STATUS_BADGE = {
  CONNECTED: "badge-success",
  STALE: "badge-warning",
  DISCONNECTED: "badge-neutral",
};

export const CONNECTION_STATUS_LABEL_KEY = {
  CONNECTED: "peers.status_connected",
  STALE: "peers.status_stale",
  DISCONNECTED: "peers.status_disconnected",
};

export function connectionBadgeClass(p) {
  return CONNECTION_STATUS_BADGE[p.connectionStatus] || "badge-neutral";
}

export function connectionLabelKey(p) {
  return CONNECTION_STATUS_LABEL_KEY[p.connectionStatus] || "peers.status_disconnected";
}
