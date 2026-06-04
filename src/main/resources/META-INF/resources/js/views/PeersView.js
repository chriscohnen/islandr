import { defineComponent } from "vue";
import { peerModalMixin, peerModalTemplate } from "/js/peerModal.js";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

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
      loading: true,
      error: null,
      // Pre-selected user for the create modal (sticky, so creating many peers
      // for the same user does not require re-picking each time).
      createUserId: "",
      lang: locale.current,
    };
  },
  computed: {
    _lang() { return locale.current; },
    modalUserName() {
      const u = this.usersById[this.modalUserId];
      return u ? `${u.name} (${u.email})` : null;
    },
  },
  async mounted() {
    await this.load();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const [peersRes, usersRes] = await Promise.all([
          fetch("/api/v1/peers"),
          fetch("/api/v1/users"),
        ]);
        if (!peersRes.ok) throw new Error("Peers HTTP " + peersRes.status);
        if (!usersRes.ok) throw new Error("Users HTTP " + usersRes.status);
        this.peers = await peersRes.json();
        this.users = await usersRes.json();
        this.usersById = Object.fromEntries(this.users.map((u) => [u.id, u]));
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
      if (!this.createUserId) {
        alert(t("peers.no_users_alert"));
        return;
      }
      this.openCreatePeer(this.createUserId);
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
      try {
        const res = await fetch("/api/v1/peers/" + peer.id + "/enabled", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ enabled: !peer.enabled }),
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

    onPeerUpdated() {
      // Same shape — refresh the list so the edited row reflects the new state.
      this.load();
    },

    userNameFor(userId) {
      return this.usersById[userId]?.name || "?";
    },

    formatDate(iso) {
      if (!iso) return "—";
      return new Date(iso).toLocaleString("de-DE");
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('peers.title') }} <span v-if="peers.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ peers.length }}</span></h1>
      <div style="display: flex; gap: var(--space-3); align-items: center">
        <label class="muted" for="createUser" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">{{ t('peers.for_user') }}</label>
        <select id="createUser" class="select" style="height: 32px; width: auto; min-width: 180px" v-model="createUserId" :disabled="users.length === 0">
          <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
        </select>
        <button class="btn btn-primary btn-sm" @click="openCreate" :disabled="users.length === 0">{{ t('peers.create_btn') }}</button>
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
          <th>{{ t('peers.th_name') }}</th>
          <th>{{ t('peers.th_type') }}</th>
          <th>{{ t('peers.th_user') }}</th>
          <th>{{ t('peers.th_ip') }}</th>
          <th>{{ t('peers.th_status') }}</th>
          <th>{{ t('peers.th_handshake') }}</th>
          <th>{{ t('peers.th_created') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in peers" :key="p.id">
          <td>{{ p.name }}</td>
          <td>
            <span v-if="p.type === 'site'" class="badge badge-info">{{ t('peers.type_site') }}</span>
            <span v-else style="display: inline-flex; align-items: center; gap: var(--space-2); color: var(--fg2); font-size: var(--text-sm)">
              <Icon :name="p.deviceType && p.deviceType !== 'other' ? p.deviceType : 'peers'" :size="15" />
              {{ { laptop: t('peers.dev_laptop'), desktop: t('peers.dev_desktop'), mobile: t('peers.dev_mobile'), tablet: t('peers.dev_tablet'), server: t('peers.dev_server'), other: t('peers.dev_other') }[p.deviceType] || t('peers.dev_other') }}
            </span>
          </td>
          <td>{{ userNameFor(p.userId) }}</td>
          <td>
            <div class="mono">{{ p.assignedIp }}</div>
            <div v-if="p.type === 'site' && p.siteAllowedCidrs" class="mono" style="font-size: var(--text-xs); color: var(--fg3); margin-top: 2px">
              → {{ p.siteAllowedCidrs }}
            </div>
          </td>
          <td>
            <span :class="['badge', p.enabled ? 'badge-success' : 'badge-neutral']">
              {{ p.enabled ? t('peers.status_active') : t('peers.status_disabled') }}
            </span>
          </td>
          <td class="muted">{{ p.lastSeenAt ? formatDate(p.lastSeenAt) : "—" }}</td>
          <td class="muted">{{ formatDate(p.createdAt) }}</td>
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEditPeer(p)">{{ t('peers.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="openReshow(p.userId, p.id)">{{ t('peers.btn_qr') }}</button>
            <button class="btn btn-ghost btn-sm" @click="toggleEnabled(p)">{{ p.enabled ? t('peers.btn_disable') : t('peers.btn_enable') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deletePeer(p.id)">{{ t('peers.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    ${peerModalTemplate}
  `,
});
