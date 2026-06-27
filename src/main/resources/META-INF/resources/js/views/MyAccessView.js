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
      detectedPlatform: (() => {
        const ua = navigator.userAgent;
        if (/iPad|iPhone|iPod/.test(ua))          return 'ios';
        if (/Macintosh|MacIntel/.test(ua))         return 'macos';
        if (/Windows NT/.test(ua))                 return 'windows';
        if (/Android/.test(ua))                    return 'android';
        if (/Linux/.test(ua))                      return 'linux';
        return null;
      })(),
      copiedCmd: null,
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
    // Own view with no devices yet → the setup guide leads the page and is
    // auto-expanded. Once a device exists (or an admin views another user),
    // the guide collapses to a single bar and the daily-use sections lead.
    isOnboarding() {
      return !this.viewAsUserId && this.peers.length === 0;
    },
    detectedPlatformLabel() {
      return {
        ios: "iOS", macos: "macOS", windows: "Windows",
        android: "Android", linux: "Linux",
      }[this.detectedPlatform] || "";
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
      const base = scheme + "://" + resource.ip + ":" + port.port;
      return port.pathPrefix ? base + port.pathPrefix : base;
    },

    isWebPort(port) {
      return port.protocol === "HTTP" || port.protocol === "HTTPS";
    },

    isRdpPort(port) {
      return port.protocol === "RDP" || (port.port === 3389 && port.transport === "tcp");
    },

    isVncPort(port) {
      return port.protocol === "VNC" || (port.port === 5900 && port.transport === "tcp");
    },

    vncUrl(resource, port) {
      return "vnc://" + resource.ip + ":" + port.port;
    },

    rdpUri(resource, port) {
      return "rdp://" + resource.ip + ":" + port.port;
    },

    isSshPort(port) {
      return port.protocol === "SSH" || (port.port === 22 && port.transport === "tcp");
    },

    sshUrl(resource, port) {
      return "ssh://" + resource.ip + ":" + port.port;
    },

    isSftpPort(port) {
      return port.protocol === "SFTP";
    },

    sftpUrl(resource, port) {
      return "sftp://" + resource.ip + ":" + port.port;
    },

    isSmbPort(port) {
      return port.protocol === "SMB" || (port.port === 445 && port.transport === "tcp");
    },

    smbUrl(resource, port) {
      return "smb://" + resource.ip;
    },

    isPrintPort(port) {
      return port.protocol === "PRINT" || (port.port === 631 && port.transport === "tcp");
    },

    ippUrl(resource, port) {
      return "ipp://" + resource.ip + ":" + port.port;
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

    pRow(key) {
      const hit = this.detectedPlatform === key;
      return {
        display: 'flex', alignItems: 'center', gap: 'var(--space-3)', flexWrap: 'wrap',
        padding: 'var(--space-3) var(--space-4)', borderRadius: 'var(--radius-md)',
        border: `1px solid ${hit ? 'var(--accent)' : 'var(--border)'}`,
        background: hit ? 'color-mix(in srgb, var(--accent) 5%, var(--surface))' : 'transparent',
      };
    },

    async copyCmd(text, key) {
      try {
        await navigator.clipboard.writeText(text);
        this.copiedCmd = key;
        setTimeout(() => { this.copiedCmd = null; }, 2000);
      } catch (_) {}
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

    <div v-else class="myaccess-stack" :class="{ 'is-onboarding': isOnboarding }">

      <!-- WireGuard-Client installieren — collapsible; auto-open during onboarding,
           collapsed to a single bar once a device exists. Hidden in admin view-as. -->
      <details v-if="!viewAsUserId" class="myaccess-setup" :open="isOnboarding">
        <summary>
          <svg class="myaccess-setup-chevron" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6,4 10,8 6,12"/></svg>
          <span class="myaccess-setup-label">{{ t('myaccess.setup_s1_title') }}</span>
          <span v-if="detectedPlatformLabel" class="myaccess-setup-detected">{{ detectedPlatformLabel }} {{ t('myaccess.setup_detected_suffix') }}</span>
        </summary>
        <div class="myaccess-setup-body">
          <p class="field-hint" style="margin-bottom:var(--space-5)">{{ t('myaccess.setup_s1_desc') }}</p>

          <div style="display:flex; flex-direction:column; gap:var(--space-2)">

            <!-- macOS -->
            <div :style="pRow('macos')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0; color:var(--text-muted)">
                <rect x="2" y="1" width="12" height="9" rx="1"/>
                <path d="M0 12.5h16"/><path d="M5 12.5l1 2h4l1-2"/>
              </svg>
              <span style="font-weight:500; min-width:72px">macOS</span>
              <span v-if="detectedPlatform==='macos'" style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.08em; color:var(--accent); padding:1px 6px; border:1px solid var(--accent); border-radius:var(--radius-sm)">{{ t('myaccess.setup_detected') }}</span>
              <div style="display:flex; gap:var(--space-2); flex-wrap:wrap; margin-left:auto">
                <a href="macappstore://apps.apple.com/de/app/passepartout-vpn-client/id1433648537" class="btn btn-secondary btn-sm" target="_blank" rel="noopener">
                  Passepartout&nbsp;<span style="font-size:11px; opacity:0.7">({{ t('myaccess.setup_recommended') }})</span>
                </a>
                <a href="macappstore://apps.apple.com/app/wireguard/id1451685025" class="btn btn-ghost btn-sm" target="_blank" rel="noopener">WireGuard</a>
              </div>
            </div>
            <p class="field-hint" style="margin:var(--space-1) 0 var(--space-2) calc(16px + var(--space-3))">{{ t('myaccess.setup_passepartout_desc') }}</p>

            <!-- iOS -->
            <div :style="pRow('ios')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" style="flex-shrink:0; color:var(--text-muted)">
                <rect x="4" y="1" width="8" height="14" rx="2"/>
                <circle cx="8" cy="12.5" r="0.8" fill="currentColor" stroke="none"/>
              </svg>
              <span style="font-weight:500; min-width:72px">iOS</span>
              <span v-if="detectedPlatform==='ios'" style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.08em; color:var(--accent); padding:1px 6px; border:1px solid var(--accent); border-radius:var(--radius-sm)">{{ t('myaccess.setup_detected') }}</span>
              <div style="display:flex; gap:var(--space-2); flex-wrap:wrap; margin-left:auto">
                <a href="https://apps.apple.com/de/app/passepartout-vpn-client/id1433648537" class="btn btn-secondary btn-sm" target="_blank" rel="noopener">
                  Passepartout&nbsp;<span style="font-size:11px; opacity:0.7">({{ t('myaccess.setup_recommended') }})</span>
                </a>
                <a href="https://apps.apple.com/app/wireguard/id1441195209" class="btn btn-ghost btn-sm" target="_blank" rel="noopener">WireGuard</a>
              </div>
            </div>

            <!-- Windows -->
            <div :style="pRow('windows')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0; color:var(--text-muted)">
                <rect x="1" y="2" width="14" height="10" rx="1"/>
                <line x1="8" y1="12" x2="8" y2="14.5"/>
                <line x1="5" y1="14.5" x2="11" y2="14.5"/>
              </svg>
              <span style="font-weight:500; min-width:72px">Windows</span>
              <span v-if="detectedPlatform==='windows'" style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.08em; color:var(--accent); padding:1px 6px; border:1px solid var(--accent); border-radius:var(--radius-sm)">{{ t('myaccess.setup_detected') }}</span>
              <div style="margin-left:auto">
                <a href="https://download.wireguard.com/windows-client/wireguard-installer.exe" class="btn btn-secondary btn-sm" target="_blank" rel="noopener">WireGuard {{ t('myaccess.setup_download') }}</a>
              </div>
            </div>

            <!-- Android -->
            <div :style="pRow('android')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0; color:var(--text-muted)">
                <rect x="3" y="3" width="10" height="12" rx="2"/>
                <circle cx="8" cy="4.5" r="0.8" fill="currentColor" stroke="none"/>
                <line x1="5.5" y1="1.5" x2="4.5" y2="3"/>
                <line x1="10.5" y1="1.5" x2="11.5" y2="3"/>
              </svg>
              <span style="font-weight:500; min-width:72px">Android</span>
              <span v-if="detectedPlatform==='android'" style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.08em; color:var(--accent); padding:1px 6px; border:1px solid var(--accent); border-radius:var(--radius-sm)">{{ t('myaccess.setup_detected') }}</span>
              <div style="margin-left:auto">
                <a href="https://play.google.com/store/apps/details?id=com.wireguard.android" class="btn btn-secondary btn-sm" target="_blank" rel="noopener">WireGuard (Play Store)</a>
              </div>
            </div>

            <!-- Linux -->
            <div :style="pRow('linux')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0; color:var(--text-muted)">
                <rect x="1" y="2" width="14" height="12" rx="1"/>
                <polyline points="4,7 7,10 4,13"/>
                <line x1="8.5" y1="13" x2="12" y2="13"/>
              </svg>
              <span style="font-weight:500; min-width:72px">Linux</span>
              <span v-if="detectedPlatform==='linux'" style="font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:0.08em; color:var(--accent); padding:1px 6px; border:1px solid var(--accent); border-radius:var(--radius-sm)">{{ t('myaccess.setup_detected') }}</span>
              <div style="display:flex; flex-direction:column; gap:var(--space-2); margin-left:auto">
                <div style="display:flex; align-items:center; gap:var(--space-2)">
                  <code style="font-family:var(--font-mono); font-size:var(--text-sm); background:var(--surface-raised); padding:2px 8px; border-radius:var(--radius-sm)">sudo apt install wireguard</code>
                  <button type="button" class="btn btn-ghost btn-sm" @click="copyCmd('sudo apt install wireguard', 'apt')">{{ copiedCmd === 'apt' ? t('myaccess.setup_copied') : t('myaccess.setup_copy') }}</button>
                </div>
                <div style="display:flex; align-items:center; gap:var(--space-2)">
                  <code style="font-family:var(--font-mono); font-size:var(--text-sm); background:var(--surface-raised); padding:2px 8px; border-radius:var(--radius-sm)">sudo dnf install wireguard-tools</code>
                  <button type="button" class="btn btn-ghost btn-sm" @click="copyCmd('sudo dnf install wireguard-tools', 'dnf')">{{ copiedCmd === 'dnf' ? t('myaccess.setup_copied') : t('myaccess.setup_copy') }}</button>
                </div>
              </div>
            </div>

          </div>

          <p class="myaccess-legal">{{ t('myaccess.setup_legal') }}</p>
        </div>
      </details>

      <!-- Meine Geräte -->
      <section class="myaccess-devices">
        <div class="page-header" style="margin-bottom: var(--space-4)">
          <h2 style="margin: 0; font-size: var(--text-lg); font-weight: 600">
            {{ t('myaccess.devices_title') }}
            <span v-if="peers.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-2)">{{ peers.length }}</span>
          </h2>
        </div>

        <div v-if="peers.length === 0 && viewAsUserId" class="empty-state">
          <h2>{{ t('myaccess.empty_title') }}</h2>
          <p>{{ t('myaccess.empty_desc') }}</p>
        </div>

        <div v-else-if="peers.length === 0" class="empty-state">
          <h2>{{ t('myaccess.empty_title') }}</h2>
          <p>{{ t(selfServicePeerCreation ? 'myaccess.setup_s2_self' : 'myaccess.setup_s2_admin') }}</p>
          <button v-if="selfServicePeerCreation" class="btn btn-primary btn-sm" @click="openCreate" style="margin-top: var(--space-3)">{{ t('myaccess.add_btn') }}</button>
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
      </section>

      <!-- Freigegebene Ressourcen -->
      <section class="myaccess-resources">
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
                <div v-else-if="isRdpPort(p)" class="myaccess-port-rdp-group">
                  <button @click="downloadRdp(r, p)"
                     class="myaccess-port-link myaccess-port-rdp myaccess-port-rdp-left"
                     :title="t('myaccess.rdp_title', { ip: r.ip, port: p.port })">
                    <!-- Monitor + download badge icon -->
                    <svg width="18" height="16" viewBox="0 0 22 20" fill="none" stroke="currentColor"
                         stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                         style="flex-shrink:0" aria-hidden="true">
                      <rect x="1" y="1" width="16" height="11" rx="2"/>
                      <line x1="5" x2="13" y1="16" y2="16"/>
                      <line x1="9" x2="9" y1="12" y2="16"/>
                      <circle cx="17" cy="15" r="4" fill="var(--accent,#3BBBD2)" stroke="none"/>
                      <path d="M15.3 15h3.4M17 13.3v3.4" stroke="white" stroke-width="1.4"
                            stroke-linecap="round" transform="rotate(45 17 15)"/>
                    </svg>
                    <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                    <span>{{ p.label || p.protocol }}</span>
                  </button>
                  <a :href="rdpUri(r, p)"
                     class="myaccess-port-link myaccess-port-rdp myaccess-port-rdp-right"
                     :title="t('myaccess.rdp_uri_title', { ip: r.ip, port: p.port })">
                    <!-- Monitor icon for open-in-client -->
                    <svg width="14" height="13" viewBox="0 0 18 16" fill="none" stroke="currentColor"
                         stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                         style="flex-shrink:0" aria-hidden="true">
                      <rect x="1" y="1" width="16" height="10" rx="2"/>
                      <line x1="5" x2="13" y1="15" y2="15"/>
                      <line x1="9" x2="9" y1="11" y2="15"/>
                    </svg>
                  </a>
                </div>
                <a v-else-if="isVncPort(p)"
                   :href="vncUrl(r, p)"
                   class="myaccess-port-link myaccess-port-vnc"
                   :title="t('myaccess.vnc_title', { ip: r.ip, port: p.port })">
                  <!-- Monitor + VNC badge icon -->
                  <svg width="18" height="16" viewBox="0 0 22 20" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <rect x="1" y="1" width="16" height="11" rx="2"/>
                    <line x1="5" x2="13" y1="16" y2="16"/>
                    <line x1="9" x2="9" y1="12" y2="16"/>
                    <!-- eye badge (bottom-right) -->
                    <circle cx="17" cy="15" r="4" fill="var(--vnc-accent,#7C6AF7)" stroke="none"/>
                    <ellipse cx="17" cy="15" rx="2.2" ry="1.4" stroke="white" stroke-width="1.2" fill="none"/>
                    <circle cx="17" cy="15" r="0.7" fill="white" stroke="none"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </a>
                <a v-else-if="isSshPort(p)"
                   :href="sshUrl(r, p)"
                   class="myaccess-port-link myaccess-port-ssh"
                   :title="t('myaccess.ssh_title', { ip: r.ip, port: p.port })">
                  <!-- Terminal icon -->
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <rect x="1" y="1" width="14" height="14" rx="2"/>
                    <polyline points="4,5.5 7,8 4,10.5"/>
                    <line x1="8" x2="12" y1="10.5" y2="10.5"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </a>
                <a v-else-if="isSftpPort(p)"
                   :href="sftpUrl(r, p)"
                   class="myaccess-port-link myaccess-port-sftp"
                   :title="t('myaccess.sftp_title', { ip: r.ip, port: p.port })">
                  <!-- Folder icon -->
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <path d="M1 4.5C1 3.7 1.7 3 2.5 3H6l1.5 2H13.5C14.3 5 15 5.7 15 6.5V12c0 .8-.7 1.5-1.5 1.5h-11C1.7 13.5 1 12.8 1 12V4.5z"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </a>
                <a v-else-if="isSmbPort(p)"
                   :href="smbUrl(r, p)"
                   class="myaccess-port-link myaccess-port-smb"
                   :title="t('myaccess.smb_title', { ip: r.ip })">
                  <!-- Network folder icon -->
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <path d="M1 4.5C1 3.7 1.7 3 2.5 3H6l1.5 2H13.5C14.3 5 15 5.7 15 6.5V12c0 .8-.7 1.5-1.5 1.5h-11C1.7 13.5 1 12.8 1 12V4.5z"/>
                    <line x1="5.5" x2="5.5" y1="8" y2="11"/>
                    <line x1="8" x2="8" y1="8" y2="11"/>
                    <line x1="10.5" x2="10.5" y1="8" y2="11"/>
                    <line x1="4" x2="12" y1="8" y2="8"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </a>
                <a v-else-if="isPrintPort(p)"
                   :href="ippUrl(r, p)"
                   class="myaccess-port-link myaccess-port-print"
                   :title="t('myaccess.print_title', { ip: r.ip, port: p.port })">
                  <!-- Printer icon -->
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <rect x="3" y="1" width="10" height="4" rx="1"/>
                    <path d="M3 5H1.5A.5.5 0 0 0 1 5.5v5A.5.5 0 0 0 1.5 11H3"/>
                    <path d="M13 5h1.5a.5.5 0 0 1 .5.5v5a.5.5 0 0 1-.5.5H13"/>
                    <rect x="3" y="8" width="10" height="7" rx="1"/>
                    <line x1="5" x2="11" y1="11" y2="11"/>
                    <line x1="5" x2="11" y1="13" y2="13"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || p.protocol }}</span>
                </a>
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
      </section>
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
