import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import AtlasDiagram from "/js/AtlasDiagram.js";
import { Icon } from "/js/Icons.js";
import { onEscape } from "/js/keyboard.js";

const GRANTS_PAGE_SIZE = 20;

// Mirrors AtlasDiagram.js's edgeColor(kind) exactly — kept in sync by hand
// since it's five CSS var strings, not worth sharing a module for. Order
// here is the legend's display order, not load-bearing elsewhere.
const EDGE_KIND_LEGEND = [
  { kind: "role", color: "var(--accent)", labelKey: "atlas.legend_kind_role" },
  { kind: "type-grant", color: "var(--success-solid)", labelKey: "atlas.legend_kind_type_grant" },
  { kind: "user-direct", color: "var(--info-solid)", labelKey: "atlas.mode_direct" },
  { kind: "site-direct", color: "var(--warning-solid)", labelKey: "atlas.mode_direct_site" },
  { kind: "network-grant", color: "var(--danger-solid)", labelKey: "atlas.legend_kind_network_grant" },
];

export default defineComponent({
  name: "AtlasView",
  components: { AtlasDiagram, Icon },
  data() {
    return {
      graph: null,
      loading: false,
      error: null,
      tool: "grant",
      selectedRoleId: "", // "" = direct user-grant mode
      selectedUserId: null, // focused user (click-select in direct mode) — only their edges render
      selectedResourceId: null, // focused resource (click-select) — only edges reaching it render
      selectedPeerId: null, // focused site-gateway peer (ADR-0025 diagnostics target) — no edge filtering
      lang: locale.current,
      grantDialog: null, // { subjectType, subjectId, resourceId, subjectName, resourceName, kind, allPorts, portIds, ports }
      grantSaving: false,
      revokeConfirm: null, // { edge, userName, resourceName, roleLabel }
      revokeSaving: false,
      // Inclusive type filter, same semantics as the topology map's type
      // chips: empty set = show everything; a non-empty set shows only the
      // types in it (and, for edges, only what still touches a shown node).
      activeTypes: new Set(),
      // User filter is by role, not a per-user chip list — a chip per user
      // doesn't scale once a tenant has dozens of them. "" = show everyone.
      userFilterRoleId: "",
      grantsPage: 1,
      // Network diagnostics (ADR-0025): availability is fetched once so the
      // action can gray itself out instead of offering a probe that will just
      // fail; diagModal holds the live state of one open probe dialog.
      diagAvailability: null,
      diagModal: null, // { targetKind: "resource"|"peer", targetId, targetName, path, ping, pingLoading, pingError, tracepath, traceLoading, traceError }
      // Which mobile/roaming user currently has a connected client peer
      // (ADR-0025 follow-up: connected peers are pingable too, and Atlas
      // previously gave no visual way to tell who's actually online).
      // From GET /api/v1/peers/live: userId -> all of that user's currently
      // connected peers, newest handshake first. A user with two devices
      // connected (e.g. laptop + phone) still shows as a single dot on the
      // diagram — the dot means "this user is online", not "this device is"
      // — but every connected device of theirs must stay individually
      // pingable, so the full list is kept, not just the most recent one.
      connectedPeersByUserId: {},
      // Site-gateway (type=site) live peers, keyed by peer id — same
      // /api/v1/peers/live snapshot as connectedPeersByUserId above, just the
      // entries loadConnectedPeers used to skip. Drives the gateway diamond's
      // hover card and online dot in AtlasDiagram (issue: "hover cards for
      // site peers are still missing").
      gatewayLiveByPeerId: {},
    };
  },
  computed: {
    _lang() { return locale.current; },
    edgeKindLegend() {
      void this.lang;
      return EDGE_KIND_LEGEND.map((e) => ({ ...e, label: t(e.labelKey) }));
    },
    grantModeLabel() {
      void this.lang;
      if (!this.selectedRoleId) return t("atlas.mode_direct");
      const role = (this.graph && this.graph.roles || []).find((r) => r.id === this.selectedRoleId);
      return t("atlas.mode_role", { role: role ? role.name : this.selectedRoleId });
    },
    highlightedUserIds() {
      if (!this.selectedRoleId || !this.graph) return [];
      const ids = new Set();
      for (const e of this.graph.edges) {
        if (e.kind === "role" && e.roleId === this.selectedRoleId) ids.add(e.subjectId);
      }
      return Array.from(ids);
    },
    focusedUserName() {
      if (!this.selectedUserId || !this.graph) return "";
      const u = this.graph.users.find((u) => u.id === this.selectedUserId);
      return u ? u.name : this.selectedUserId;
    },
    focusedResourceName() {
      if (!this.selectedResourceId || !this.graph) return "";
      const r = this.graph.resources.find((r) => r.id === this.selectedResourceId);
      return r ? r.name : this.selectedResourceId;
    },
    focusedPeerName() {
      if (!this.selectedPeerId) return "";
      return (this._lastPeerClick && this._lastPeerClick.peerId === this.selectedPeerId)
          ? this._lastPeerClick.peerName : this.selectedPeerId;
    },
    // The focused user's currently-connected client peers, if any — the
    // diagnostics targets when pinging a mobile/roaming user (each peer's
    // own tunnel IP, same PeerResource endpoints the site-gateway diamond
    // already uses, just for a client device instead of a site). Newest
    // handshake first; every entry is individually pingable, not just [0].
    selectedUserConnectedPeers() {
      if (!this.selectedUserId) return [];
      return this.connectedPeersByUserId[this.selectedUserId] || [];
    },
    focusLabel() {
      if (this.selectedUserId) return t("atlas.focus_user", { user: this.focusedUserName || " " });
      if (this.selectedResourceId) return t("atlas.focus_resource", { resource: this.focusedResourceName || " " });
      if (this.selectedPeerId) return t("atlas.focus_peer", { peer: this.focusedPeerName || " " });
      return "";
    },
    // Drives the probed-path overlay on the diagram (ADR-0025 §5): the label
    // shown at the last segment's midpoint and whether it's drawn green or
    // red. Based on the ping result specifically — ping always runs first
    // when the dialog opens, so it's the one result guaranteed to exist as
    // soon as there's a path to draw at all.
    probeLabel() {
      const ping = this.diagModal && this.diagModal.ping;
      if (!ping) return "";
      return ping.reachable
          ? t("atlas.diagnostics_overlay_ms", { ms: ping.avgMs })
          : t("atlas.diagnostics_overlay_loss", { loss: ping.lossPercent });
    },
    probeReachable() {
      const ping = this.diagModal && this.diagModal.ping;
      return ping ? !!ping.reachable : true;
    },
    // True while the overlay is showing the client-synthesized path (below)
    // and no ping result has landed yet — drives a neutral/dashed "testing…"
    // stroke instead of jumping straight to the green "reachable" color
    // before anything has actually been measured.
    probePending() {
      return !!(this.diagModal && this.diagModal.pingLoading && !this.diagModal.ping);
    },
    stats() {
      if (!this.graph) return { sites: 0, devices: 0, users: 0, grants: 0 };
      return {
        sites: (this.graph.sites || []).length,
        devices: this.graph.resources.length,
        users: this.graph.users.length,
        grants: this.graph.edges.length,
      };
    },
    resourceTypeLabels() {
      void this.lang;
      return {
        computer: t("resources.type_computer"),
        router: t("resources.type_router"),
        printer: t("resources.type_printer"),
        nas: t("resources.type_nas"),
        camera: t("resources.type_camera"),
        iot: t("resources.type_iot"),
        "virt-host": t("resources.type_virt"),
        rackserver: t("resources.type_rackserver"),
        kvm: t("resources.type_kvm"),
        management: t("resources.type_mgmt"),
        other: t("resources.type_other"),
      };
    },
    // Every resource type actually present in the graph, with a count —
    // same shape the topology map's type-filter chips use — so the filter
    // never offers a type with nothing behind it.
    resourceTypes() {
      if (!this.graph) return [];
      const counts = new Map();
      for (const r of this.graph.resources) counts.set(r.type, (counts.get(r.type) || 0) + 1);
      return Array.from(counts.entries())
          .map(([key, count]) => ({ key, count, label: this.resourceTypeLabels[key] || key }))
          .sort((a, b) => a.label.localeCompare(b.label));
    },
    // memberUserIds comes straight from the backend's role-membership
    // resolution (same rule used for grant fan-out) — true membership, not
    // "has a grant via this role", which would miss a role with no grant yet.
    activeUserIds() {
      if (!this.userFilterRoleId || !this.graph) return [];
      const role = this.graph.roles.find((r) => r.id === this.userFilterRoleId);
      return role ? role.memberUserIds : [];
    },
    filtersActive() {
      return this.activeTypes.size > 0 || this.userFilterRoleId !== "";
    },
    // Same type/user-role filters as the diagram above, so the table below
    // it never contradicts what's currently shown on the graph. Also mirrors
    // the diagram's click-to-focus state (selectedUserId/selectedResourceId)
    // — clicking a peer on the graph is a strong "show me just this one"
    // signal, and the table previously ignored it entirely, still listing
    // every grant in the system underneath a graph now showing just one.
    grantsForTable() {
      if (!this.graph) return [];
      const usersById = Object.fromEntries(this.graph.users.map((u) => [u.id, u]));
      const sitesById = Object.fromEntries((this.graph.sites || []).map((s) => [s.id, s]));
      const resById = Object.fromEntries(this.graph.resources.map((r) => [r.id, r]));
      const typeActive = this.activeTypes.size > 0;
      // The user-role filter narrows which users' edges show — it has no
      // meaning for a site subject, so a site-direct edge is never excluded
      // by it (only the type filter applies to those).
      const userSet = this.activeUserIds.length > 0 ? new Set(this.activeUserIds) : null;
      return this.graph.edges
          .filter((e) => {
            // A network-grant edge has no resourceId at all — it isn't
            // scoped to a resource type, so the type filter simply doesn't
            // apply to it (same reasoning that would exempt any other
            // whole-network row from a per-type filter).
            if (typeActive && e.kind !== "network-grant") {
              const res = resById[e.resourceId];
              if (!res || !this.activeTypes.has(res.type)) return false;
            }
            if (userSet && e.subjectType === "user" && !userSet.has(e.subjectId)) return false;
            // Click-to-focus, same rule the diagram's own edgeLines() applies:
            // a selected user narrows to only their edges (and, for a
            // network-grant edge specifically, only shows it while its own
            // granted user is the one selected — never unconditionally);
            // a selected resource narrows to only edges reaching it.
            if (this.selectedUserId) {
              if (!(e.subjectType === "user" && e.subjectId === this.selectedUserId)) return false;
            } else if (this.selectedResourceId) {
              if (e.resourceId !== this.selectedResourceId) return false;
            }
            return true;
          })
          .map((e) => {
            const subjectName = e.subjectType === "site"
                ? t("atlas.subject_site", { site: (sitesById[e.subjectId] || {}).name || e.subjectId })
                : (usersById[e.subjectId] || {}).name || e.subjectId;
            // A network-grant edge targets a whole site (via siteId), not a
            // single resource (resourceId is always null for this kind) —
            // show the site's name with a "whole network" label instead of
            // an empty cell.
            const resourceName = e.kind === "network-grant"
                ? t("atlas.resource_whole_network", { site: (sitesById[e.siteId] || {}).name || e.siteId })
                : (resById[e.resourceId] || {}).name || e.resourceId;
            return {
              // resourceId is null for every network-grant edge, so siteId
              // must be part of the key too — otherwise one user holding
              // network grants on two different sites via the same role
              // would produce two rows with an identical key.
              key: e.subjectType + "|" + e.subjectId + "|" + e.resourceId + "|" + e.siteId + "|" + e.kind + "|" + (e.roleId || ""),
              userName: subjectName,
              resourceName,
              roleName: e.kind === "user-direct" ? t("atlas.mode_direct")
                  : e.kind === "site-direct" ? t("atlas.mode_direct_site")
                  : e.roleName,
              portsLabel: e.allPorts ? t("acl.picker_all") : e.portLabels.join(", "),
            };
          });
    },
    grantsPageCount() {
      return Math.max(1, Math.ceil(this.grantsForTable.length / GRANTS_PAGE_SIZE));
    },
    grantsPageClamped() {
      return Math.min(this.grantsPage, this.grantsPageCount);
    },
    pagedGrantsForTable() {
      const start = (this.grantsPageClamped - 1) * GRANTS_PAGE_SIZE;
      return this.grantsForTable.slice(start, start + GRANTS_PAGE_SIZE);
    },
    grantsPageInfo() {
      return t("atlas.grants_page_info", { page: this.grantsPageClamped, total: this.grantsPageCount });
    },
  },
  watch: {
    // Picking a role from the dropdown shifts intent to role-mode — drop any
    // active user focus so the two selection concepts never coexist visibly.
    selectedRoleId(newVal) {
      if (newVal) { this.selectedUserId = null; this.selectedResourceId = null; }
    },
    // A changed filter can shrink the result set below the current page —
    // back to page 1 rather than landing on an empty page.
    activeTypes() { this.grantsPage = 1; },
    userFilterRoleId() { this.grantsPage = 1; },
  },
  async mounted() {
    await this.load();
    this.loadDiagAvailability();
    this.loadConnectedPeers();
    this._offEscape = onEscape(() => {
      if (this.diagModal) this.closeDiagnostics();
      else if (this.grantDialog) this.cancelGrantDialog();
      else if (this.revokeConfirm) this.cancelRevokeConfirm();
      else if (this.selectedUserId || this.selectedResourceId || this.selectedPeerId) this.clearFocus();
    });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    // Clicking a user node: while a role is active, clicking someone who
    // does NOT hold that role switches the whole toolbar to direct mode and
    // focuses them (an admin trying to drag from a non-member gets redirected
    // to the mode that actually applies to that person). While already in
    // direct mode, a click simply toggles focus on that one user.
    onUserClick(userId) {
      if (this.selectedRoleId) {
        if (!this.highlightedUserIds.includes(userId)) {
          this.selectedRoleId = "";
          this.selectedResourceId = null;
          this.selectedPeerId = null;
          this.selectedUserId = userId;
        }
        return;
      }
      this.selectedResourceId = null;
      this.selectedPeerId = null;
      this.selectedUserId = this.selectedUserId === userId ? null : userId;
    },

    // Clicking a resource focuses it the same way a user click does, just
    // the mirror direction: show who/what can reach this one resource
    // instead of what this one user can reach. Mutually exclusive with role
    // filtering and user focus — a resource focus replaces both.
    onResourceClick(resourceId) {
      this.selectedRoleId = "";
      this.selectedUserId = null;
      this.selectedPeerId = null;
      this.selectedResourceId = this.selectedResourceId === resourceId ? null : resourceId;
    },

    // Clicking a site's gateway diamond (ADR-0025) — only fires when the
    // site actually has a gateway peer (AtlasDiagram no-ops otherwise).
    // Its own focus mode, mutually exclusive with user/resource focus but
    // deliberately NOT touching the role picker or edge filtering — picking
    // a peer to ping is not a grant-graph question.
    onPeerClick({ peerId, peerName, siteName }) {
      this.selectedUserId = null;
      this.selectedResourceId = null;
      this.selectedPeerId = this.selectedPeerId === peerId ? null : peerId;
      this._lastPeerClick = { peerId, peerName, siteName };
    },

    clearFocus() {
      this.selectedUserId = null;
      this.selectedResourceId = null;
      this.selectedPeerId = null;
    },

    toggleTypeFilter(type) {
      const next = new Set(this.activeTypes);
      if (next.has(type)) next.delete(type); else next.add(type);
      this.activeTypes = next;
    },
    clearTypeFilter() {
      this.activeTypes = new Set();
    },
    clearAllFilters() {
      this.activeTypes = new Set();
      this.userFilterRoleId = "";
    },

    grantsPrevPage() {
      if (this.grantsPageClamped > 1) this.grantsPage = this.grantsPageClamped - 1;
    },
    grantsNextPage() {
      if (this.grantsPageClamped < this.grantsPageCount) this.grantsPage = this.grantsPageClamped + 1;
    },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/acl/atlas");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.graph = await res.json();
      } catch (e) {
        this.error = t("atlas.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    async onDragGrant({ subjectType, subjectId, resourceId }) {
      const resource = this.graph.resources.find((r) => r.id === resourceId);
      if (!resource) return;
      // A site drag always creates a direct site-grant — sites don't hold
      // roles in this design, so there's no role-mode equivalent for them
      // the way a user drag has (role vs. direct, depending on selectedRoleId).
      if (subjectType === "site") {
        const site = (this.graph.sites || []).find((s) => s.id === subjectId);
        if (!site) return;
        const existingFull = this.graph.edges.some((e) =>
            e.subjectType === "site" && e.subjectId === subjectId && e.resourceId === resourceId
                && e.allPorts && e.kind === "site-direct");
        if (existingFull) {
          this.error = t("atlas.grant_already_full", { user: site.name, resource: resource.name });
          return;
        }
        let ports = [];
        try {
          const res = await fetch("/api/v1/resources/" + resourceId);
          if (res.ok) { const full = await res.json(); ports = full.ports || []; }
        } catch { /* dialog still opens, port list just stays empty */ }
        this.grantDialog = {
          subjectType: "site", subjectId, resourceId,
          subjectName: site.name, resourceName: resource.name,
          kind: "site-direct", allPorts: true, portIds: [], ports,
        };
        return;
      }

      const user = this.graph.users.find((u) => u.id === subjectId);
      if (!user) return;
      const kind = this.selectedRoleId ? "role" : "user-direct";
      const existingFull = this.graph.edges.some((e) =>
          e.subjectType === "user" && e.subjectId === subjectId && e.resourceId === resourceId && e.allPorts &&
          (kind === "role" ? (e.kind === "role" && e.roleId === this.selectedRoleId) : e.kind === "user-direct"));
      if (existingFull) {
        this.error = t("atlas.grant_already_full", { user: user.name, resource: resource.name });
        return;
      }
      let ports = [];
      try {
        const res = await fetch("/api/v1/resources/" + resourceId);
        if (res.ok) {
          const full = await res.json();
          ports = full.ports || [];
        }
      } catch { /* dialog still opens, port list just stays empty */ }
      this.grantDialog = {
        subjectType: "user", subjectId, resourceId,
        subjectName: user.name,
        resourceName: resource.name,
        kind,
        allPorts: true,
        portIds: [],
        ports,
      };
    },

    cancelGrantDialog() {
      this.grantDialog = null;
    },

    async confirmGrantDialog() {
      if (!this.grantDialog) return;
      this.grantSaving = true;
      this.error = null;
      try {
        const d = this.grantDialog;
        const url = d.kind === "role" ? "/api/v1/acl/matrix"
            : d.kind === "site-direct" ? "/api/v1/acl/site-grants"
            : "/api/v1/acl/user-grants";
        const body = d.kind === "role"
            ? { grants: [{ roleId: this.selectedRoleId, resourceId: d.resourceId, allPorts: d.allPorts, portIds: d.allPorts ? [] : d.portIds }] }
            : d.kind === "site-direct"
            ? { siteId: d.subjectId, resourceId: d.resourceId, allPorts: d.allPorts, portIds: d.allPorts ? [] : d.portIds }
            : { userId: d.subjectId, resourceId: d.resourceId, allPorts: d.allPorts, portIds: d.allPorts ? [] : d.portIds };
        const res = await fetch(url, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        this.grantDialog = null;
        await this.load();
      } catch (e) {
        this.error = t("atlas.error_grant", { error: e.message });
      } finally {
        this.grantSaving = false;
      }
    },

    onRevokeEdge(edge) {
      const resource = this.graph.resources.find((r) => r.id === edge.resourceId);
      const resourceName = resource ? resource.name : edge.resourceId;
      let subjectName;
      if (edge.subjectType === "site") {
        const site = (this.graph.sites || []).find((s) => s.id === edge.subjectId);
        subjectName = t("atlas.subject_site", { site: site ? site.name : edge.subjectId });
      } else {
        const user = this.graph.users.find((u) => u.id === edge.subjectId);
        subjectName = user ? user.name : edge.subjectId;
      }
      if (edge.kind === "type-grant") {
        this.openTypeGrantRevokeConfirm(edge, resource, subjectName);
        return;
      }
      if (edge.kind === "network-grant") {
        this.error = t("atlas.revoke_network_grant_blocked");
        return;
      }
      const roleLabel = edge.kind === "role" ? edge.roleName
          : edge.kind === "site-direct" ? t("atlas.mode_direct_site")
          : t("atlas.mode_direct");
      this.revokeConfirm = { edge, userName: subjectName, resourceName, roleLabel };
    },

    // A type-grant edge isn't a single (role, resource) row to delete — the
    // click always means "revoke the whole type-grant" (every resource of
    // this type in this site, for this role), so look up the actual
    // RoleResourceTypeGrant row behind the edge and confirm that instead.
    async openTypeGrantRevokeConfirm(edge, resource, subjectName) {
      if (!resource) {
        this.error = t("atlas.revoke_type_grant_blocked");
        return;
      }
      try {
        const res = await fetch("/api/v1/acl/type-grants");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const typeGrants = await res.json();
        const match = typeGrants.find(
            (g) => g.roleId === edge.roleId && g.siteId === resource.siteId && g.resourceType === resource.type
        );
        if (!match) {
          this.error = t("atlas.revoke_type_grant_blocked");
          return;
        }
        this.revokeConfirm = {
          edge,
          isTypeGrant: true,
          typeGrantId: match.id,
          userName: subjectName,
          roleLabel: edge.roleName,
          typeLabel: this.resourceTypeLabels[resource.type] || resource.type,
          siteName: match.siteName,
        };
      } catch (e) {
        this.error = t("atlas.error_revoke", { error: e.message });
      }
    },

    cancelRevokeConfirm() {
      this.revokeConfirm = null;
    },

    async confirmRevokeEdge() {
      if (!this.revokeConfirm) return;
      if (this.revokeConfirm.isTypeGrant) {
        await this.confirmRevokeTypeGrant();
        return;
      }
      const edge = this.revokeConfirm.edge;
      this.revokeSaving = true;
      this.error = null;
      try {
        const url = edge.kind === "role" ? "/api/v1/acl/matrix"
            : edge.kind === "site-direct" ? "/api/v1/acl/site-grants"
            : "/api/v1/acl/user-grants";
        const body = edge.kind === "role"
            ? { grants: [{ roleId: edge.roleId, resourceId: edge.resourceId, allPorts: false, portIds: [] }] }
            : edge.kind === "site-direct"
            ? { siteId: edge.subjectId, resourceId: edge.resourceId, allPorts: false, portIds: [] }
            : { userId: edge.subjectId, resourceId: edge.resourceId, allPorts: false, portIds: [] };
        const res = await fetch(url, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        this.revokeConfirm = null;
        await this.load();
      } catch (e) {
        this.error = t("atlas.error_revoke", { error: e.message });
      } finally {
        this.revokeSaving = false;
      }
    },

    async confirmRevokeTypeGrant() {
      const typeGrantId = this.revokeConfirm.typeGrantId;
      this.revokeSaving = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/acl/type-grants/" + typeGrantId, { method: "DELETE" });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        this.revokeConfirm = null;
        await this.load();
      } catch (e) {
        this.error = t("atlas.error_revoke", { error: e.message });
      } finally {
        this.revokeSaving = false;
      }
    },

    // ── Network diagnostics (ADR-0025) — admin-triggered ping/path-latency probe ──

    async loadDiagAvailability() {
      try {
        const res = await fetch("/api/v1/diagnostics/availability");
        if (res.ok) this.diagAvailability = await res.json();
      } catch { /* action just stays enabled; the probe itself will report the real error */ }
    },

    // Who's actually connected right now, so the "mobile/roaming" circle can
    // show it and a connected user's own peer becomes a diagnostics target —
    // same idea as the site-gateway peer, just for a client device instead of
    // a site. A snapshot at load time, not live-refreshed — good enough to
    // decide "is this worth pinging", same one-shot posture as the rest of
    // this dialog (ADR-0025 §6, no persisted/live history in v1).
    async loadConnectedPeers() {
      try {
        const res = await fetch("/api/v1/peers/live");
        if (!res.ok) return;
        const live = await res.json();
        const byUser = {};
        const gatewayById = {};
        for (const p of live) {
          if (p.type === "site") {
            if (p.id) gatewayById[p.id] = p; // site peers: gateway hover card, not the user map below
            continue;
          }
          if (!p.userId) continue;
          (byUser[p.userId] || (byUser[p.userId] = [])).push(p);
        }
        for (const peers of Object.values(byUser)) {
          peers.sort((a, b) => new Date(b.lastHandshake) - new Date(a.lastHandshake));
        }
        this.connectedPeersByUserId = byUser;
        this.gatewayLiveByPeerId = gatewayById;
      } catch { /* the mobile circle just shows everyone as "unknown", no worse than before this existed */ }
    },

    pathHopLabel(hop) {
      if (hop.kind === "hub") return t("atlas.diagnostics_hop_hub");
      if (hop.kind === "site-gateway") return t("atlas.diagnostics_hop_gateway", { peer: hop.name, site: hop.detail });
      if (hop.kind === "peer") return hop.name; // the peer itself is the destination — no further detail to show
      return hop.name + (hop.detail ? " (" + hop.detail + ")" : "");
    },

    tracepathHopLabel(hop) {
      return hop.host
          ? t("atlas.diagnostics_hop_row", { ttl: hop.ttl, host: hop.host, ms: hop.ms != null ? hop.ms : "?" })
          : t("atlas.diagnostics_hop_no_reply", { ttl: hop.ttl });
    },

    // Deterministic hub -> [site-gateway] -> resource chain — mirrors
    // ResourceResource.resolveDiagnosticsPath() exactly, but computed here
    // from data already in `graph` instead of waiting on the server. The
    // route itself never depends on the probe's outcome (only reachability/
    // latency do), so there's nothing to actually wait for before drawing
    // it — this is what lets the overlay line appear the instant the dialog
    // opens instead of only after the first ping response lands.
    pathHopsForResource(resourceId) {
      const resource = this.graph.resources.find((r) => r.id === resourceId);
      if (!resource) return null;
      const path = [{ kind: "hub", id: null, name: t("atlas.diagnostics_hop_hub"), detail: null }];
      const site = (this.graph.sites || []).find((s) => s.id === resource.siteId);
      if (site && site.gatewayPeerId) {
        path.push({ kind: "site-gateway", id: site.gatewayPeerId, name: site.gatewayPeerName, detail: site.name });
      }
      path.push({ kind: "resource", id: resource.id, name: resource.name, detail: resource.ip });
      return path;
    },

    // Same idea for a direct peer probe (a site's gateway peer, or a
    // connected user's own client peer) — mirrors PeerResource's own
    // hub -> peer chain.
    pathHopsForPeer(peerId, peerName) {
      return [
        { kind: "hub", id: null, name: t("atlas.diagnostics_hop_hub"), detail: null },
        { kind: "peer", id: peerId, name: peerName || peerId, detail: null },
      ];
    },

    // targetKind selects the endpoint base — /api/v1/resources/{id} for a
    // Resource, /api/v1/peers/{id} for a site's gateway peer probed directly
    // (ADR-0025). Same modal, same ping/tracepath UI either way.
    openDiagnostics(resourceId) {
      const resource = this.graph.resources.find((r) => r.id === resourceId);
      if (!resource) return;
      this.diagModal = {
        targetKind: "resource", targetId: resourceId, targetName: resource.name,
        path: this.pathHopsForResource(resourceId), ping: null, pingLoading: true, pingError: null,
        tracepath: null, traceLoading: false, traceError: null,
        mtr: null, mtrLoading: false, mtrError: null,
      };
      this.runPing();
    },

    openDiagnosticsForPeer(peerId, peerName) {
      this.diagModal = {
        targetKind: "peer", targetId: peerId, targetName: peerName || peerId,
        path: this.pathHopsForPeer(peerId, peerName), ping: null, pingLoading: true, pingError: null,
        tracepath: null, traceLoading: false, traceError: null,
        mtr: null, mtrLoading: false, mtrError: null,
      };
      this.runPing();
    },

    closeDiagnostics() {
      this.diagModal = null;
    },

    diagBaseUrl(m) {
      return m.targetKind === "peer" ? "/api/v1/peers/" + m.targetId : "/api/v1/resources/" + m.targetId;
    },

    async runPing() {
      if (!this.diagModal) return;
      const m = this.diagModal;
      m.pingLoading = true;
      m.pingError = null;
      try {
        const res = await fetch(this.diagBaseUrl(m) + "/diagnostics/ping", { method: "POST" });
        if (!res.ok) {
          const body = await res.text();
          throw new Error(body || ("HTTP " + res.status));
        }
        const data = await res.json();
        m.ping = data;
        m.path = data.path;
      } catch (e) {
        m.pingError = t("atlas.diagnostics_error", { error: e.message });
      } finally {
        m.pingLoading = false;
      }
    },

    async runTracepath() {
      if (!this.diagModal) return;
      const m = this.diagModal;
      m.traceLoading = true;
      m.traceError = null;
      try {
        const res = await fetch(this.diagBaseUrl(m) + "/diagnostics/tracepath", { method: "POST" });
        if (!res.ok) {
          const body = await res.text();
          throw new Error(body || ("HTTP " + res.status));
        }
        const data = await res.json();
        m.tracepath = data;
        m.path = data.path;
      } catch (e) {
        m.traceError = t("atlas.diagnostics_error", { error: e.message });
      } finally {
        m.traceLoading = false;
      }
    },

    async runMtr() {
      if (!this.diagModal) return;
      const m = this.diagModal;
      m.mtrLoading = true;
      m.mtrError = null;
      try {
        const res = await fetch(this.diagBaseUrl(m) + "/diagnostics/mtr", { method: "POST" });
        if (!res.ok) {
          const body = await res.text();
          throw new Error(body || ("HTTP " + res.status));
        }
        const data = await res.json();
        m.mtr = data;
        m.path = data.path;
      } catch (e) {
        m.mtrError = t("atlas.diagnostics_error", { error: e.message });
      } finally {
        m.mtrLoading = false;
      }
    },
  },
  template: `
    <div class="page-header" style="display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: var(--space-3)">
      <h1>{{ t('atlas.title') }}</h1>
      <div v-if="graph" style="display: flex; gap: var(--space-5)">
        <div v-for="s in [
          { label: t('atlas.stat_sites'), value: stats.sites },
          { label: t('atlas.stat_devices'), value: stats.devices },
          { label: t('atlas.stat_users'), value: stats.users },
          { label: t('atlas.stat_grants'), value: stats.grants, hint: t('atlas.stat_grants_hint') },
        ]" :key="s.label" style="text-align: center" :title="s.hint || ''">
          <div style="font-size: var(--text-xl); font-weight: 700; line-height: 1">{{ s.value }}</div>
          <div class="muted" style="font-size: var(--text-xs); text-transform: uppercase; letter-spacing: 0.05em">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <p class="muted" style="font-size: var(--text-sm); margin: 0 0 var(--space-3)">
      {{ t('atlas.legend_hint') }} {{ t('atlas.legend_connected_hint') }}
    </p>

    <!-- Type/user filter chips — same inclusive-filter pattern as the topology
         map's type row (empty selection = show everything; picking one or
         more chips narrows to just those). Unlike the topology map, Atlas
         also gets a user row, plus one combined reset once either is active. -->
    <div v-if="graph && resourceTypes.length > 0" style="display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-2); font-family: var(--font-sans)">
      <button @click="clearTypeFilter"
              :class="['btn', 'btn-sm', activeTypes.size === 0 ? 'btn-secondary' : 'btn-ghost']"
              style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
        {{ t('topology.filter_all') }}
        <span style="font-family: var(--font-mono); opacity: 0.6">{{ graph.resources.length }}</span>
      </button>
      <button v-for="ty in resourceTypes" :key="ty.key" @click="toggleTypeFilter(ty.key)"
              :class="['btn', 'btn-sm', activeTypes.has(ty.key) ? 'btn-secondary' : 'btn-ghost']"
              style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
        <Icon :name="ty.key" :size="13" />
        {{ ty.label }}
        <span style="font-family: var(--font-mono); opacity: 0.6">{{ ty.count }}</span>
      </button>
    </div>

    <div v-if="graph && graph.users.length > 0" style="display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap">
      <select class="select" v-model="userFilterRoleId" style="max-width: 220px">
        <option value="">{{ t('atlas.filter_all_users') }}</option>
        <option v-for="r in graph.roles" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select>
      <button v-if="filtersActive" class="btn btn-ghost btn-sm" @click="clearAllFilters">
        <Icon name="trash" :size="13" /> {{ t('atlas.filters_clear') }}
      </button>
    </div>

    <div style="display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap">
      <select class="select" v-model="selectedRoleId" style="max-width: 260px">
        <option value="">{{ t('atlas.pick_role') }}</option>
        <option v-for="r in (graph ? graph.roles : [])" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select>
      <span class="muted" style="font-size: var(--text-sm)">{{ grantModeLabel }}</span>

      <!-- Always rendered (never v-if) and only visibility-toggled: this row
           can wrap onto two lines on narrow viewports, and an appearing/
           disappearing element here would shift the diagram below by a full
           line — including mid-interaction, right between two clicks on the
           same user node (select, then click again to deselect), landing
           the second click on whatever node the page reflow put under the
           still-stationary cursor instead of the one the admin meant. -->
      <span class="badge" :style="{ display: 'flex', alignItems: 'center', gap: '6px', visibility: (selectedUserId || selectedResourceId || selectedPeerId) ? 'visible' : 'hidden' }">
        {{ focusLabel || ' ' }}
        <button class="btn btn-ghost btn-sm" style="padding: 0 4px" @click="clearFocus" :aria-label="t('atlas.focus_clear')" :title="t('atlas.focus_clear')" :tabindex="(selectedUserId || selectedResourceId || selectedPeerId) ? 0 : -1">✕</button>
      </span>

      <!-- ADR-0025: a probe target is always a focused, known Resource, the
           site's own gateway peer, or a connected user's own client peer —
           never free text. Grayed out (not hidden) with an actionable title
           when ping itself is missing on the hub — same "degrade honestly"
           posture as the DNS resolver's own status page. A focused user with
           no *currently connected* peer gets no action at all — there is
           nothing reachable to probe. A user with exactly one connected
           device keeps the single generic button; two or more (e.g. laptop
           + phone both online) get one button each, named by device, so
           every one of them stays individually pingable instead of only
           ever reaching the most-recently-handshook device. -->
      <button v-if="selectedResourceId || selectedPeerId" class="btn btn-ghost btn-sm"
              :disabled="diagAvailability && !diagAvailability.ping"
              :title="diagAvailability && !diagAvailability.ping ? t('atlas.diagnostics_unavailable', { tool: 'ping' }) : ''"
              @click="selectedPeerId ? openDiagnosticsForPeer(selectedPeerId, focusedPeerName)
                    : openDiagnostics(selectedResourceId)">
        <Icon name="activity" :size="14" /> {{ t('atlas.diagnostics_action') }}
      </button>
      <button v-else-if="selectedUserConnectedPeers.length === 1" class="btn btn-ghost btn-sm"
              :disabled="diagAvailability && !diagAvailability.ping"
              :title="diagAvailability && !diagAvailability.ping ? t('atlas.diagnostics_unavailable', { tool: 'ping' }) : ''"
              @click="openDiagnosticsForPeer(selectedUserConnectedPeers[0].id, focusedUserName)">
        <Icon name="activity" :size="14" /> {{ t('atlas.diagnostics_action') }}
      </button>
      <template v-else-if="selectedUserConnectedPeers.length > 1">
        <button v-for="peer in selectedUserConnectedPeers" :key="peer.id" class="btn btn-ghost btn-sm"
                :disabled="diagAvailability && !diagAvailability.ping"
                :title="diagAvailability && !diagAvailability.ping ? t('atlas.diagnostics_unavailable', { tool: 'ping' }) : ''"
                @click="openDiagnosticsForPeer(peer.id, peer.name || peer.id)">
          <Icon name="activity" :size="14" /> {{ t('atlas.diagnostics_action_device', { device: peer.name || peer.id }) }}
        </button>
      </template>

      <div style="display: flex; gap: var(--space-2); margin-left: auto">
        <button class="btn btn-sm" :class="tool === 'grant' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'grant'">
          <Icon name="link" :size="14" /> {{ t('atlas.tool_grant') }}
        </button>
        <button class="btn btn-sm" :class="tool === 'revoke' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'revoke'">
          <Icon name="unlink" :size="14" /> {{ t('atlas.tool_revoke') }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="graph && graph.users.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_users') }}</p>
    </div>

    <div v-else-if="graph && graph.resources.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_resources') }}</p>
    </div>

    <template v-else-if="graph">
      <div class="card card-pad" style="position: relative">
        <AtlasDiagram :graph="graph" :tool="tool" :highlighted-user-ids="highlightedUserIds" :selected-user-id="selectedUserId" :selected-resource-id="selectedResourceId"
                       :selected-peer-id="selectedPeerId"
                       :connected-user-ids="Object.keys(connectedPeersByUserId)"
                       :connected-peers-by-user-id="connectedPeersByUserId"
                       :connected-gateway-peer-ids="Object.keys(gatewayLiveByPeerId)"
                       :gateway-peer-live-by-id="gatewayLiveByPeerId"
                       :hub-ip4="graph.hubIp4" :hub-ip6="graph.hubIp6"
                       :active-types="Array.from(activeTypes)" :active-user-ids="Array.from(activeUserIds)"
                       :probe-path="diagModal ? diagModal.path : null" :probe-label="probeLabel" :probe-reachable="probeReachable"
                       :probe-pending="probePending"
                       @drag-grant="onDragGrant" @revoke-edge="onRevokeEdge" @user-click="onUserClick" @resource-click="onResourceClick" @peer-click="onPeerClick" />

        <!-- Network diagnostics (ADR-0025): docked beside the graph, not a modal —
             the whole point is seeing the probed hub -> [site-gateway] -> target
             chain highlighted live on the diagram (AtlasDiagram's probe overlay)
             at the same time as the numbers, not one hidden behind the other.
             No backdrop: the graph underneath stays interactive. -->
        <div v-if="diagModal" class="card" style="position: absolute; top: var(--space-3); right: var(--space-3); width: 300px;
                    max-height: calc(70vh - var(--space-3) * 2); overflow-y: auto; z-index: 5; padding: var(--space-3);
                    box-shadow: var(--shadow-lg, 0 8px 24px rgba(0,0,0,0.18))">
          <div style="display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); margin-bottom: var(--space-3)">
            <strong style="font-size: var(--text-sm)">{{ t('atlas.diagnostics_title', { resource: diagModal.targetName }) }}</strong>
            <button class="btn btn-ghost btn-sm" style="padding: 0 4px; flex-shrink: 0" @click="closeDiagnostics">✕</button>
          </div>

          <div v-if="diagModal.path" style="margin-bottom: var(--space-3)">
            <h3 style="margin: 0 0 4px; font-size: 10px; font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.05em">
              {{ t('atlas.diagnostics_path_title') }}
            </h3>
            <div class="mono" style="font-size: var(--text-xs)">
              <span v-for="(hop, i) in diagModal.path" :key="i">
                <span v-if="i > 0"> → </span>{{ pathHopLabel(hop) }}
              </span>
            </div>
          </div>

          <div style="margin-bottom: var(--space-3)">
            <h3 style="margin: 0 0 4px; font-size: 10px; font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.05em; display: flex; align-items: center; gap: 6px">
              {{ t('atlas.diagnostics_ping_title') }}
              <button class="btn btn-ghost btn-sm" style="padding: 2px; height: auto; text-transform: none; letter-spacing: 0"
                      :disabled="diagModal.pingLoading" :title="t('atlas.diagnostics_rerun')" @click="runPing">
                <Icon name="rotate" :size="12" />
              </button>
            </h3>
            <div v-if="diagModal.pingLoading" class="muted" style="font-size: var(--text-xs)">{{ t('common.loading') }}</div>
            <div v-else-if="diagModal.pingError" class="error-banner" style="font-size: var(--text-xs)">{{ diagModal.pingError }}</div>
            <div v-else-if="diagModal.ping">
              <p style="margin: 0 0 4px">
                <span :class="['badge', 'status-pill', diagModal.ping.reachable ? 'badge-success' : 'badge-danger']">
                  <Icon :name="diagModal.ping.reachable ? 'check' : 'unlink'" :size="12" />
                  {{ diagModal.ping.reachable ? t('atlas.diagnostics_reachable') : t('atlas.diagnostics_unreachable') }}
                </span>
              </p>
              <p class="mono" style="margin: 0; font-size: var(--text-xs)">
                {{ t('atlas.diagnostics_stats', { received: diagModal.ping.received, sent: diagModal.ping.sent, loss: diagModal.ping.lossPercent }) }}
              </p>
              <p v-if="diagModal.ping.reachable" class="mono muted" style="margin: 2px 0 0; font-size: var(--text-xs)">
                {{ t('atlas.diagnostics_rtt', { min: diagModal.ping.minMs, avg: diagModal.ping.avgMs, max: diagModal.ping.maxMs }) }}
              </p>
            </div>
          </div>

          <div style="margin-bottom: var(--space-3)">
            <h3 style="margin: 0 0 4px; font-size: 10px; font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.05em; display: flex; align-items: center; gap: 6px">
              {{ t('atlas.diagnostics_tracepath_title') }}
              <button v-if="diagModal.tracepath" class="btn btn-ghost btn-sm" style="padding: 2px; height: auto; text-transform: none; letter-spacing: 0"
                      :disabled="diagModal.traceLoading" :title="t('atlas.diagnostics_rerun')" @click="runTracepath">
                <Icon name="rotate" :size="12" />
              </button>
            </h3>
            <div v-if="diagModal.traceLoading" class="muted" style="font-size: var(--text-xs)">{{ t('common.loading') }}</div>
            <template v-else>
              <div v-if="diagModal.traceError" class="error-banner" style="font-size: var(--text-xs); margin-bottom: var(--space-2)">{{ diagModal.traceError }}</div>
              <ul v-if="diagModal.tracepath" class="mono" style="margin: 0; padding-left: var(--space-4); font-size: var(--text-xs)">
                <li v-for="hop in diagModal.tracepath.hops" :key="hop.ttl">{{ tracepathHopLabel(hop) }}</li>
              </ul>
              <!-- Not v-else: same "don't dead-end on the error" fix as mtr below. -->
              <button v-if="!diagModal.tracepath" class="btn btn-ghost btn-sm"
                      :disabled="diagAvailability && !diagAvailability.tracepath"
                      :title="diagAvailability && !diagAvailability.tracepath ? t('atlas.diagnostics_unavailable', { tool: 'tracepath' }) : ''"
                      @click="runTracepath">
                {{ diagModal.traceError ? t('atlas.diagnostics_retry') : t('atlas.diagnostics_run_tracepath') }}
              </button>
            </template>
          </div>

          <!-- mtr (ADR-0025 §1): opportunistic upgrade over tracepath — per-hop
               loss % and aggregated RTT over several cycles, not just one shot.
               Only offered when actually detected on the hub; tracepath above
               always stays available as the baseline either way. -->
          <div v-if="diagAvailability && diagAvailability.mtr">
            <h3 style="margin: 0 0 4px; font-size: 10px; font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.05em; display: flex; align-items: center; gap: 6px">
              {{ t('atlas.diagnostics_mtr_title') }}
              <button v-if="diagModal.mtr" class="btn btn-ghost btn-sm" style="padding: 2px; height: auto; text-transform: none; letter-spacing: 0"
                      :disabled="diagModal.mtrLoading" :title="t('atlas.diagnostics_rerun')" @click="runMtr">
                <Icon name="rotate" :size="12" />
              </button>
            </h3>
            <div v-if="diagModal.mtrLoading" class="muted" style="font-size: var(--text-xs)">{{ t('common.loading') }}</div>
            <template v-else>
              <div v-if="diagModal.mtrError" class="error-banner" style="font-size: var(--text-xs); margin-bottom: var(--space-2)">{{ diagModal.mtrError }}</div>
              <table v-if="diagModal.mtr" class="table" style="font-size: var(--text-xs)">
                <thead>
                  <tr>
                    <th>{{ t('atlas.diagnostics_mtr_th_hop') }}</th>
                    <th>{{ t('atlas.diagnostics_mtr_th_loss') }}</th>
                    <th>{{ t('atlas.diagnostics_mtr_th_avg') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="hop in diagModal.mtr.hops" :key="hop.ttl">
                    <td class="mono">{{ hop.ttl }}. {{ hop.host || t('atlas.diagnostics_hop_no_reply_short') }}</td>
                    <td class="mono">{{ hop.lossPercent }}%</td>
                    <td class="mono">{{ hop.avgMs != null ? hop.avgMs + ' ms' : '—' }}</td>
                  </tr>
                </tbody>
              </table>
              <!-- Not v-else: a failed attempt (e.g. the shared per-target cooldown
                   rejecting a too-quick retry) must still offer another try, not
                   dead-end on the error banner forever. -->
              <button v-if="!diagModal.mtr" class="btn btn-ghost btn-sm" @click="runMtr">
                {{ diagModal.mtrError ? t('atlas.diagnostics_retry') : t('atlas.diagnostics_run_mtr') }}
              </button>
            </template>
          </div>
        </div>
      </div>

      <!-- Grant-kind color legend, directly under the graph so it isn't
           missed — the line color alone would fail color-blind readers, so
           each swatch carries its own text label (the label, not the color,
           is what actually distinguishes a kind). -->
      <div style="display: flex; flex-wrap: wrap; gap: var(--space-3); margin-top: var(--space-2); font-size: var(--text-xs)">
        <span v-for="item in edgeKindLegend" :key="item.kind" style="display: inline-flex; align-items: center; gap: 6px">
          <svg width="20" height="8" style="flex-shrink: 0">
            <line x1="0" y1="4" x2="20" y2="4" :stroke="item.color"
                  :stroke-width="item.kind === 'network-grant' ? 2.5 : 1.5"
                  :stroke-dasharray="item.kind === 'network-grant' ? '5 3' : null" />
          </svg>
          <span class="muted">{{ item.label }}</span>
        </span>
      </div>

      <div class="card card-pad" style="margin-top: var(--space-4)">
        <h2 style="margin: 0 0 var(--space-3); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">
          {{ t('atlas.grants_table_title') }}
        </h2>
        <table v-if="grantsForTable.length > 0" class="table">
          <thead>
            <tr>
              <th>{{ t('atlas.th_user') }}</th>
              <th>{{ t('atlas.th_role') }}</th>
              <th>{{ t('atlas.th_resource') }}</th>
              <th>{{ t('atlas.th_ports') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in pagedGrantsForTable" :key="row.key">
              <td>{{ row.userName }}</td>
              <td>{{ row.roleName }}</td>
              <td>{{ row.resourceName }}</td>
              <td class="mono">{{ row.portsLabel }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="grantsForTable.length > 0" style="display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-3)">
          <button class="btn btn-ghost btn-sm" :disabled="grantsPageClamped <= 1" @click="grantsPrevPage">{{ t('atlas.grants_page_prev') }}</button>
          <button class="btn btn-ghost btn-sm" :disabled="grantsPageClamped >= grantsPageCount" @click="grantsNextPage">{{ t('atlas.grants_page_next') }}</button>
          <span class="muted" style="font-size: var(--text-sm)">{{ grantsPageInfo }}</span>
        </div>
        <p v-else class="muted" style="font-size: var(--text-sm)">{{ t('acl.type_grants_empty') }}</p>
      </div>
    </template>

    <div v-if="grantDialog" class="modal-backdrop" @click.self="cancelGrantDialog">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('atlas.grant_dialog_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="cancelGrantDialog">✕</button>
        </div>
        <div class="modal-body">
          <p style="margin: 0 0 var(--space-3)">
            <strong>{{ t('atlas.grant_dialog_mode') }}</strong> {{ grantModeLabel }} → {{ grantDialog.subjectName }} → {{ grantDialog.resourceName }}
          </p>

          <label style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; margin-bottom: var(--space-2)">
            <input type="radio" :value="true" v-model="grantDialog.allPorts" style="width: 16px; height: 16px; accent-color: var(--accent)" />
            {{ t('atlas.grant_dialog_all_ports') }}
          </label>
          <label v-if="grantDialog.ports.length > 0" style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer">
            <input type="radio" :value="false" v-model="grantDialog.allPorts" style="width: 16px; height: 16px; accent-color: var(--accent)" />
            {{ t('atlas.grant_dialog_ports') }}
          </label>
          <div v-if="!grantDialog.allPorts" style="margin-left: var(--space-6); margin-top: var(--space-2); display: flex; flex-direction: column; gap: var(--space-2)">
            <label v-for="p in grantDialog.ports" :key="p.id" style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer">
              <input type="checkbox" :value="p.id" v-model="grantDialog.portIds" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              <span class="mono">{{ p.port }}/{{ p.transport }}</span>
              <span>{{ p.protocol }}</span>
            </label>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="cancelGrantDialog">{{ t('atlas.grant_dialog_cancel') }}</button>
          <button type="button" class="btn btn-primary" :disabled="grantSaving" @click="confirmGrantDialog">
            {{ grantSaving ? t('common.loading') : t('atlas.grant_dialog_confirm') }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="revokeConfirm" class="modal-backdrop" @click.self="cancelRevokeConfirm">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('atlas.revoke_dialog_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="cancelRevokeConfirm">✕</button>
        </div>
        <div class="modal-body">
          <p v-if="revokeConfirm.isTypeGrant" style="margin: 0">
            {{ t('atlas.revoke_type_grant_confirm', { user: revokeConfirm.userName, type: revokeConfirm.typeLabel, site: revokeConfirm.siteName }) }}
          </p>
          <p v-else style="margin: 0">
            {{ t('atlas.revoke_confirm', { user: revokeConfirm.userName, role: revokeConfirm.roleLabel, resource: revokeConfirm.resourceName }) }}
          </p>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="cancelRevokeConfirm">{{ t('atlas.grant_dialog_cancel') }}</button>
          <button type="button" class="btn btn-primary" :disabled="revokeSaving" @click="confirmRevokeEdge">
            {{ revokeSaving ? t('common.loading') : t('atlas.tool_revoke') }}
          </button>
        </div>
      </div>
    </div>

  `,
});
