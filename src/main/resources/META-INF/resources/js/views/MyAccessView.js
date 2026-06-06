import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Self-service view: an org user manages their own devices. No site peers,
// no IP picker (the server chooses), no other user's data.
// Endpoints used:
//   GET    /api/v1/peers/mine               — list own peers
//   POST   /api/v1/peers/mine                — add a device
//   GET    /api/v1/peers/mine/{id}/conf      — re-show .conf (plaintext only)
//   PUT    /api/v1/peers/mine/{id}/public-key — rotate the public key
export default defineComponent({
  name: "MyAccessView",
  components: { Icon },
  props: {
    retention: { type: String, default: "never" },
    selfServicePeerCreation: { type: Boolean, default: true },
  },
  data() {
    return {
      lang: locale.current,
      peers: [],
      grants: [],       // resources this user has access to
      grantsLoading: true,
      loading: true,
      error: null,
      // userId the view is scoped to (null = own, set = admin impersonation)
      viewAsUserId: null,
      viewAsUserName: null,
      modalMode: null, // null | "create" | "secret" | "rotate"
      // create form
      newDevice: { name: "", publicKey: "" },
      importPublicKey: false,
      submitting: false,
      // secret view (after create or reshow)
      secret: null,
      secretIsReshow: false,
      copyState: "idle",
      // rotate form
      rotatePeer: null,
      rotateKey: "",
      formError: null,
    };
  },
  computed: {
    _lang() { return locale.current; },
    groupedGrants() {
      const groups = {};
      for (const r of this.grants) {
        const key = r.siteName || "Unbekannt";
        (groups[key] = groups[key] || []).push(r);
      }
      return groups;
    },
    pageTitle() {
      void this._lang;
      if (this.viewAsUserId && this.viewAsUserName) {
        return t("myaccess.title_admin", { name: this.viewAsUserName });
      }
      return t("myaccess.title");
    },
  },
  async mounted() {
    if (this.$route.query.as) {
      this.viewAsUserId = this.$route.query.as;
      this.viewAsUserName = this.$route.query.asName || this.$route.query.as;
    }
    await Promise.all([this.load(), this.loadGrants()]);
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const url = this.viewAsUserId
            ? "/api/v1/users/" + encodeURIComponent(this.viewAsUserId) + "/peers"
            : "/api/v1/peers/mine";
        const res = await fetch(url);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.peers = await res.json();
      } catch (e) {
        this.error = t("myaccess.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    async loadGrants() {
      this.grantsLoading = true;
      try {
        const url = this.viewAsUserId
            ? "/api/v1/acl/my-resources?userId=" + encodeURIComponent(this.viewAsUserId)
            : "/api/v1/acl/my-resources";
        const res = await fetch(url);
        if (!res.ok) return;
        this.grants = await res.json();
      } catch {
        // non-fatal — grants section simply stays empty
      } finally {
        this.grantsLoading = false;
      }
    },

    httpUrl(resource, port) {
      const scheme = port.protocol === "HTTPS" ? "https" : "http";
      return scheme + "://" + resource.ip + ":" + port.port;
    },

    isWebPort(port) {
      return port.protocol === "HTTP" || port.protocol === "HTTPS";
    },

    isRdpPort(port) {
      return port.protocol === "RDP" || (port.port === 3389 && port.transport === "tcp");
    },

    downloadRdp(resource, port) {
      const content = [
        "full address:s:" + resource.ip + ":" + port.port,
        "prompt for credentials:i:1",
        "screen mode id:i:2",
        "use multimon:i:0",
        "desktopwidth:i:1920",
        "desktopheight:i:1080",
        "session bpp:i:32",
        "compression:i:1",
        "keyboardhook:i:2",
        "audiocapturemode:i:0",
        "videoplaybackmode:i:1",
        "connection type:i:7",
        "networkautodetect:i:1",
        "bandwidthautodetect:i:1",
        "displayconnectionbar:i:1",
        "enableworkspacereconnect:i:0",
        "disable wallpaper:i:0",
        "allow font smoothing:i:0",
        "allow desktop composition:i:0",
        "disable full window drag:i:1",
        "disable menu anims:i:1",
        "disable themes:i:0",
        "disable cursor setting:i:0",
        "bitmapcachepersistenable:i:1",
        "audiomode:i:0",
        "redirectprinters:i:1",
        "redirectcomports:i:0",
        "redirectsmartcards:i:1",
        "redirectwebauthn:i:1",
        "redirectclipboard:i:1",
        "redirectposdevices:i:0",
        "autoreconnection enabled:i:1",
        "authentication level:i:2",
        "negotiate security layer:i:1",
        "remoteapplicationmode:i:0",
        "alternate shell:s:",
        "shell working directory:s:",
        "gatewayhostname:s:",
        "gatewayusagemethod:i:4",
        "gatewaycredentialssource:i:4",
        "gatewayprofileusagemethod:i:0",
        "promptcredentialonce:i:0",
        "gatewaybrokeringtype:i:0",
        "use redirection server name:i:0",
        "rdgiskdcproxy:i:0",
        "kdcproxyname:s:",
      ].join("\r\n");
      const blob = new Blob([content], { type: "application/rdp" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = resource.name.replace(/[^a-zA-Z0-9_\-]/g, "_") + ".rdp";
      a.click();
      URL.revokeObjectURL(url);
    },

    openCreate() {
      this.modalMode = "create";
      this.newDevice = { name: "", publicKey: "" };
      this.importPublicKey = false;
      this.formError = null;
      this.secret = null;
    },

    async submitCreate() {
      if (!this.newDevice.name.trim()) {
        this.formError = t("myaccess.err_name");
        return;
      }
      const payload = { name: this.newDevice.name.trim() };
      if (this.importPublicKey) {
        if (!this.newDevice.publicKey.trim()) {
          this.formError = t("myaccess.err_key");
          return;
        }
        payload.publicKey = this.newDevice.publicKey.trim();
      }
      this.submitting = true;
      this.formError = null;
      try {
        const res = await fetch("/api/v1/peers/mine", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        this.secret = await res.json();
        this.secretIsReshow = false;
        this.modalMode = "secret";
        await this.load();
      } catch (e) {
        this.formError = t("myaccess.error_create", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },

    async openReshow(peerId) {
      try {
        const res = await fetch("/api/v1/peers/mine/" + peerId + "/conf");
        if (res.status === 404) {
          alert(t("myaccess.err_no_conf"));
          return;
        }
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.secret = await res.json();
        this.secretIsReshow = true;
        this.modalMode = "secret";
      } catch (e) {
        alert(t("myaccess.error_conf", { error: e.message }));
      }
    },

    openRotate(peer) {
      this.rotatePeer = peer;
      this.rotateKey = "";
      this.formError = null;
      this.modalMode = "rotate";
    },

    async submitRotate() {
      if (!this.rotateKey.trim()) {
        this.formError = t("myaccess.err_key");
        return;
      }
      this.submitting = true;
      this.formError = null;
      try {
        const res = await fetch("/api/v1/peers/mine/" + this.rotatePeer.id + "/public-key", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ publicKey: this.rotateKey.trim() }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.load();
        this.closeModal();
      } catch (e) {
        this.formError = t("myaccess.error_rotate", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },

    closeModal() {
      this.modalMode = null;
      this.secret = null;
      this.rotatePeer = null;
      this.rotateKey = "";
      this.formError = null;
      this.copyState = "idle";
    },

    async copyConf() {
      if (!this.secret?.conf) return;
      try {
        await navigator.clipboard.writeText(this.secret.conf);
        this.copyState = "copied";
        setTimeout(() => (this.copyState = "idle"), 1500);
      } catch {
        // ignore
      }
    },

    downloadConf() {
      if (!this.secret?.conf) return;
      const blob = new Blob([this.secret.conf], { type: "text/plain" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = (this.secret.peer?.name || "wireguard").replace(/[^a-z0-9-_]/gi, "_") + ".conf";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    },

    canReshow(peer) {
      // /conf only returns 200 when the server has stored the private half —
      // i.e. retention='plaintext' AND the key was server-generated. We can't
      // know that from the peer row alone, so the button stays visible and the
      // 404 path handles the "no stored conf" case.
      return this.retention === "plaintext";
    },

    formatDate(iso) {
      if (!iso) return "—";
      return new Date(iso).toLocaleString("de-DE");
    },
  },
  template: `
    <div class="page-header">
      <div>
        <h1 style="margin: 0 0 2px">
          {{ pageTitle }}
          <span v-if="peers.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ peers.length }}</span>
        </h1>
      </div>
      <button v-if="!viewAsUserId && selfServicePeerCreation" class="btn btn-primary btn-sm" @click="openCreate">{{ t('myaccess.add_btn') }}</button>
      <button v-else class="btn btn-ghost btn-sm" @click="$router.push({ name: 'users' })">{{ t('myaccess.back_btn') }}</button>
    </div>

    <div v-if="viewAsUserId" class="callout callout-info" style="margin-bottom: var(--space-4)">
      <div>{{ t('myaccess.admin_preview', { name: viewAsUserName }) }}</div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="peers.length === 0" class="empty-state">
      <h2>{{ t('myaccess.empty_title') }}</h2>
      <p>{{ t('myaccess.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('myaccess.th_name') }}</th>
          <th>{{ t('myaccess.th_ip') }}</th>
          <th>{{ t('myaccess.th_status') }}</th>
          <th>{{ t('myaccess.th_handshake') }}</th>
          <th>{{ t('myaccess.th_created') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in peers" :key="p.id">
          <td>{{ p.name }}</td>
          <td class="mono">{{ p.assignedIp }}</td>
          <td>
            <span :class="['badge', p.enabled ? 'badge-success' : 'badge-neutral']">
              {{ p.enabled ? t('myaccess.status_active') : t('myaccess.status_disabled') }}
            </span>
          </td>
          <td class="muted">{{ p.lastSeenAt ? formatDate(p.lastSeenAt) : "—" }}</td>
          <td class="muted">{{ formatDate(p.createdAt) }}</td>
          <td style="text-align: right">
            <button v-if="canReshow(p)" class="btn btn-ghost btn-sm" @click="openReshow(p.id)"><Icon name="qr-code" :size="13" />{{ t('myaccess.btn_qr') }}</button>
            <button class="btn btn-ghost btn-sm" @click="openRotate(p)"><Icon name="rotate" :size="13" />{{ t('myaccess.btn_rotate') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- Freigegebene Ressourcen -->
    <div style="margin-top: var(--space-8)">
      <div class="page-header" style="margin-bottom: var(--space-4)">
        <h2 style="margin: 0; font-size: var(--text-lg); font-weight: 600">{{ t('myaccess.grants_title') }}</h2>
      </div>

      <div v-if="grantsLoading" class="muted">{{ t('common.loading') }}</div>

      <div v-else-if="grants.length === 0" class="empty-state">
        <h2>{{ t('myaccess.grants_empty_title') }}</h2>
        <p>{{ t('myaccess.grants_empty_desc', { user: viewAsUserName || 'dir' }) }}</p>
      </div>

      <div v-else class="myaccess-grants">
        <template v-for="(siteGrants, siteName) in groupedGrants" :key="siteName">
          <div class="myaccess-site-label">{{ siteName }}</div>
          <div v-for="r in siteGrants" :key="r.id" class="myaccess-resource">
            <div class="myaccess-resource-head">
              <div class="res-type-tile" style="width:32px;height:32px">
                <Icon :name="r.type || 'computer'" :size="16" />
              </div>
              <div style="flex:1; min-width:0">
                <div style="font-weight:600; font-size: var(--text-sm)">{{ r.name }}</div>
                <div class="mono" style="font-size: var(--text-xs); color: var(--fg3)">{{ r.ip }}</div>
              </div>
            </div>
            <div class="myaccess-ports">
              <span v-if="r.grantedPorts.length === 0" style="font-size:var(--text-xs);color:var(--fg3)">{{ t('myaccess.no_ports') }}</span>
              <template v-for="p in r.grantedPorts" :key="p.id">
                <a v-if="isWebPort(p)"
                   :href="httpUrl(r, p)"
                   target="_blank"
                   rel="noopener noreferrer"
                   class="myaccess-port-link"
                   :title="httpUrl(r, p) + ' — im Browser öffnen'">
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.protocol }}</span>
                  <span v-if="p.label" style="color:var(--fg3)">{{ p.label }}</span>
                  <Icon name="external-link" :size="11" style="opacity:.6; flex-shrink:0" />
                </a>
                <button v-else-if="isRdpPort(p)"
                   @click="downloadRdp(r, p)"
                   class="myaccess-port-link myaccess-port-rdp"
                   :title="t('myaccess.rdp_title', { ip: r.ip, port: p.port })">
                  <!-- Monitor + arrow badge icon -->
                  <svg width="18" height="16" viewBox="0 0 22 20" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <!-- monitor body -->
                    <rect x="1" y="1" width="16" height="11" rx="2"/>
                    <line x1="5" x2="13" y1="16" y2="16"/>
                    <line x1="9" x2="9" y1="12" y2="16"/>
                    <!-- arrow badge (bottom-right, filled circle + arrow) -->
                    <circle cx="17" cy="15" r="4" fill="var(--accent,#3BBBD2)" stroke="none"/>
                    <path d="M15.3 15h3.4M17 13.3v3.4" stroke="white" stroke-width="1.4"
                          stroke-linecap="round" transform="rotate(45 17 15)"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </button>
                <span v-else class="myaccess-port-chip">
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.protocol }}</span>
                  <span v-if="p.label" style="color:var(--fg3)">{{ p.label }}</span>
                </span>
              </template>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div v-if="!viewAsUserId && modalMode" class="modal-backdrop" @click.self="closeModal">

      <div v-if="modalMode === 'create'" class="modal">
        <div class="modal-header">
          <h2>Neues Gerät</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submitCreate">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>

            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="devName">Gerätename</label>
              <input id="devName" class="input" v-model="newDevice.name" required placeholder="z.B. iPhone, MacBook" />
              <div class="field-hint">Frei wählbar. Wird im Audit-Log angezeigt.</div>
            </div>

            <fieldset class="key-mode">
              <legend>{{ t('peer.key_section') }}</legend>
              <label class="key-mode-option">
                <input type="radio" :checked="!importPublicKey" @change="importPublicKey = false" />
                <div>
                  <div class="key-mode-title">{{ t('peer.key_generate') }}</div>
                  <div class="key-mode-hint">Einfachster Weg. Du bekommst danach einen QR-Code zum Einscannen in der WireGuard-App.</div>
                </div>
              </label>
              <label class="key-mode-option">
                <input type="radio" :checked="importPublicKey" @change="importPublicKey = true" />
                <div>
                  <div class="key-mode-title">{{ t('peer.key_import') }}</div>
                  <div class="key-mode-hint">Wenn du den Keypair lokal auf dem Gerät erzeugt hast, gib hier nur den öffentlichen Teil ein. Der private Schlüssel verlässt dein Gerät nie.</div>
                </div>
              </label>
            </fieldset>

            <div v-if="importPublicKey" class="field" style="margin-top: var(--space-4)">
              <label for="devPub">{{ t('peer.field_pubkey') }}</label>
              <input id="devPub" class="input mono" v-model="newDevice.publicKey" required :placeholder="t('peer.field_pubkey_ph')" />
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('peer.btn_cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('peer.btn_creating') : t('peer.btn_create') }}
            </button>
          </div>
        </form>
      </div>

      <div v-else-if="modalMode === 'rotate'" class="modal">
        <div class="modal-header">
          <h2>{{ t('myaccess.btn_rotate') }} — {{ rotatePeer?.name }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submitRotate">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>
            <div class="callout callout-info" style="margin-bottom: var(--space-4)">
              Erzeuge auf dem Gerät einen neuen Keypair (z.B. <code>wg genkey | tee priv | wg pubkey</code>) und füge den öffentlichen Teil hier ein. Der bisherige Schlüssel funktioniert anschließend nicht mehr.
            </div>
            <div class="field">
              <label for="rotKey">Neuer Public Key</label>
              <input id="rotKey" class="input mono" v-model="rotateKey" required :placeholder="t('peer.field_pubkey_ph')" />
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('peer.btn_cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('peer.btn_saving') : t('peer.btn_save') }}
            </button>
          </div>
        </form>
      </div>

      <div v-else-if="modalMode === 'secret' && secret" class="modal modal-xl">
        <div class="modal-header">
          <h2>{{ secretIsReshow ? t('peer.secret_title_re') : t('peer.secret_title_new') }} — {{ secret.peer?.name }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <div class="modal-body">
          <div v-if="!secretIsReshow && secret.privateKey && retention !== 'plaintext'" class="callout callout-warning">
            <div>
              <strong>{{ t('peer.warn_once') }}</strong>
              {{ t('peer.warn_once_desc') }}
            </div>
          </div>
          <div v-else-if="!secretIsReshow && !secret.privateKey" class="callout callout-info">
            <div>
              Gerät hinzugefügt mit deinem importierten Public Key. Die .conf unten enthält keine <code>PrivateKey</code>-Zeile — den trägst du auf dem Gerät selbst ein.
            </div>
          </div>
          <div v-else-if="secretIsReshow && secret.privateKey" class="callout callout-info">
            <div>
              {{ t('peer.warn_reshow') }}
            </div>
          </div>

          <div class="secret-block" :class="{ 'secret-block-no-qr': !secret.qrPngBase64 }">
            <div v-if="secret.qrPngBase64" class="qr">
              <img :src="secret.qrPngBase64" alt="WireGuard QR-Code" />
            </div>
            <div>
              <div class="field" style="margin-bottom: var(--space-3)">
                <label>{{ t('peer.field_ip_key') }}</label>
                <div class="mono" style="font-size: var(--text-sm)">{{ secret.peer?.assignedIp }}</div>
                <div class="mono" style="font-size: var(--text-xs); color: var(--fg3); word-break: break-all">
                  {{ secret.peer?.publicKey }}
                </div>
              </div>
              <pre class="conf-block">{{ secret.conf }}</pre>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" @click="copyConf">
            {{ copyState === "copied" ? t('peer.btn_copied') : t('peer.btn_copy') }}
          </button>
          <button class="btn btn-secondary" @click="downloadConf">{{ t('peer.btn_download') }}</button>
          <button class="btn btn-primary" @click="closeModal">{{ t('peer.btn_done') }}</button>
        </div>
      </div>
    </div>
  `,
});
