import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale, formatDate } from "/js/i18n.js";
import { onEscape } from "/js/keyboard.js";
import TopologyDiagram from "/js/TopologyDiagram.js";
import TopologyWorldMap from "/js/TopologyWorldMap.js";
import PortalActivityHeatmap from "/js/PortalActivityHeatmap.js";

// Self-service view: an org user manages their own devices. No site peers,
// no IP picker (the server chooses), no other user's data.
// Endpoints used:
//   GET    /api/v1/peers/mine               — list own peers
//   POST   /api/v1/peers/mine                — add a device
//   GET    /api/v1/peers/mine/{id}/conf      — re-show .conf (plaintext only)
//   PUT    /api/v1/peers/mine/{id}/public-key — rotate the public key
export default defineComponent({
  name: "MyAccessView",
  components: { Icon, TopologyDiagram, TopologyWorldMap, PortalActivityHeatmap },
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
      // Exclusive-capacity reservations (#72), keyed by resource id: the
      // duration currently picked, the in-flight request, and the last error
      // (kept per resource so one failure doesn't blank another card).
      rsvMinutes: {},
      rsvBusy: null,
      rsvError: {},
      loading: true,
      error: null,
      // Grants section tabs: "list" (default), "topology", "map", "activity" —
      // topology/map/activity data is fetched lazily on first activation,
      // not up front, since most users just want the plain resource list.
      grantsTab: "list",
      topology: null,        // portal-scoped DashboardDto.Topology, or null until loaded
      topologyLoading: false,
      topologyError: null,
      activityLoaded: false, // PortalActivityHeatmap fetches its own data; just gate first mount
      // userId the view is scoped to (null = own, set = admin impersonation)
      viewAsUserId: null,
      viewAsUserName: null,
      modalMode: null, // null | "create" | "secret" | "rotate"
      // create form
      newDevice: { name: "", publicKey: "", category: "stationary" },
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
      // IronRDP browser client
      ironRdpEnabled: false,   // gates the "open in browser" button; comes from /my-resources
      rdpDialog: null,
      rdpCreds: { username: "", password: "", domain: "" },
      rdpShowPassword: false,
      rdpOverlay: null,
      rdpModule: null,
      rdpActiveSession: null,
      rdpStatus: "",
      rdpError: "",
    };
  },
  computed: {
    _lang() { return locale.current; },
    // Same 2-geocoded-sites gate as the admin dashboard's map tab (ADR-0021)
    // — a hub plus a single remote network already clears it.
    worldMapAvailable() {
      if (!this.topology) return false;
      const hasHub = this.topology.hubLat != null && this.topology.hubLon != null;
      const geocodedSites = this.topology.sites.filter((s) => s.gatewayLat != null && s.gatewayLng != null).length;
      return hasHub && geocodedSites >= 1;
    },
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
    // Guarded on modalMode/rdpDialog being set — both are already null while
    // an RDP session's own canvas keydown forwarding is active, so this never
    // competes with keys meant for the remote session.
    this._offEscape = onEscape(() => {
      if (this.rdpDialog) this.closeRdpDialog();
      else if (this.modalMode) this.closeModal();
    });
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

    // -- exclusive-capacity reservations (#72) ------------------------------
    /** The granted ports of this resource that carry a capacity limit. */
    reservablePorts(r) {
      return (r.grantedPorts || []).filter(p => p.maxConcurrentUsers);
    },
    /**
     * True when this port is capacity-limited and the user holds no live slot
     * — nftables will drop traffic to it, so the UI must not present it as
     * ready to click.
     */
    needsReservation(p) {
      return !!p.maxConcurrentUsers && p.myReservationStatus !== 'active';
    },
    /** True when *every* granted port on this resource is gated and unheld. */
    allPortsLocked(r) {
      const ports = r.grantedPorts || [];
      return ports.length > 0 && ports.every(p => this.needsReservation(p));
    },
    /** The offered ladder, trimmed to the port's own ceiling. */
    durationChoices(p) {
      const all = [60, 240, 1440, 10080];
      if (!p.maxReservationMinutes) return all;
      const fits = all.filter(m => m <= p.maxReservationMinutes);
      // Always offer at least the shortest option, even on a port capped below
      // it — the server trims to the ceiling anyway, and an empty picker would
      // leave the user unable to ask at all.
      return fits.length ? fits : [all[0]];
    },
    durationLabel(minutes) {
      if (minutes % 1440 === 0) return t('myaccess.rsv_days', { n: minutes / 1440 });
      if (minutes % 60 === 0) return t('myaccess.rsv_hours', { n: minutes / 60 });
      return t('myaccess.rsv_minutes', { n: minutes });
    },
    shortTime(iso) {
      if (!iso) return "";
      try {
        return new Date(iso).toLocaleTimeString(locale.current === 'de' ? 'de-DE' : 'en-GB',
            { hour: '2-digit', minute: '2-digit' });
      } catch (e) { return ""; }
    },
    /**
     * How long until the slot frees up, phrased as a wait rather than a clock
     * time: "noch 42 Min" answers the question the waiting user actually has.
     * Falls back to the absolute time once the wait runs past a day, where a
     * relative figure stops being useful.
     */
    waitLabel(iso) {
      if (!iso) return "";
      const mins = Math.ceil((new Date(iso).getTime() - Date.now()) / 60000);
      if (!isFinite(mins) || mins <= 0) return t('myaccess.rsv_wait_any_moment');
      if (mins < 60) return t('myaccess.rsv_wait_minutes', { n: mins });
      if (mins < 1440) return t('myaccess.rsv_wait_hours', { n: Math.round(mins / 60) });
      return this.shortTime(iso);
    },
    /** Pre-addressed mail to whoever holds the port, so asking is one click. */
    mailtoHolder(r, p, holder) {
      const subject = t('myaccess.rsv_mail_subject', {
        resource: r.name, port: p.port + '/' + p.transport,
      });
      return 'mailto:' + encodeURIComponent(holder.userEmail) +
             '?subject=' + encodeURIComponent(subject);
    },
    async requestReservation(p) {
      this.rsvBusy = p.id;
      this.rsvError = { ...this.rsvError, [p.id]: "" };
      try {
        const minutes = this.rsvMinutes[p.id] || this.durationChoices(p)[0];
        const res = await fetch("/api/v1/reservations", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ portId: p.id, minutes }),
        });
        if (res.status === 409) {
          // At capacity — name the holder rather than a bare failure, which is
          // the whole point of rejecting instead of queueing (#72).
          const body = await res.json();
          const h = (body.holders || [])[0];
          this.rsvError = { ...this.rsvError, [p.id]: h
              ? t('myaccess.rsv_at_capacity_by', { name: h.userName, wait: this.waitLabel(h.until) })
              : t('myaccess.rsv_at_capacity') };
          await this.loadGrants();
          return;
        }
        if (!res.ok) throw new Error(await res.text());
        await this.loadGrants();
      } catch (e) {
        this.rsvError = { ...this.rsvError, [p.id]: t('myaccess.rsv_failed') };
      } finally {
        this.rsvBusy = null;
      }
    },
    async releaseReservation(p) {
      if (!p.myReservationId) return;
      this.rsvBusy = p.id;
      this.rsvError = { ...this.rsvError, [p.id]: "" };
      try {
        const res = await fetch("/api/v1/reservations/" + p.myReservationId, { method: "DELETE" });
        if (!res.ok) throw new Error(await res.text());
        await this.loadGrants();
      } catch (e) {
        this.rsvError = { ...this.rsvError, [p.id]: t('myaccess.rsv_failed') };
      } finally {
        this.rsvBusy = null;
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
        const data = await res.json();
        this.grants = data.resources;
        this.ironRdpEnabled = !!data.ironRdpEnabled;
        // Seed the duration picker per reservable resource. Without this the
        // v-model is undefined, matches no <option>, and the select renders
        // blank — the user would have to open it before the button works.
        const seeded = { ...this.rsvMinutes };
        for (const r of this.grants) {
          for (const p of r.grantedPorts || []) {
            if (p.maxConcurrentUsers && seeded[p.id] == null) {
              seeded[p.id] = this.durationChoices(p)[0];
            }
          }
        }
        this.rsvMinutes = seeded;
      } catch {
        // non-fatal — grants section simply stays empty
      } finally {
        this.grantsLoading = false;
      }
    },

    // Topology/map/activity are lazy: fetched on first tab activation, not
    // alongside load()/loadGrants() — most visits just want the plain list.
    async selectGrantsTab(tab) {
      this.grantsTab = tab;
      if ((tab === "topology" || tab === "map") && !this.topology && !this.topologyLoading) {
        await this.loadTopology();
      }
      if (tab === "activity") this.activityLoaded = true;
    },
    async loadTopology() {
      this.topologyLoading = true;
      this.topologyError = null;
      try {
        const url = this.viewAsUserId
            ? "/api/v1/acl/my-resources/topology?userId=" + encodeURIComponent(this.viewAsUserId)
            : "/api/v1/acl/my-resources/topology";
        const res = await fetch(url);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.topology = await res.json();
      } catch (e) {
        this.topologyError = t("myaccess.topology_error", { error: e.message });
      } finally {
        this.topologyLoading = false;
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
      this.newDevice = { name: "", publicKey: "", category: "stationary" };
      this.importPublicKey = false;
      this.formError = null;
      this.secret = null;
    },

    async submitCreate() {
      if (!this.newDevice.name.trim()) {
        this.formError = t("myaccess.err_name");
        return;
      }
      const payload = {
        name: this.newDevice.name.trim(),
        deviceType: this.newDevice.category === "mobile" ? "mobile" : "desktop",
      };
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

    formatDate(iso) { return formatDate(iso); },

    // ── IronRDP browser-RDP ──────────────────────────────────────────────────

    openRdpDialog(resource, port) {
      this.rdpDialog = { resource, port };
      this.rdpShowPassword = false;
      this.rdpError = "";
    },
    closeRdpDialog() { this.rdpDialog = null; },

    // A per-resource URI the user can store in their password manager. Built on the
    // real islandr origin (which KeePassXC / Bitwarden match on) with the resource
    // name in the route, so each RDP resource maps to a distinct, named vault entry.
    rdpMatchUri(resource) {
      return window.location.origin + "/#/my-access/" + encodeURIComponent(resource.name);
    },

    async connectRdpInBrowser() {
      const { resource, port } = this.rdpDialog;
      this.rdpDialog = null;
      this.rdpOverlay = { resource, port };
      this.rdpStatus = "connecting";
      this.rdpError = "";

      try {
        if (!this.rdpModule) {
          const mod = await import("/vendor/ironrdp/rdp_client.js");
          await mod.default("/vendor/ironrdp/rdp_client_bg.wasm");
          mod.setup("WARN");
          this.rdpModule = mod;
        }

        await this.$nextTick();
        const canvas = this.$refs.rdpCanvas;
        canvas.focus();

        const w = Math.max(canvas.offsetWidth, 800);
        const h = Math.max(canvas.offsetHeight, 600);
        const wsScheme = location.protocol === "https:" ? "wss" : "ws";
        let proxyUrl = `${wsScheme}://${location.host}/api/v1/rdp/proxy/${port.id}`;
        // When an admin previews another user's access, tell the proxy to gate on
        // that user's grants (the same ?as= the my-resources view uses).
        if (this.viewAsUserId) proxyUrl += `?as=${encodeURIComponent(this.viewAsUserId)}`;

        const { SessionBuilder, DesktopSize } = this.rdpModule;
        let b = new SessionBuilder()
          .proxyAddress(proxyUrl)
          // The WASM client requires a non-empty auth token (RDCleanPath / Devolutions
          // Gateway convention — normally a JWT). Our proxy does NOT use it: it
          // authenticates via the session cookie on the WS handshake plus the per-port
          // grant check (RdpProxyEndpoint). A placeholder just satisfies the client so
          // connect() proceeds instead of throwing "auth_token missing".
          .authToken("islandr")
          .destination(`${resource.ip}:${port.port}`)
          .renderCanvas(canvas)
          .desktopSize(new DesktopSize(w, h))
          // connect() requires a cursor-style callback + its context. The WASM hands
          // us a ready-to-use CSS cursor value; apply it to the canvas. Context is the
          // canvas itself (matches the IronRDP web reference client).
          .setCursorStyleCallback((style) => { canvas.style.cursor = style || "default"; })
          .setCursorStyleCallbackContext(canvas);

        if (this.rdpCreds.username) b = b.username(this.rdpCreds.username);
        if (this.rdpCreds.password) b = b.password(this.rdpCreds.password);
        if (this.rdpCreds.domain)   b = b.serverDomain(this.rdpCreds.domain);

        const session = await b.connect();
        this.rdpActiveSession = session;
        this.rdpStatus = "connected";
        this.setupRdpInput(canvas, session);

        await session.run();

        if (this.rdpOverlay) this.rdpStatus = "disconnected";
      } catch (e) {
        // Surface the real error + stack in the browser console — the failure is
        // otherwise invisible (a connect() failure before the WebSocket opens
        // never reaches the server log). Helps tell a client-side (WASM/canvas)
        // failure from a proxy/handshake one.
        const iron = this.decodeIronError(e);
        console.error("Browser-RDP failed:", iron || e, e && e.stack);
        // kind() alone (esp. General) is not enough — backtrace() carries the real
        // underlying message. Log it on its own line so it is readable, uncollapsed.
        if (iron && iron.backtrace) console.error("IronError backtrace:\n" + iron.backtrace);
        if (this.rdpOverlay) {
          const reached = this.rdpStatus === "connected";
          this.rdpStatus = "error";
          this.rdpError = this.rdpErrorMessage(e, reached, iron);
        }
      }
    },

    // IronError (the WASM client's thrown type) is an opaque wasm-bindgen handle
    // — it renders as {__wbg_ptr:…} and has no .message. But it exposes kind(),
    // backtrace(), and rdcleanpathDetails() (HTTP status / TLS alert / WSA code).
    // Pull those out so the real reason is visible instead of a bare pointer.
    decodeIronError(e) {
      if (!e || typeof e.kind !== "function") return null;
      const KINDS = {
        0: t("myaccess.rdp_kind_0"),
        1: t("myaccess.rdp_kind_1"),
        2: t("myaccess.rdp_kind_2"),
        3: t("myaccess.rdp_kind_3"),
        4: t("myaccess.rdp_kind_4"),
        5: t("myaccess.rdp_kind_5"),
        6: t("myaccess.rdp_kind_6"),
      };
      const TLS = {
        40: t("myaccess.rdp_tls_40"), 42: t("myaccess.rdp_tls_42"), 45: t("myaccess.rdp_tls_45"),
        48: t("myaccess.rdp_tls_48"), 112: t("myaccess.rdp_tls_112"),
      };
      const WSA = {
        10013: t("myaccess.rdp_wsa_10013"), 10060: t("myaccess.rdp_wsa_10060"),
        10061: t("myaccess.rdp_wsa_10061"), 10051: t("myaccess.rdp_wsa_10051"),
        10065: t("myaccess.rdp_wsa_10065"),
      };
      const out = {};
      try { out.kind = e.kind(); out.kindLabel = KINDS[out.kind] || ("Kind " + out.kind); } catch {}
      try { out.backtrace = e.backtrace(); } catch {}
      try {
        const d = typeof e.rdcleanpathDetails === "function" ? e.rdcleanpathDetails() : undefined;
        if (d) {
          if (d.httpStatusCode !== undefined) out.http = d.httpStatusCode;
          if (d.tlsAlertCode !== undefined) { out.tlsAlert = d.tlsAlertCode; out.tlsAlertLabel = TLS[d.tlsAlertCode]; }
          if (d.wsaErrorCode !== undefined) { out.wsa = d.wsaErrorCode; out.wsaLabel = WSA[d.wsaErrorCode]; }
          try { d.free(); } catch {}
        }
      } catch {}
      return out;
    },

    // Turn the WASM client's thrown value into a readable message. A structured
    // IronError (iron) gives the concrete reason (kind + TLS/WSA/HTTP detail);
    // otherwise fall back to message/toString. reached=false means connect()
    // failed before the RDP session started. Never render "[object Object]".
    rdpErrorMessage(e, reached, iron) {
      if (iron && iron.kindLabel) {
        const bits = [iron.kindLabel];
        if (iron.tlsAlert !== undefined) bits.push(t("myaccess.rdp_tls_alert") + " " + iron.tlsAlert + (iron.tlsAlertLabel ? " (" + iron.tlsAlertLabel + ")" : ""));
        if (iron.wsa !== undefined) bits.push(t("myaccess.rdp_socket_err") + " " + iron.wsa + (iron.wsaLabel ? " (" + iron.wsaLabel + ")" : ""));
        if (iron.http !== undefined) bits.push("HTTP " + iron.http);
        // For General (kind 0) with no coded detail, the backtrace is the only real
        // signal — surface its first line so the user isn't left with a bare label.
        if (iron.kind === 0 && iron.tlsAlert === undefined && iron.wsa === undefined && iron.http === undefined && iron.backtrace) {
          const first = String(iron.backtrace).split("\n").map(s => s.trim()).filter(Boolean)[0];
          if (first) bits.push(first);
        }
        return t("myaccess.rdp_fail") + ": " + bits.join(" — ") + ".";
      }
      let detail = "";
      if (e != null) {
        if (typeof e === "string") detail = e;
        else if (e.message) detail = String(e.message);
        else {
          try { const s = e.toString(); if (s && s !== "[object Object]") detail = s; } catch {}
          // Skip opaque wasm-bindgen handles ({"__wbg_ptr":…}) — just internal noise.
          if (!detail) { try { const j = JSON.stringify(e); if (j && j !== "{}" && !j.includes("__wbg_ptr")) detail = j; } catch {} }
        }
      }
      if (reached) {
        return detail ? (t("myaccess.rdp_session_fail") + ": " + detail) : t("myaccess.rdp_session_ended");
      }
      return t("myaccess.rdp_fail_hint")
        + (detail ? " (" + detail + ")" : "");
    },

    closeRdpOverlay() {
      if (this.rdpActiveSession) {
        try { this.rdpActiveSession.shutdown(); } catch {}
        this.rdpActiveSession = null;
      }
      this.rdpOverlay = null;
      this.rdpStatus = "";
      this.rdpError = "";
    },

    setupRdpInput(canvas, session) {
      const SCANCODES = {
        Escape:0x01,Digit1:0x02,Digit2:0x03,Digit3:0x04,Digit4:0x05,
        Digit5:0x06,Digit6:0x07,Digit7:0x08,Digit8:0x09,Digit9:0x0A,
        Digit0:0x0B,Minus:0x0C,Equal:0x0D,Backspace:0x0E,Tab:0x0F,
        KeyQ:0x10,KeyW:0x11,KeyE:0x12,KeyR:0x13,KeyT:0x14,KeyY:0x15,
        KeyU:0x16,KeyI:0x17,KeyO:0x18,KeyP:0x19,BracketLeft:0x1A,
        BracketRight:0x1B,Enter:0x1C,ControlLeft:0x1D,
        KeyA:0x1E,KeyS:0x1F,KeyD:0x20,KeyF:0x21,KeyG:0x22,KeyH:0x23,
        KeyJ:0x24,KeyK:0x25,KeyL:0x26,Semicolon:0x27,Quote:0x28,
        Backquote:0x29,ShiftLeft:0x2A,Backslash:0x2B,
        KeyZ:0x2C,KeyX:0x2D,KeyC:0x2E,KeyV:0x2F,KeyB:0x30,KeyN:0x31,
        KeyM:0x32,Comma:0x33,Period:0x34,Slash:0x35,ShiftRight:0x36,
        NumpadMultiply:0x37,AltLeft:0x38,Space:0x39,CapsLock:0x3A,
        F1:0x3B,F2:0x3C,F3:0x3D,F4:0x3E,F5:0x3F,F6:0x40,
        F7:0x41,F8:0x42,F9:0x43,F10:0x44,NumLock:0x45,ScrollLock:0x46,
        Numpad7:0x47,Numpad8:0x48,Numpad9:0x49,NumpadSubtract:0x4A,
        Numpad4:0x4B,Numpad5:0x4C,Numpad6:0x4D,NumpadAdd:0x4E,
        Numpad1:0x4F,Numpad2:0x50,Numpad3:0x51,Numpad0:0x52,
        NumpadDecimal:0x53,F11:0x57,F12:0x58,
        NumpadEnter:0xE01C,ControlRight:0xE01D,NumpadDivide:0xE035,
        PrintScreen:0xE037,AltRight:0xE038,Home:0xE047,ArrowUp:0xE048,
        PageUp:0xE049,ArrowLeft:0xE04B,ArrowRight:0xE04D,End:0xE04F,
        ArrowDown:0xE050,PageDown:0xE051,Insert:0xE052,Delete:0xE053,
        MetaLeft:0xE05B,MetaRight:0xE05C,ContextMenu:0xE05D,
      };
      const { DeviceEvent, InputTransaction } = this.rdpModule;
      const send = (evt) => {
        try { const t = new InputTransaction(); t.addEvent(evt); session.applyInputs(t); } catch {}
      };
      canvas.addEventListener("keydown", (e) => {
        e.preventDefault(); e.stopPropagation();
        const sc = SCANCODES[e.code]; if (sc != null) send(DeviceEvent.keyPressed(sc));
      });
      canvas.addEventListener("keyup", (e) => {
        e.preventDefault();
        const sc = SCANCODES[e.code]; if (sc != null) send(DeviceEvent.keyReleased(sc));
      });
      canvas.addEventListener("mousemove", (e) => {
        const r = canvas.getBoundingClientRect();
        const x = Math.round((e.clientX - r.left) * (canvas.width / r.width));
        const y = Math.round((e.clientY - r.top) * (canvas.height / r.height));
        send(DeviceEvent.mouseMove(x, y));
      });
      canvas.addEventListener("mousedown", (e) => {
        e.preventDefault(); canvas.focus(); send(DeviceEvent.mouseButtonPressed(e.button));
      });
      canvas.addEventListener("mouseup", (e) => {
        e.preventDefault(); send(DeviceEvent.mouseButtonReleased(e.button));
      });
      canvas.addEventListener("wheel", (e) => {
        e.preventDefault();
        if (e.deltaY) send(DeviceEvent.wheelRotations(true,  e.deltaY > 0 ? -1 : 1, 1));
        if (e.deltaX) send(DeviceEvent.wheelRotations(false, e.deltaX > 0 ? -1 : 1, 1));
      }, { passive: false });
      canvas.addEventListener("contextmenu", (e) => e.preventDefault());
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

        <div v-if="grants.length > 0" style="display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-4)">
          <button type="button" class="btn btn-sm" :class="grantsTab === 'list' ? 'btn-secondary' : 'btn-ghost'"
                  @click="selectGrantsTab('list')">{{ t('myaccess.tab_list') }}</button>
          <button type="button" class="btn btn-sm" :class="grantsTab === 'topology' ? 'btn-secondary' : 'btn-ghost'"
                  @click="selectGrantsTab('topology')">{{ t('myaccess.tab_topology') }}</button>
          <button type="button" class="btn btn-sm" :class="grantsTab === 'map' ? 'btn-secondary' : 'btn-ghost'"
                  @click="selectGrantsTab('map')">{{ t('myaccess.tab_map') }}</button>
          <button type="button" class="btn btn-sm" :class="grantsTab === 'activity' ? 'btn-secondary' : 'btn-ghost'"
                  @click="selectGrantsTab('activity')">{{ t('myaccess.tab_activity') }}</button>
        </div>

      <template v-if="grantsTab === 'list'">
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
            <!-- Exclusive-capacity ports (#72): a grant puts the port in this
                 list, but reaching it needs a live reservation on top. The
                 strip sits per port, not per host — one seat taken on RDP
                 leaves the same machine's SSH port untouched. -->
            <div v-for="p in reservablePorts(r)" :key="'rsv-' + p.id" class="myaccess-reservation">
              <span class="mono" style="font-size:var(--text-xs); color:var(--fg2)">
                {{ p.port }}/{{ p.transport }} {{ p.label || p.protocol }}
              </span>
              <template v-if="p.myReservationStatus === 'active'">
                <span class="badge badge-success">
                  <Icon name="clock" :size="12" />
                  {{ t('myaccess.rsv_held_until', { time: shortTime(p.myReservationEndsAt) }) }}
                </span>
                <button class="btn btn-ghost btn-sm" :disabled="rsvBusy === p.id"
                        @click="releaseReservation(p)">{{ t('myaccess.rsv_release') }}</button>
              </template>
              <template v-else-if="p.myReservationStatus === 'pending'">
                <span class="badge badge-warning">
                  <Icon name="clock" :size="12" />{{ t('myaccess.rsv_pending') }}
                </span>
                <button class="btn btn-ghost btn-sm" :disabled="rsvBusy === p.id"
                        @click="releaseReservation(p)">{{ t('myaccess.rsv_withdraw') }}</button>
              </template>
              <template v-else>
                <span class="badge badge-neutral">
                  <Icon name="clock" :size="12" />{{ t('myaccess.rsv_on_demand') }}
                </span>
                <template v-if="p.holders && p.holders.length">
                  <span style="font-size:var(--text-xs);color:var(--fg3)">
                    {{ t('myaccess.rsv_in_use_by', {
                         name: p.holders[0].userName, wait: waitLabel(p.holders[0].until) }) }}
                  </span>
                  <a v-if="p.holders[0].userEmail"
                     :href="mailtoHolder(r, p, p.holders[0])"
                     class="myaccess-holder-mail"
                     :title="t('myaccess.rsv_contact_title', { name: p.holders[0].userName })">
                    <Icon name="mail" :size="12" />{{ p.holders[0].userEmail }}
                  </a>
                </template>
                <select class="select select-sm" v-model.number="rsvMinutes[p.id]"
                        :aria-label="t('myaccess.rsv_duration')">
                  <option v-for="d in durationChoices(p)" :key="d" :value="d">{{ durationLabel(d) }}</option>
                </select>
                <button class="btn btn-primary btn-sm" :disabled="rsvBusy === p.id"
                        @click="requestReservation(p)">{{ t('myaccess.rsv_request') }}</button>
              </template>
              <span v-if="rsvError[p.id]" style="font-size:var(--text-xs);color:var(--danger)">{{ rsvError[p.id] }}</span>
            </div>
            <div class="myaccess-ports">
              <span v-if="r.grantedPorts.length === 0" style="font-size:var(--text-xs);color:var(--fg3)">{{ t('myaccess.no_ports') }}</span>
              <template v-for="p in r.grantedPorts" :key="p.id">
                <span class="myaccess-port-slot"
                      :class="{ 'myaccess-port-slot--locked': needsReservation(p) }"
                      :title="needsReservation(p) ? t('myaccess.rsv_ports_locked') : null">
                <a v-if="isWebPort(p)"
                   :href="httpUrl(r, p)"
                   target="_blank"
                   rel="noopener noreferrer"
                   class="myaccess-port-link"
                   :title="httpUrl(r, p) + ' — ' + t('myaccess.open_in_browser')">
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.protocol }}</span>
                  <span v-if="p.label" style="color:var(--fg3)">{{ p.label }}</span>
                  <Icon name="external-link" :size="11" style="opacity:.6; flex-shrink:0" />
                </a>
                <div v-else-if="isRdpPort(p)" class="myaccess-port-rdp-group">
                  <!-- Native buttons: hidden for web-only mode -->
                  <template v-if="p.rdpAccessMode !== 'web-only'">
                    <button @click="downloadRdp(r, p)"
                       class="myaccess-port-link myaccess-port-rdp myaccess-port-rdp-left"
                       :title="t('myaccess.rdp_title', { ip: r.ip, port: p.port })">
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
                      <svg width="14" height="13" viewBox="0 0 18 16" fill="none" stroke="currentColor"
                           stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                           style="flex-shrink:0" aria-hidden="true">
                        <rect x="1" y="1" width="16" height="10" rx="2"/>
                        <line x1="5" x2="13" y1="15" y2="15"/>
                        <line x1="9" x2="9" y1="11" y2="15"/>
                      </svg>
                      <span>{{ t('myaccess.rdp_client_label') }}</span>
                    </a>
                  </template>
                  <!-- Browser button: only when the admin has enabled browser-based RDP -->
                  <button v-if="ironRdpEnabled" @click="openRdpDialog(r, p)"
                     :class="['myaccess-port-link', 'myaccess-port-rdp', 'myaccess-port-rdp-browser',
                              p.rdpAccessMode === 'web-only' ? '' : 'myaccess-port-rdp-browser-adj']"
                     :title="t('myaccess.open_in_browser')">
                    <!-- Monitor + globe badge -->
                    <svg width="18" height="16" viewBox="0 0 22 20" fill="none" stroke="currentColor"
                         stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                         style="flex-shrink:0" aria-hidden="true">
                      <rect x="1" y="1" width="16" height="11" rx="2"/>
                      <line x1="5" x2="13" y1="16" y2="16"/>
                      <line x1="9" x2="9" y1="12" y2="16"/>
                      <!-- globe badge -->
                      <circle cx="17" cy="15" r="4" fill="var(--accent,#3BBBD2)" stroke="none"/>
                      <circle cx="17" cy="15" r="2.5" stroke="white" stroke-width="1.1" fill="none"/>
                      <line x1="17" y1="12.5" x2="17" y2="17.5" stroke="white" stroke-width="1.1"/>
                      <line x1="13" y1="15" x2="21" y2="15" stroke="white" stroke-width="1.1"/>
                    </svg>
                    <span v-if="p.rdpAccessMode === 'web-only'" class="mono">{{ p.port }}/{{ p.transport }}</span>
                    <span>{{ t('myaccess.open_in_browser') }}</span>
                  </button>
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
                  <!-- Printer icon + install badge (same corner-badge convention as RDP/VNC above) -->
                  <svg width="18" height="16" viewBox="0 0 22 20" fill="none" stroke="currentColor"
                       stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
                       style="flex-shrink:0" aria-hidden="true">
                    <rect x="1" y="2" width="12" height="3.5" rx="1"/>
                    <rect x="1" y="7" width="12" height="8" rx="1.2"/>
                    <line x1="3.5" x2="9" y1="10" y2="10"/>
                    <line x1="3.5" x2="9" y1="12.5" y2="12.5"/>
                    <circle cx="17" cy="15" r="4" fill="var(--accent,#0E97B8)" stroke="none"/>
                    <line x1="17" y1="13" x2="17" y2="17" stroke="white" stroke-width="1.3" stroke-linecap="round"/>
                    <line x1="15" y1="15" x2="19" y2="15" stroke="white" stroke-width="1.3" stroke-linecap="round"/>
                  </svg>
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.label || t('myaccess.print_action') }}</span>
                </a>
                <span v-else class="myaccess-port-chip">
                  <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                  <span>{{ p.protocol }}</span>
                  <span v-if="p.label" style="color:var(--fg3)">{{ p.label }}</span>
                </span>
                </span>
              </template>
            </div>
          </div>
        </template>
        </div>
      </template>

      <!-- Topology: same radial diagram as the admin dashboard, scoped to
           just the networks/resources this user has grants on (#43) —
           a network with zero grants never appears here at all. -->
      <template v-if="grantsTab === 'topology'">
        <div v-if="topologyLoading" class="muted">{{ t('common.loading') }}</div>
        <div v-else-if="topologyError" class="error-banner">{{ topologyError }}</div>
        <div v-else-if="!topology || topology.sites.length === 0" class="empty-state">
          <h2>{{ t('myaccess.grants_empty_title') }}</h2>
          <p>{{ t('myaccess.grants_empty_desc', { user: viewAsUserName || 'dir' }) }}</p>
        </div>
        <TopologyDiagram v-else portal
            :sites="topology.sites"
            :resources="topology.resources"
            :resource-overflow="topology.resourceOverflow"
            :hub-label="topology.hubLabel" />
      </template>

      <!-- Geo map: same component/data as above, just the other of the two
           admin-dashboard topology views. -->
      <template v-if="grantsTab === 'map'">
        <div v-if="topologyLoading" class="muted">{{ t('common.loading') }}</div>
        <div v-else-if="topologyError" class="error-banner">{{ topologyError }}</div>
        <div v-else-if="!topology || !worldMapAvailable" class="muted">{{ t('myaccess.map_unavailable') }}</div>
        <TopologyWorldMap v-else portal
            :sites="topology.sites"
            :hub-lat="topology.hubLat"
            :hub-lon="topology.hubLon"
            :hub-label="topology.hubLabel" />
      </template>

      <!-- Own-activity heatmap: GitHub-contributions layout, own peer(s) only. -->
      <template v-if="grantsTab === 'activity'">
        <PortalActivityHeatmap v-if="activityLoaded" :user-id="viewAsUserId" />
      </template>
      </section>
    </div>

    <div v-if="!viewAsUserId && modalMode" class="modal-backdrop" @click.self="closeModal">

      <div v-if="modalMode === 'create'" class="modal">
        <div class="modal-header">
          <h2>{{ t('myaccess.modal_new_device') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submitCreate">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>

            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="devName">{{ t('myaccess.label_device_name') }}</label>
              <input id="devName" class="input" v-model="newDevice.name" required :placeholder="t('myaccess.ph_device_name')" />
              <div class="field-hint">{{ t('myaccess.hint_device_name') }}</div>
            </div>

            <fieldset class="key-mode" style="margin-bottom: var(--space-4)">
              <legend>{{ t('myaccess.category_section') }}</legend>
              <label class="key-mode-option">
                <input type="radio" :checked="newDevice.category === 'stationary'" @change="newDevice.category = 'stationary'" />
                <div>
                  <div class="key-mode-title">{{ t('myaccess.category_stationary') }}</div>
                  <div class="key-mode-hint">{{ t('myaccess.category_stationary_hint') }}</div>
                </div>
              </label>
              <label class="key-mode-option">
                <input type="radio" :checked="newDevice.category === 'mobile'" @change="newDevice.category = 'mobile'" />
                <div>
                  <div class="key-mode-title">{{ t('myaccess.category_mobile') }}</div>
                  <div class="key-mode-hint">{{ t('myaccess.category_mobile_hint') }}</div>
                </div>
              </label>
            </fieldset>

            <fieldset class="key-mode">
              <legend>{{ t('peer.key_section') }}</legend>
              <label class="key-mode-option">
                <input type="radio" :checked="!importPublicKey" @change="importPublicKey = false" />
                <div>
                  <div class="key-mode-title">{{ t('peer.key_generate') }}</div>
                  <div class="key-mode-hint">{{ t('myaccess.hint_generate') }}</div>
                </div>
              </label>
              <label class="key-mode-option">
                <input type="radio" :checked="importPublicKey" @change="importPublicKey = true" />
                <div>
                  <div class="key-mode-title">{{ t('peer.key_import') }}</div>
                  <div class="key-mode-hint">{{ t('myaccess.hint_own_key') }}</div>
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
              {{ t('myaccess.rotate_hint_a') }}<code>wg genkey | tee priv | wg pubkey</code>{{ t('myaccess.rotate_hint_b') }}
            </div>
            <div class="field">
              <label for="rotKey">{{ t('myaccess.label_new_pubkey') }}</label>
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
              {{ t('myaccess.secret_imported_a') }}<code>PrivateKey</code>{{ t('myaccess.secret_imported_b') }}
            </div>
          </div>
          <div v-else-if="secretIsReshow && secret.privateKey" class="callout callout-info">
            <div>
              {{ t('peer.warn_reshow') }}
            </div>
          </div>

          <div class="secret-block" :class="{ 'secret-block-no-qr': !secret.qrPngBase64 }">
            <div v-if="secret.qrPngBase64" class="qr">
              <img :src="secret.qrPngBase64" :alt="t('peer.qr_alt')" />
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
    <!-- IronRDP credential dialog -->
    <div v-if="rdpDialog" class="modal-backdrop" @click.self="closeRdpDialog">
      <div class="modal" style="max-width: 400px">
        <div class="modal-header">
          <h3 style="margin:0; font-size: var(--text-md)">{{ t('myaccess.rdp_dialog_title') }}</h3>
        </div>
        <div class="modal-body">
          <div class="muted" style="margin-bottom: var(--space-4); font-size: var(--text-sm)">
            {{ rdpDialog.resource.name }} &mdash;
            {{ rdpDialog.port.label || rdpDialog.port.protocol }}
            ({{ rdpDialog.resource.ip }}:{{ rdpDialog.port.port }})
          </div>
          <form id="rdp-cred-form" @submit.prevent="connectRdpInBrowser">
            <div class="form-group">
              <label class="label" for="rdpUser">{{ t('myaccess.label_username') }}</label>
              <input id="rdpUser" name="username" class="input" v-model="rdpCreds.username"
                     type="text" autocomplete="username" :placeholder="t('myaccess.ph_username')" />
            </div>
            <div class="form-group">
              <label class="label" for="rdpPass">{{ t('login.password') }}</label>
              <div class="input-reveal">
                <input id="rdpPass" name="password" class="input" v-model="rdpCreds.password"
                       :type="rdpShowPassword ? 'text' : 'password'" autocomplete="current-password" />
                <button type="button" class="input-reveal-btn" @click="rdpShowPassword = !rdpShowPassword"
                        :aria-label="rdpShowPassword ? t('common.pw_hide') : t('common.pw_show')"
                        :title="rdpShowPassword ? t('common.pw_hide') : t('common.pw_show')">
                  <Icon :name="rdpShowPassword ? 'eye-off' : 'eye'" :size="16" />
                </button>
              </div>
            </div>
            <div class="form-group">
              <label class="label" for="rdpDomain">{{ t('myaccess.label_domain') }} <span class="muted">(optional)</span></label>
              <input id="rdpDomain" name="domain" class="input" v-model="rdpCreds.domain"
                     type="text" autocomplete="off" :placeholder="t('myaccess.ph_domain')" />
            </div>
          </form>
          <!-- Copyable per-resource URI for the user's password manager (KeePassXC /
               Bitwarden). Autofill still keys on the islandr origin; this URI carries
               the resource name so the right vault entry is easy to find/organise. -->
          <div class="field" style="margin-top: var(--space-3)">
            <label class="label muted" style="font-size: var(--text-xs)">{{ t('myaccess.label_pwmgr_uri') }}</label>
            <div style="display: flex; gap: var(--space-2); align-items: center">
              <code class="mono" style="flex: 1; font-size: var(--text-xs); overflow-x: auto; white-space: nowrap">{{ rdpMatchUri(rdpDialog.resource) }}</code>
              <button type="button" class="btn btn-ghost btn-sm"
                      @click="copyCmd(rdpMatchUri(rdpDialog.resource), 'rdpuri')">
                {{ copiedCmd === 'rdpuri' ? t('common.copied') : t('common.copy') }}
              </button>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" @click="closeRdpDialog">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" form="rdp-cred-form" type="submit">{{ t('myaccess.btn_connect') }}</button>
        </div>
      </div>
    </div>

    <!-- IronRDP browser canvas overlay -->
    <div v-if="rdpOverlay" class="rdp-overlay">
      <div class="rdp-toolbar">
        <span class="rdp-toolbar-title">
          {{ rdpOverlay.resource.name }} &mdash; {{ rdpOverlay.port.label || rdpOverlay.port.protocol }}
          <span class="mono muted" style="font-size: var(--text-xs)">({{ rdpOverlay.resource.ip }}:{{ rdpOverlay.port.port }})</span>
        </span>
        <span v-if="rdpStatus === 'connecting'" class="muted" style="font-size: var(--text-xs)">{{ t('myaccess.rdp_connecting') }}</span>
        <span v-else-if="rdpStatus === 'disconnected'" class="muted" style="font-size: var(--text-xs)">{{ t('myaccess.rdp_disconnected') }}</span>
        <span v-else-if="rdpStatus === 'error'" style="color: var(--danger); font-size: var(--text-xs)">{{ rdpError }}</span>
        <button class="btn btn-ghost btn-sm rdp-close-btn" @click="closeRdpOverlay">{{ t('myaccess.btn_disconnect') }}</button>
      </div>
      <canvas ref="rdpCanvas" class="rdp-canvas" tabindex="0"></canvas>
    </div>
  `,
});
