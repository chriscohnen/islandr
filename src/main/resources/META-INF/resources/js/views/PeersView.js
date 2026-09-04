import { defineComponent } from "vue";
import { peerModalMixin, peerModalTemplate } from "/js/peerModal.js";
import { Icon } from "/js/Icons.js";
import { t, locale, formatDate } from "/js/i18n.js";
import { connectionBadgeClass, connectionLabelKey } from "/js/peerStatus.js";
import { onEscape } from "/js/keyboard.js";
import { hub, loadHub } from "/js/hub.js";

// Flat list of every peer across every user. This is the main working surface
// for sysadmins ("show me everything connected"). User-scoped peer creation
// also lives here via a user picker inside the modal.
export default defineComponent({
  name: "PeersView",
  components: { Icon },
  props: {
    retention: { type: String, default: "never" },
  },
  mixins: [peerModalMixin],
  data() {
    return {
      peers: [],
      users: [],
      usersById: {},
      // peerId -> true for every peer with a recurring schedule (#47) — one
      // bulk fetch instead of a per-row request.
      scheduledPeerIds: {},
      loading: true,
      error: null,
      // Pre-selected user for the create modal (sticky, so creating many peers
      // for the same user does not require re-picking each time).
      createUserId: "",
      filterUserId: "",   // "" = all users
      sortKey: "updatedAt",
      sortDir: -1,        // -1 = desc, 1 = asc
      lang: locale.current,
      // wg import
      importModal: false,
      importCandidates: [],
      importLoading: false,
      importError: null,
      importSubmitting: false,
      importResults: null,
    };
  },
  computed: {
    // The interface these peers live on. Named in the import copy, so a hub
    // deployed with ISLANDR_WG_INTERFACE=wg1 does not read "Import from wg0".
    wgInterface() { return hub.wgInterface; },
    importSelectable() {
      return this.importCandidates.filter(c => !c.alreadyExists);
    },
    importSelectedCount() {
      return this.importSelectable.filter(c => c.selected).length;
    },
    _lang() { return locale.current; },
    modalUserName() {
      const u = this.usersById[this.modalUserId];
      return u ? `${u.name} (${u.email})` : null;
    },
    visiblePeers() {
      // type !== 'site' guard matches the Benutzer column's own display rule
      // below (a site peer always shows "—" there, regardless of its raw
      // userId) — without it, a site peer whose userId happens to be
      // non-null (stale data, or a client that skipped server-side
      // validation) wrongly surfaces under a user it isn't actually owned
      // by, even though the table itself correctly shows no owner for it.
      let list = this.filterUserId
        ? this.peers.filter((p) => p.type !== "site" && p.userId === this.filterUserId)
        : [...this.peers];
      const k = this.sortKey;
      const d = this.sortDir;
      list.sort((a, b) => {
        let av = a[k], bv = b[k];
        if (k === "name") return d * av.localeCompare(bv);
        if (k === "assignedIp") {
          const diff = this.ipKey(a.assignedIp) - this.ipKey(b.assignedIp);
          // Ties are the routed networks below the IP: a gateway with more of
          // them is the bigger site, which is the useful order here.
          if (diff !== 0) return d * diff;
          const ac = (a.siteAllowedCidrs || "").split(",").filter((x) => x.trim()).length;
          const bc = (b.siteAllowedCidrs || "").split(",").filter((x) => x.trim()).length;
          return d * (bc - ac);
        }
        // Sites first ascending — a hub with a handful of gateways among many
        // clients is the case worth grouping, not the other way round.
        if (k === "type") {
          av = a.type === "site" ? 0 : 1;
          bv = b.type === "site" ? 0 : 1;
          if (av !== bv) return d * (av - bv);
          return a.name.localeCompare(b.name);
        }
        if (k === "enabled") return d * ((av ? 1 : 0) - (bv ? 1 : 0));
        if (k === "user") {
          // Same "—" for a site peer as the Benutzer column, regardless of
          // any stray userId on the row (see visiblePeers above).
          av = a.type === "site" ? "—" : this.userNameFor(a.userId);
          bv = b.type === "site" ? "—" : this.userNameFor(b.userId);
          return d * av.localeCompare(bv);
        }
        // dates / nulls
        if (!av && !bv) return 0;
        if (!av) return 1;
        if (!bv) return -1;
        return d * (av < bv ? -1 : av > bv ? 1 : 0);
      });
      return list;
    },
  },
  async mounted() {
    loadHub();
    await this.load();
    this._offEscape = onEscape(() => { if (this.importModal) this.closeImport(); });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const [peersRes, usersRes, schedulesRes] = await Promise.all([
          fetch("/api/v1/peers"),
          fetch("/api/v1/users"),
          fetch("/api/v1/peers/schedules"),
        ]);
        if (!peersRes.ok) throw new Error("Peers HTTP " + peersRes.status);
        if (!usersRes.ok) throw new Error("Users HTTP " + usersRes.status);
        this.peers = await peersRes.json();
        this.users = await usersRes.json();
        this.usersById = Object.fromEntries(this.users.map((u) => [u.id, u]));
        // Best-effort — a failed schedules fetch just means no indicator dots,
        // not a load failure for the whole page.
        this.scheduledPeerIds = {};
        if (schedulesRes.ok) {
          const schedules = await schedulesRes.json();
          this.scheduledPeerIds = Object.fromEntries(schedules.map((s) => [s.peerId, true]));
        }
        if (!this.createUserId && this.users.length > 0) {
          this.createUserId = this.users[0].id;
        }
      } catch (e) {
        this.error = t("peers.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    openCreate() {
      // No users yet: still allow a site peer (which needs no user). The modal
      // disables the client option with a hint to create a user first (F-3b).
      this.openCreatePeer(this.createUserId || null);
      if (!this.createUserId) this.peerType = "site";
    },

    async deletePeer(peerId) {
      if (!confirm(t("peers.confirm_delete"))) return;
      try {
        const res = await fetch("/api/v1/peers/" + peerId, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        alert(t("peers.error_delete", { error: e.message }));
      }
    },

    async toggleEnabled(peer) {
      const enabling = !peer.enabled;
      // Reason is optional context for the audit log only (#47) — asked only
      // when disabling; Cancel just skips it rather than aborting the toggle.
      const reason = enabling ? null : window.prompt(t("peers.disable_reason_prompt"));
      try {
        const res = await fetch("/api/v1/peers/" + peer.id + "/enabled", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ enabled: enabling, reason: reason || null }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        alert(t("peers.error_toggle", { error: e.message }));
      }
    },

    onPeerCreated() {
      // From the mixin — reload to pick up the new row.
      this.load();
    },

    async openImport() {
      this.importModal = true;
      this.importError = null;
      this.importResults = null;
      this.importLoading = true;
      try {
        const res = await fetch("/api/v1/peers/wg-import-preview");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const candidates = await res.json();
        // Pre-populate name from allowedIps and default user to first user
        // A candidate that already routes networks beyond its own address is a
        // gateway as far as wg is concerned — default it to site so importing 24
        // peers does not silently flatten the branch offices into clients.
        this.importCandidates = candidates.map(c => ({
          ...c,
          selected: !c.alreadyExists,
          name: c.assignedIp || c.publicKey.slice(0, 8),
          type: c.siteAllowedCidrs ? "site" : "client",
          siteAllowedCidrs: c.siteAllowedCidrs || "",
          userId: c.siteAllowedCidrs ? "" : (this.users[0]?.id || ""),
        }));
      } catch (e) {
        this.importError = t("peers.import_error_load", { error: e.message });
      } finally {
        this.importLoading = false;
      }
    },

    setAllImportSelected(selected) {
      this.importCandidates.forEach(c => { if (!c.alreadyExists) c.selected = selected; });
    },

    closeImport() {
      this.importModal = false;
      this.importCandidates = [];
      this.importResults = null;
      this.importError = null;
    },

    async submitImport() {
      const toImport = this.importCandidates.filter(c => c.selected && !c.alreadyExists);
      if (toImport.length === 0) return;
      const siteWithoutCidrs = toImport.find(c => c.type === "site" && !c.siteAllowedCidrs.trim());
      if (siteWithoutCidrs) {
        this.importError = t("peers.import_error_site_cidrs", { name: siteWithoutCidrs.name });
        return;
      }
      this.importSubmitting = true;
      this.importError = null;
      try {
        const res = await fetch("/api/v1/peers/wg-import", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ peers: toImport.map(c => ({
            publicKey: c.publicKey,
            name: c.name,
            assignedIp: c.assignedIp,
            userId: c.type === "site" ? null : (c.userId || null),
            type: c.type,
            siteAllowedCidrs: c.type === "site" ? c.siteAllowedCidrs.trim() : null,
          })) }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.importResults = await res.json();
        await this.load();
      } catch (e) {
        this.importError = t("peers.import_error_save", { error: e.message });
      } finally {
        this.importSubmitting = false;
      }
    },

    onPeerUpdated() {
      // Same shape — refresh the list so the edited row reflects the new state.
      this.load();
    },

    userNameFor(userId) {
      return this.usersById[userId]?.name || "?";
    },

    // Sortable as a number, not as text: "10.77.140.9" sorts after
    // "10.77.140.21" as a string, which is the opposite of what an operator
    // scanning a subnet expects. Peers with no IPv4 (v6-only) sort last.
    ipKey(ip) {
      if (!ip) return Number.MAX_SAFE_INTEGER;
      const parts = ip.split(".");
      if (parts.length !== 4) return Number.MAX_SAFE_INTEGER;
      return parts.reduce((acc, p) => acc * 256 + (parseInt(p, 10) || 0), 0);
    },

    sortBy(key) {
      if (this.sortKey === key) this.sortDir *= -1;
      else { this.sortKey = key; this.sortDir = 1; }
    },
    sortIcon(key) {
      if (this.sortKey !== key) return "↕";
      return this.sortDir === 1 ? "↑" : "↓";
    },
    formatDate(iso) { return formatDate(iso); },
    connectionBadgeClass(p) { return connectionBadgeClass(p); },
    connectionLabelKey(p) { return connectionLabelKey(p); },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('peers.title') }}
        <span v-if="peers.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">
          {{ visiblePeers.length }}<template v-if="filterUserId"> / {{ peers.length }}</template>
        </span>
      </h1>
      <div style="display: flex; gap: var(--space-3); align-items: center">
        <label class="muted" for="filterUser" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">{{ t('peers.for_user') }}</label>
        <select id="filterUser" class="select" style="height: 32px; width: auto; min-width: 180px"
                v-model="filterUserId"
                @change="createUserId = filterUserId || (users[0] && users[0].id) || ''"
                :disabled="users.length === 0">
          <option value="">{{ t('peers.all_users') }}</option>
          <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
        </select>
        <button class="btn btn-primary btn-sm" @click="openCreate" :disabled="users.length === 0">{{ t('peers.create_btn') }}</button>
        <button class="btn btn-ghost btn-sm" @click="openImport">{{ t('peers.import_btn', { iface: wgInterface }) }}</button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="peers.length === 0" class="empty-state">
      <h2>{{ t('peers.empty_title') }}</h2>
      <p>{{ t('peers.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th @click="sortBy('name')" style="cursor: pointer; user-select: none; white-space: nowrap; width: 22%">
            {{ t('peers.th_name') }} <span class="muted" style="font-size: 10px">{{ sortIcon('name') }}</span>
          </th>
          <th @click="sortBy('type')" style="cursor: pointer; user-select: none; white-space: nowrap">
            {{ t('peers.th_type') }} <span class="muted" style="font-size: 10px">{{ sortIcon('type') }}</span>
          </th>
          <th @click="sortBy('user')" style="cursor: pointer; user-select: none; white-space: nowrap">
            {{ t('peers.th_user') }} <span class="muted" style="font-size: 10px">{{ sortIcon('user') }}</span>
          </th>
          <th @click="sortBy('assignedIp')" style="cursor: pointer; user-select: none; white-space: nowrap">
            {{ t('peers.th_ip') }} <span class="muted" style="font-size: 10px">{{ sortIcon('assignedIp') }}</span>
          </th>
          <th @click="sortBy('enabled')" style="cursor: pointer; user-select: none; white-space: nowrap">
            {{ t('peers.th_status') }} <span class="muted" style="font-size: 10px">{{ sortIcon('enabled') }}</span>
          </th>
          <th>{{ t('peers.th_handshake') }}</th>
          <th @click="sortBy('updatedAt')" style="cursor: pointer; user-select: none; white-space: nowrap">
            {{ t('peers.th_updated') }} <span class="muted" style="font-size: 10px">{{ sortIcon('updatedAt') }}</span>
          </th>
          <th class="table-actions"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in visiblePeers" :key="p.id">
          <td>{{ p.name }}</td>
          <td>
            <span v-if="p.type === 'site'" class="badge badge-info">{{ t('peers.type_site') }}</span>
            <span v-else style="display: inline-flex; align-items: center; gap: var(--space-2); color: var(--fg2); font-size: var(--text-sm)">
              <Icon :name="p.deviceType && p.deviceType !== 'other' ? p.deviceType : 'peers'" :size="15" />
              {{ { laptop: t('peers.dev_laptop'), desktop: t('peers.dev_desktop'), mobile: t('peers.dev_mobile'), tablet: t('peers.dev_tablet'), server: t('peers.dev_server'), other: t('peers.dev_other') }[p.deviceType] || t('peers.dev_other') }}
            </span>
          </td>
          <td>
            <span v-if="p.type === 'site'" class="muted">—</span>
            <span v-else>{{ userNameFor(p.userId) }}</span>
          </td>
          <td>
            <div class="mono">{{ p.assignedIp }}</div>
            <div v-if="p.type === 'site' && p.siteAllowedCidrs" class="mono" style="font-size: var(--text-xs); color: var(--fg3); margin-top: 2px">
              → {{ p.siteAllowedCidrs }}
            </div>
          </td>
          <td>
            <span v-if="!p.enabled" class="badge badge-neutral">
              <span class="dot"></span>{{ t('peers.status_disabled') }}
            </span>
            <span v-else :class="['badge', connectionBadgeClass(p)]">
              <span class="dot"></span>{{ t(connectionLabelKey(p)) }}
            </span>
            <div style="margin-top: 4px; display: flex; flex-direction: column; gap: 2px">
              <span v-if="p.validUntil" class="muted" style="font-size: var(--text-xs)">
                {{ t('peers.expires_label', { date: formatDate(p.validUntil) }) }}
              </span>
              <span v-if="scheduledPeerIds[p.id]" class="muted" style="font-size: var(--text-xs)">
                {{ t('peers.has_schedule') }}
              </span>
            </div>
          </td>
          <td class="muted">{{ p.lastSeenAt ? formatDate(p.lastSeenAt) : "—" }}</td>
          <td class="muted">{{ formatDate(p.updatedAt) }}</td>
          <td class="table-actions">
            <button class="btn btn-ghost btn-sm" @click="openEditPeer(p)"><Icon name="edit" :size="13" />{{ t('peers.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="openReshow(p.userId, p.id)"><Icon name="qr-code" :size="13" />{{ t('peers.btn_qr') }}</button>
            <button class="btn btn-ghost btn-sm" @click="toggleEnabled(p)">
              <Icon :name="p.enabled ? 'pause-circle' : 'play-circle'" :size="13" />
              {{ p.enabled ? t('peers.btn_disable') : t('peers.btn_enable') }}
            </button>
            <button class="btn btn-ghost btn-sm" @click="deletePeer(p.id)"><Icon name="trash" :size="13" />{{ t('peers.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    ${peerModalTemplate}

    <!-- wg import modal -->
    <div v-if="importModal" class="modal-backdrop" @click.self="closeImport">
      <div class="modal" style="max-width: 880px">
        <div class="modal-header">
          <h2>{{ t('peers.import_title', { iface: wgInterface }) }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeImport">✕</button>
        </div>
        <div class="modal-body">
          <div v-if="importError" class="error-banner" style="margin-bottom: var(--space-3)">{{ importError }}</div>

          <div v-if="importLoading" class="muted">{{ t('common.loading') }}</div>

          <div v-else-if="importResults">
            <p style="margin-bottom: var(--space-3)">
              {{ importResults.filter(r => r.status === 'imported').length }} {{ t('peers.import_done') }}
            </p>
            <table class="table" style="font-size: var(--text-sm)">
              <thead><tr><th>Key</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="r in importResults" :key="r.publicKey">
                  <td class="mono" style="font-size: 11px">{{ r.publicKey.slice(0,16) }}…</td>
                  <td>{{ r.status }}</td>
                </tr>
              </tbody>
            </table>
            <div style="margin-top: var(--space-4)">
              <button class="btn btn-primary btn-sm" @click="closeImport">{{ t('common.close') }}</button>
            </div>
          </div>

          <div v-else-if="importCandidates.length === 0" class="muted">
            {{ t('peers.import_empty', { iface: wgInterface }) }}
          </div>

          <div v-else>
            <p class="muted" style="margin-bottom: var(--space-3); font-size: var(--text-sm)">{{ t('peers.import_hint', { iface: wgInterface }) }}</p>
            <div style="margin-bottom: var(--space-2); display:flex; align-items:center; gap:var(--space-2); flex-wrap:wrap">
              <button type="button" class="btn btn-ghost btn-sm"
                      :disabled="importSelectedCount === importSelectable.length"
                      @click="setAllImportSelected(true)">{{ t('peers.import_select_all') }}</button>
              <button type="button" class="btn btn-ghost btn-sm"
                      :disabled="importSelectedCount === 0"
                      @click="setAllImportSelected(false)">{{ t('peers.import_select_none') }}</button>
              <span class="muted" style="font-size: var(--text-sm)">
                {{ t('peers.import_selected_count', { selected: importSelectedCount, total: importSelectable.length }) }}
              </span>
            </div>
            <table class="table" style="font-size: var(--text-sm)">
              <thead>
                <!-- Explicit widths: .input/.select are width:100%, so without
                     them the browser squeezes a column down to its control's
                     chevron and the value looks missing rather than clipped. -->
                <tr>
                  <th style="width: 32px"></th>
                  <th style="width: 150px">{{ t('peers.import_th_key') }}</th>
                  <th style="width: 120px">{{ t('peers.import_th_ip') }}</th>
                  <th>{{ t('peers.import_th_name') }}</th>
                  <th style="width: 110px">{{ t('peers.import_th_type') }}</th>
                  <th style="width: 190px">{{ t('peers.import_th_user') }}</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="c in importCandidates" :key="c.publicKey">
                <tr :style="c.alreadyExists ? 'opacity:0.45' : ''">
                  <td>
                    <input type="checkbox" v-model="c.selected" :disabled="c.alreadyExists"
                           style="width:15px;height:15px;accent-color:var(--accent);margin:0" />
                  </td>
                  <td class="mono" style="font-size:11px">{{ c.publicKey.slice(0,16) }}…
                    <span v-if="c.alreadyExists && c.assignedIp" class="badge badge-neutral" style="margin-left:4px;font-size:10px">{{ t('peers.import_exists') }}</span>
                    <span v-if="c.alreadyExists && !c.assignedIp" class="badge badge-neutral" style="margin-left:4px;font-size:10px">IPv6</span>
                  </td>
                  <td class="mono">{{ c.assignedIp || '—' }}</td>
                  <td>
                    <input v-if="!c.alreadyExists" class="input" style="height:28px;font-size:var(--text-sm);padding:2px 6px"
                           v-model="c.name" :disabled="!c.selected" placeholder="Name" />
                    <span v-else class="muted">—</span>
                  </td>
                  <td>
                    <select v-if="!c.alreadyExists" class="select" style="height:28px;font-size:var(--text-sm)"
                            v-model="c.type" :disabled="!c.selected">
                      <option value="client">Client</option>
                      <option value="site">Site</option>
                    </select>
                    <span v-else class="muted">—</span>
                  </td>
                  <td>
                    <select v-if="!c.alreadyExists && c.type === 'client'" class="select" style="height:28px;font-size:var(--text-sm)"
                            v-model="c.userId" :disabled="!c.selected">
                      <option value="">{{ t('peers.import_no_user') }}</option>
                      <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
                    </select>
                    <span v-else-if="!c.alreadyExists" class="muted">{{ t('peers.import_site_no_user') }}</span>
                    <span v-else class="muted">—</span>
                  </td>
                </tr>
                <tr v-if="!c.alreadyExists && c.type === 'site'">
                  <td></td>
                  <td colspan="5" style="padding-top:0">
                    <label class="eyebrow" style="display:block; margin-bottom:2px">{{ t('peers.import_th_cidrs') }}</label>
                    <input class="input mono" style="height:28px;font-size:var(--text-sm);padding:2px 6px"
                           v-model="c.siteAllowedCidrs" :disabled="!c.selected"
                           placeholder="192.168.50.0/24, 10.20.0.0/16" />
                    <div class="field-hint" style="margin-top:2px">{{ t('peers.import_cidrs_hint') }}</div>
                  </td>
                </tr>
                </template>
              </tbody>
            </table>
            <div style="margin-top: var(--space-4); display:flex; gap:var(--space-3)">
              <button class="btn btn-primary btn-sm" :disabled="importSubmitting || !importCandidates.some(c => c.selected && !c.alreadyExists)" @click="submitImport">
                {{ importSubmitting ? t('common.saving') : t('peers.import_btn_confirm') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="closeImport">{{ t('common.cancel') }}</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
});
