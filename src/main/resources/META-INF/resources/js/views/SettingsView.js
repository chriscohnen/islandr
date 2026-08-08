import { defineComponent } from "vue";
import { t, locale, formatDate } from "/js/i18n.js";
import { Icon } from "/js/Icons.js";

// Public resolvers offered as one-click fill-ins for the client-DNS field —
// IPv4 and IPv6 addresses toggle independently, so dual-stack setups (wgSubnet6)
// can add both, and v4-only setups can ignore the v6 row entirely.
const DNS_PRESETS_V4 = [
  { name: "Quad9", value: "9.9.9.9" },
  { name: "Google", value: "8.8.8.8" },
  { name: "Cloudflare", value: "1.1.1.1" },
  { name: "AdGuard", value: "94.140.14.14" },
];
const DNS_PRESETS_V6 = [
  { name: "Quad9", value: "2620:fe::fe" },
  { name: "Google", value: "2001:4860:4860::8888" },
  { name: "Cloudflare", value: "2606:4700:4700::1111" },
  { name: "AdGuard", value: "2a10:50c0::ad1:ff" },
];

// Settings form. GET + PUT against /api/v1/settings (the singleton row).
// All WireGuard topology that ends up in client .conf files lives here —
// see docs/adr/0008-runtime-settings-in-db.md.
export default defineComponent({
  name: "SettingsView",
  components: { Icon },
  data() {
    return {
      dnsPresetsV4: DNS_PRESETS_V4,
      dnsPresetsV6: DNS_PRESETS_V6,
      // Page-level sub-tabs, so a first-time admin isn't handed all ~10 cards
      // stacked at once. "network" (WireGuard/Hub location) is the natural
      // landing tab — it's what a fresh install needs configured first.
      settingsTab: "network",
      wgSetupOpen: false,
      wgSetupCopied: null,
      wgSetupCopyFailed: null,
      loading: true,
      saving: false,
      probing: false,
      probeResult: null,
      probedIfMtu: null,
      error: null,
      info: null,
      savedRetention: "never",
      computedAllowedIpsPreview: "",
      sitesOutsideSupernetCount: 0,
      allowedIpsPreviewTimer: null,
      encryptionKeyConfigured: false,
      wgInterface: "wg0", // read-only; set via ISLANDR_WG_INTERFACE at deploy time
      form: {
        wgSubnet: "",
        wgSubnet6: "",
        wgServerPublicKey: "",
        wgServerEndpoint: "",
        wgClientAllowedIps: "",
        wgClientDns: "",
        tunnelMode: "SPLIT",
        allowedIpsMode: "MANUAL",
        splitSupernet: "",
        privateKeyRetention: "never",
        gravatarEnabled: false,
        oidcAutoProvision: true,
        firewallDryRun: true,
        selfServicePeerCreation: true,
        ironRdpEnabled: false,
        wgMtu: null,
        wgIncludeMtuInConf: false,
        wgPersistentKeepalive: 25,
        hubLat: null,
        hubLon: null,
        hubLocationLabel: "",
        activityRetentionDays: 180,
        dnsResolverEnabled: false,
        dnsResolverZone: "",
        dnsResolverUpstream: "",
      },
      meta: { updatedAt: null, updatedBy: null, setupComplete: false },
      lang: locale.current,
      configIncludePrivateKeys: false,
      configExporting: false,
      configExportError: null,
      configImportData: null,
      configImportError: null,
      configImporting: false,
      configImportResult: null,
      importFileName: "",
      versionCheck: null,
      versionChecking: false,
      enforcement: null,
      // TLS (ADR-0015) — separate mini-form with its own PUT/DELETE endpoints,
      // not bundled into the main settings save.
      tlsMode: "none",
      tlsCertExpiresAt: null,
      tlsCertInfo: null,  // { subjectCn, sans, notBefore, notAfter, issuer } | null
      tlsPemInput: "",
      tlsUploading: false,
      tlsError: null,
      tlsInfo: null,
      // ACME (ADR-0019) — a third TLS mode alongside managed/referenced, its
      // own mini-form/status like the block above.
      acmeDomain: null,
      acmeLastAttemptAt: null,
      acmeLastRenewalAt: null,
      acmeLastError: null,
      acmeDomainInput: "",
      acmeEnabling: false,
      _acmeAbort: null, // AbortController for the in-flight enableAcme() request — lets "Cancel" stop waiting on it

      // DNS-01 challenge (#41, ADR-0020) — an alternative to HTTP-01 for hubs
      // that don't want port 80 reachable. "manual" needs no API token: it
      // pauses issuance and shows the TXT record to add elsewhere yourself.
      acmeChallengeType: "http-01",
      acmeDnsProvider: null,
      acmeDnsPendingRecordName: null,
      acmeDnsPendingRecordValue: null,
      acmeChallengeTypeInput: "http-01",
      acmeDnsProviderInput: "cloudflare",
      acmeDnsApiTokenInput: "",
      dnsContinuing: false,
      dnsCopyState: "idle",
      // Pure layout split — "letsencrypt" | "origin". Defaults to whichever
      // mode is actually active so a returning admin lands where they left off.
      tlsTab: "letsencrypt",
      // Origin-certificate CSR generation (#42) — pending until a matching
      // signed certificate is uploaded, ACME is enabled, or an own key+cert
      // pair is uploaded instead (all three clear it server-side).
      pendingCsrPem: null,
      pendingCsrCreatedAt: null,
      csrDomainInput: "",
      csrGenerating: false,
      csrCopyState: "idle",
    };
  },
  async mounted() {
    // Deep links from elsewhere (e.g. FirewallView's dry-run banner) land on
    // a specific tab via ?tab=... instead of always the network default.
    const requestedTab = this.$route.query.tab;
    if (["network", "tls", "access", "users", "advanced"].includes(requestedTab)) {
      this.settingsTab = requestedTab;
    }
    await this.load();
    if (this.tlsMode === "managed") this.tlsTab = "origin";
    this.loadEnforcement();
    this.refreshAllowedIpsPreview();
  },
  watch: {
    // Live preview: recompute against unsaved form values whenever tunnel mode,
    // Auto/Manual, the supernet, or the manual value change — instead of only
    // after Save. Debounced so typing in the supernet/manual fields doesn't
    // fire a request per keystroke.
    "form.tunnelMode"() { this.scheduleAllowedIpsPreview(); },
    "form.allowedIpsMode"() { this.scheduleAllowedIpsPreview(); },
    "form.splitSupernet"() { this.scheduleAllowedIpsPreview(); },
    "form.wgClientAllowedIps"() { this.scheduleAllowedIpsPreview(); },
  },
  computed: {
    _lang() { return locale.current; },
    // R-153: a managed cert with no auto-renewal can silently expire. Warn
    // inside a 30-day window; null outside it (including dummy/no-cert state).
    // "acme" mode renews itself (ADR-0019) but still shows this — a stalled
    // renewal (R-166) should be just as visible as a forgotten manual one.
    tlsDaysUntilExpiry() {
      if ((this.tlsMode !== "managed" && this.tlsMode !== "acme") || !this.tlsCertExpiresAt) return null;
      const ms = new Date(this.tlsCertExpiresAt).getTime() - Date.now();
      return Math.ceil(ms / (1000 * 60 * 60 * 24));
    },
    // First usable host address in the configured subnet, e.g. "10.8.0.0/24"
    // -> "10.8.0.1/24". IPv4 only (dotted-quad); falls back to the documented
    // default if the subnet field is empty/unparseable/IPv6.
    wgSetupHostAddr() {
      const cidr = this.form.wgSubnet;
      const m = cidr && cidr.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/);
      if (!m) return "10.8.0.1/24";
      const octets = [+m[1], +m[2], +m[3], Math.min(+m[4] + 1, 255)];
      return octets.join(".") + "/" + m[5];
    },
    // Port from the configured "host:port" endpoint, falling back to the
    // conventional WireGuard default when nothing's set yet.
    wgSetupPort() {
      const m = (this.form.wgServerEndpoint || "").match(/:(\d+)$/);
      return m ? m[1] : "51820";
    },
    wgSetupCmdKeys() {
      const iface = this.wgInterface;
      return `sudo mkdir -p /etc/wireguard\numask 077\nwg genkey | sudo tee /etc/wireguard/${iface}.key | wg pubkey | sudo tee /etc/wireguard/${iface}.pub`;
    },
    wgSetupCmdInterface() {
      const iface = this.wgInterface;
      return `sudo ip link add ${iface} type wireguard\n`
        + `sudo wg set ${iface} private-key /etc/wireguard/${iface}.key listen-port ${this.wgSetupPort}\n`
        + `sudo ip addr add ${this.wgSetupHostAddr} dev ${iface}\n`
        + `sudo ip link set ${iface} up`;
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    // Same "paste a 'lat, lng' pair, split into both fields" convenience the
    // site-peer form already has (peerModal.js#pasteSiteCoordinates) — the
    // hub-location fields never got it, so a paste here either dropped the
    // second value or (since these are type=number inputs) got silently
    // truncated at the comma, leaving hubLon unset/garbage.
    pasteHubCoordinates(event) {
      const text = (event.clipboardData || window.clipboardData).getData("text").trim();
      const parts = text.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean);
      if (parts.length >= 2) {
        const lat = parseFloat(parts[0]);
        const lon = parseFloat(parts[1]);
        if (!isNaN(lat) && !isNaN(lon)) {
          this.form.hubLat = lat;
          this.form.hubLon = lon;
          return;
        }
      }
      this.form.hubLat = text;
    },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/settings");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
        this.savedRetention = s.privateKeyRetention || "never";
        this.encryptionKeyConfigured = !!s.encryptionKeyConfigured;
        this.wgInterface = s.wgInterface || "wg0";
        this.form = {
          wgSubnet: s.wgSubnet || "",
          wgSubnet6: s.wgSubnet6 || "",
          // (wgInterface handled below — read-only, not part of the editable form)
          wgServerPublicKey: s.wgServerPublicKey || "",
          wgServerEndpoint: s.wgServerEndpoint || "",
          wgClientAllowedIps: s.wgClientAllowedIps || "",
          wgClientDns: s.wgClientDns || "",
          tunnelMode: s.tunnelMode || "SPLIT",
          allowedIpsMode: s.allowedIpsMode || "MANUAL",
          splitSupernet: s.splitSupernet || "",
          privateKeyRetention: s.privateKeyRetention || "never",
          gravatarEnabled: !!s.gravatarEnabled,
          oidcAutoProvision: s.oidcAutoProvision !== false,
          firewallDryRun: !!s.firewallDryRun,
          selfServicePeerCreation: s.selfServicePeerCreation !== false,
          ironRdpEnabled: !!s.ironRdpEnabled,
          wgMtu: s.wgMtu || null,
          wgIncludeMtuInConf: !!s.wgIncludeMtuInConf,
          wgPersistentKeepalive: s.wgPersistentKeepalive ?? 25,
          hubLat: s.hubLat ?? null,
          hubLon: s.hubLon ?? null,
          hubLocationLabel: s.hubLocationLabel || "",
          activityRetentionDays: s.activityRetentionDays || 180,
          dnsResolverEnabled: !!s.dnsResolverEnabled,
          dnsResolverZone: s.dnsResolverZone || "",
          dnsResolverUpstream: s.dnsResolverUpstream || "",
        };
        this.computedAllowedIpsPreview = s.computedAllowedIpsPreview || "";
        this.meta = {
          updatedAt: s.updatedAt,
          updatedBy: s.updatedBy,
          setupComplete: s.setupComplete,
          version: s.version || null,
        };
        this.tlsMode = s.tlsMode || "none";
        this.tlsCertExpiresAt = s.tlsCertExpiresAt || null;
        this.tlsCertInfo = s.tlsCertInfo || null;
        this.acmeDomain = s.acmeDomain || null;
        this.acmeLastAttemptAt = s.acmeLastAttemptAt || null;
        this.acmeLastRenewalAt = s.acmeLastRenewalAt || null;
        this.acmeLastError = s.acmeLastError || null;
        this.acmeChallengeType = s.acmeChallengeType || "http-01";
        this.acmeDnsProvider = s.acmeDnsProvider || null;
        this.acmeDnsPendingRecordName = s.acmeDnsPendingRecordName || null;
        this.acmeDnsPendingRecordValue = s.acmeDnsPendingRecordValue || null;
        this.pendingCsrPem = s.pendingCsrPem || null;
        this.pendingCsrCreatedAt = s.pendingCsrCreatedAt || null;
      } catch (e) {
        this.error = t("settings.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    async save() {
      this.saving = true;
      this.error = null;
      this.info = null;
      try {
        const body = {
          ...this.form,
          wgClientDns: this.form.wgClientDns.trim() === "" ? null : this.form.wgClientDns.trim(),
          wgMtu: this.form.wgMtu || null,
          // Preserve an explicit 0 (= keepalive off globally); empty field → 25 default.
          wgPersistentKeepalive: (this.form.wgPersistentKeepalive === "" || this.form.wgPersistentKeepalive == null
            || Number.isNaN(this.form.wgPersistentKeepalive)) ? 25 : this.form.wgPersistentKeepalive,
          hubLat: this.form.hubLat !== "" && this.form.hubLat !== null ? Number(this.form.hubLat) : null,
          hubLon: this.form.hubLon !== "" && this.form.hubLon !== null ? Number(this.form.hubLon) : null,
          hubLocationLabel: this.form.hubLocationLabel.trim() || null,
          activityRetentionDays: (this.form.activityRetentionDays === "" || this.form.activityRetentionDays == null
            || Number.isNaN(this.form.activityRetentionDays)) ? 180 : this.form.activityRetentionDays,
          dnsResolverZone: this.form.dnsResolverZone.trim() === "" ? null : this.form.dnsResolverZone.trim(),
          dnsResolverUpstream: this.form.dnsResolverUpstream.trim() === "" ? null : this.form.dnsResolverUpstream.trim(),
        };
        const res = await fetch("/api/v1/settings", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error("HTTP " + res.status + (text ? " — " + text.slice(0, 200) : ""));
        }
        const s = await res.json();
        this.meta = {
          updatedAt: s.updatedAt,
          updatedBy: s.updatedBy,
          setupComplete: s.setupComplete,
        };
        this.savedRetention = s.privateKeyRetention || "never";
        this.computedAllowedIpsPreview = s.computedAllowedIpsPreview || "";
        this.refreshAllowedIpsPreview(); // also refreshes sitesOutsideSupernetCount
        this.encryptionKeyConfigured = !!s.encryptionKeyConfigured;
        this.info = t("settings.saved");
        this.$emit("settings-changed", s);
      } catch (e) {
        this.error = t("settings.error_save", { error: e.message });
      } finally {
        this.saving = false;
      }
    },

    scheduleAllowedIpsPreview() {
      clearTimeout(this.allowedIpsPreviewTimer);
      this.allowedIpsPreviewTimer = setTimeout(() => this.refreshAllowedIpsPreview(), 300);
    },

    // Recomputes the AllowedIPs preview from the current, unsaved form values —
    // the point being to show the admin the real result while editing, not just
    // after Save. Silent on failure: this is a convenience preview, not the save
    // path, and a transient error here shouldn't surface as a form-level error.
    async refreshAllowedIpsPreview() {
      try {
        const params = new URLSearchParams({
          tunnelMode: this.form.tunnelMode || "",
          allowedIpsMode: this.form.allowedIpsMode || "",
          splitSupernet: this.form.splitSupernet || "",
          wgClientAllowedIps: this.form.wgClientAllowedIps || "",
        });
        const res = await fetch("/api/v1/settings/allowed-ips-preview?" + params.toString());
        if (!res.ok) return;
        const p = await res.json();
        this.computedAllowedIpsPreview = p.preview || "";
        this.sitesOutsideSupernetCount = p.sitesOutsideSupernetCount || 0;
      } catch (e) {
        // preview convenience only — ignore
      }
    },

    async uploadTls() {
      if (!this.tlsPemInput.trim()) return;
      this.tlsUploading = true;
      this.tlsError = null;
      this.tlsInfo = null;
      try {
        const res = await fetch("/api/v1/settings/tls", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ pem: this.tlsPemInput }),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || "HTTP " + res.status);
        }
        const s = await res.json();
        this.tlsMode = s.tlsMode;
        this.tlsCertExpiresAt = s.tlsCertExpiresAt;
        this.tlsCertInfo = s.tlsCertInfo || null;
        this.pendingCsrPem = s.pendingCsrPem || null;
        this.pendingCsrCreatedAt = s.pendingCsrCreatedAt || null;
        this.tlsPemInput = "";
        this.tlsInfo = t("settings.tls_upload_success");
      } catch (e) {
        this.tlsError = t("settings.tls_upload_error", { error: e.message });
      } finally {
        this.tlsUploading = false;
      }
    },

    async resetTls() {
      if (!confirm(t("settings.tls_reset_confirm"))) return;
      this.tlsUploading = true;
      this.tlsError = null;
      this.tlsInfo = null;
      try {
        const res = await fetch("/api/v1/settings/tls", { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
        this.tlsMode = s.tlsMode;
        this.tlsCertExpiresAt = s.tlsCertExpiresAt;
        this.tlsCertInfo = s.tlsCertInfo || null;
        this.pendingCsrPem = s.pendingCsrPem || null;
        this.pendingCsrCreatedAt = s.pendingCsrCreatedAt || null;
        this.tlsInfo = t("settings.tls_reset_success");
      } catch (e) {
        this.tlsError = t("settings.tls_upload_error", { error: e.message });
      } finally {
        this.tlsUploading = false;
      }
    },

    // Blocks for up to islandr.acme.poll-timeout (~60s default) while the hub
    // talks to Let's Encrypt — same "wait on a slow admin action" pattern as
    // uploadTls/resetTls above and wg-probe. A failure still returns 200 with
    // acmeLastError populated (see SettingsResource#enableAcme), not a 5xx.
    async enableAcme() {
      if (!this.acmeDomainInput.trim()) return;
      this.acmeEnabling = true;
      this.tlsError = null;
      this.tlsInfo = null;
      this._acmeAbort = new AbortController();
      try {
        const res = await fetch("/api/v1/settings/acme", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          signal: this._acmeAbort.signal,
          body: JSON.stringify({
            domain: this.acmeDomainInput.trim(),
            challengeType: this.acmeChallengeTypeInput,
            dnsProvider: this.acmeChallengeTypeInput === "dns-01" ? this.acmeDnsProviderInput : null,
            dnsApiToken: (this.acmeChallengeTypeInput === "dns-01" && this.acmeDnsProviderInput === "cloudflare"
              && this.acmeDnsApiTokenInput.trim()) ? this.acmeDnsApiTokenInput.trim() : null,
          }),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || "HTTP " + res.status);
        }
        const s = await res.json();
        this.tlsMode = s.tlsMode;
        this.tlsCertExpiresAt = s.tlsCertExpiresAt;
        this.tlsCertInfo = s.tlsCertInfo || null;
        this.acmeDomain = s.acmeDomain || null;
        this.acmeLastAttemptAt = s.acmeLastAttemptAt || null;
        this.acmeLastRenewalAt = s.acmeLastRenewalAt || null;
        this.acmeLastError = s.acmeLastError || null;
        this.acmeChallengeType = s.acmeChallengeType || "http-01";
        this.acmeDnsProvider = s.acmeDnsProvider || null;
        this.acmeDnsPendingRecordName = s.acmeDnsPendingRecordName || null;
        this.acmeDnsPendingRecordValue = s.acmeDnsPendingRecordValue || null;
        this.pendingCsrPem = s.pendingCsrPem || null;
        this.pendingCsrCreatedAt = s.pendingCsrCreatedAt || null;
        if (s.acmeLastError) {
          this.tlsError = t("settings.acme_enable_error", { error: s.acmeLastError });
        } else if (s.acmeDnsPendingRecordValue) {
          this.acmeDomainInput = "";
          this.acmeDnsApiTokenInput = "";
          this.tlsInfo = t("settings.acme_dns_pending_info");
        } else {
          this.acmeDomainInput = "";
          this.acmeDnsApiTokenInput = "";
          this.tlsInfo = t("settings.acme_enable_success", { domain: s.acmeDomain });
        }
      } catch (e) {
        if (e.name === "AbortError") {
          this.tlsInfo = t("settings.acme_cancelled");
        } else {
          this.tlsError = t("settings.acme_enable_error", { error: e.message });
        }
      } finally {
        this.acmeEnabling = false;
        this._acmeAbort = null;
      }
    },

    // "Cancel" while enableAcme()'s request is still in flight: this only
    // stops the browser from waiting on it — the hub keeps running the
    // in-flight attempt to completion server-side (there's no async job to
    // actually interrupt, see AcmeService#cancel's own doc comment) — then
    // calls the cancel endpoint to clear any pending/error state so the form
    // comes back clean instead of possibly showing a stale pending challenge
    // a moment later.
    async cancelEnableAcme() {
      if (this._acmeAbort) this._acmeAbort.abort();
      await this.cancelAcmeSetup();
    },

    async cancelAcmeSetup() {
      this.tlsError = null;
      try {
        const res = await fetch("/api/v1/settings/acme", { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
        this.tlsMode = s.tlsMode;
        this.acmeDomain = s.acmeDomain || null;
        this.acmeLastError = s.acmeLastError || null;
        this.acmeChallengeType = s.acmeChallengeType || "http-01";
        this.acmeDnsProvider = s.acmeDnsProvider || null;
        this.acmeDnsPendingRecordName = s.acmeDnsPendingRecordName || null;
        this.acmeDnsPendingRecordValue = s.acmeDnsPendingRecordValue || null;
        this.acmeChallengeTypeInput = "http-01";
        this.acmeDnsProviderInput = "cloudflare";
        this.tlsInfo = t("settings.acme_cancelled");
      } catch (e) {
        this.tlsError = t("settings.acme_enable_error", { error: e.message });
      }
    },

    // Resumes a "manual" dns-01 challenge (ADR-0020) once the admin has added
    // the TXT record enableAcme above asked for. Same blocking/error pattern.
    async continueDns() {
      this.dnsContinuing = true;
      this.tlsError = null;
      this.tlsInfo = null;
      try {
        const res = await fetch("/api/v1/settings/acme/dns-continue", { method: "POST" });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || "HTTP " + res.status);
        }
        const s = await res.json();
        this.tlsMode = s.tlsMode;
        this.tlsCertExpiresAt = s.tlsCertExpiresAt;
        this.tlsCertInfo = s.tlsCertInfo || null;
        this.acmeDomain = s.acmeDomain || null;
        this.acmeLastAttemptAt = s.acmeLastAttemptAt || null;
        this.acmeLastRenewalAt = s.acmeLastRenewalAt || null;
        this.acmeLastError = s.acmeLastError || null;
        this.acmeChallengeType = s.acmeChallengeType || "http-01";
        this.acmeDnsProvider = s.acmeDnsProvider || null;
        this.acmeDnsPendingRecordName = s.acmeDnsPendingRecordName || null;
        this.acmeDnsPendingRecordValue = s.acmeDnsPendingRecordValue || null;
        if (s.acmeLastError) {
          this.tlsError = t("settings.acme_enable_error", { error: s.acmeLastError });
        } else {
          this.tlsInfo = t("settings.acme_enable_success", { domain: s.acmeDomain });
        }
      } catch (e) {
        this.tlsError = t("settings.acme_enable_error", { error: e.message });
      } finally {
        this.dnsContinuing = false;
      }
    },

    async copyDnsRecord() {
      if (!this.acmeDnsPendingRecordValue) return;
      try {
        await navigator.clipboard.writeText(this.acmeDnsPendingRecordValue);
        this.dnsCopyState = "copied";
        setTimeout(() => (this.dnsCopyState = "idle"), 1500);
      } catch {
        this.dnsCopyState = "idle";
      }
    },

    async generateCsr() {
      if (!this.csrDomainInput.trim()) return;
      this.csrGenerating = true;
      this.tlsError = null;
      this.tlsInfo = null;
      try {
        const res = await fetch("/api/v1/settings/tls/csr", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ domain: this.csrDomainInput.trim() }),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text || "HTTP " + res.status);
        }
        const s = await res.json();
        this.pendingCsrPem = s.pendingCsrPem || null;
        this.pendingCsrCreatedAt = s.pendingCsrCreatedAt || null;
        this.csrDomainInput = "";
        this.tlsInfo = t("settings.csr_generate_success");
      } catch (e) {
        this.tlsError = t("settings.csr_generate_error", { error: e.message });
      } finally {
        this.csrGenerating = false;
      }
    },

    async copyCsr() {
      if (!this.pendingCsrPem) return;
      try {
        await navigator.clipboard.writeText(this.pendingCsrPem);
        this.csrCopyState = "copied";
        setTimeout(() => (this.csrCopyState = "idle"), 1500);
      } catch {
        this.csrCopyState = "idle";
      }
    },

    async copyWgSetup(text, key) {
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
        } else {
          // navigator.clipboard needs a secure context (HTTPS/localhost) — fall
          // back to the legacy execCommand path instead of silently doing nothing.
          const ta = document.createElement("textarea");
          ta.value = text;
          ta.style.position = "fixed";
          ta.style.opacity = "0";
          document.body.appendChild(ta);
          ta.focus();
          ta.select();
          const ok = document.execCommand("copy");
          document.body.removeChild(ta);
          if (!ok) throw new Error("execCommand copy failed");
        }
        this.wgSetupCopied = key;
        setTimeout(() => { if (this.wgSetupCopied === key) this.wgSetupCopied = null; }, 2000);
      } catch (_) {
        this.wgSetupCopyFailed = key;
        setTimeout(() => { if (this.wgSetupCopyFailed === key) this.wgSetupCopyFailed = null; }, 2000);
      }
    },

    async probeWg() {
      this.probing = true;
      this.error = null;
      try {
        const iface = "wg0";
        const res = await fetch("/api/v1/settings/wg-probe?iface=" + encodeURIComponent(iface));
        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          throw new Error(body.error || "HTTP " + res.status);
        }
        const data = await res.json();
        this.form.wgServerPublicKey = data.publicKey;
        if (data.mtu) {
          this.probedIfMtu = data.mtu;
          this.form.wgMtu = data.mtu;
        }
        this.probeResult = data;
        this.info = t("settings.wg_probe_success", { iface: data.iface, port: data.listenPort });
      } catch (e) {
        this.error = t("settings.wg_probe_error", { error: e.message });
      } finally {
        this.probing = false;
      }
    },

    async setIfMtu() {
      try {
        const res = await fetch("/api/v1/settings/wg-set-mtu", { method: "POST" });
        if (res.ok) {
          this.info = t("settings.mtu_set_success", { mtu: this.form.wgMtu });
        } else {
          const body = await res.json().catch(() => ({}));
          this.error = t("settings.mtu_set_error", { error: body.error || res.status });
        }
      } catch (e) {
        this.error = t("settings.mtu_set_error", { error: e.message });
      }
    },

    dnsListEntries(field) {
      return (this.form[field] || "").split(",").map((s) => s.trim()).filter(Boolean);
    },

    isDnsPresetActive(preset, field) {
      return this.dnsListEntries(field).includes(preset.value);
    },

    toggleDnsPreset(preset, field) {
      const entries = this.dnsListEntries(field);
      const idx = entries.indexOf(preset.value);
      if (idx >= 0) {
        entries.splice(idx, 1);
      } else {
        entries.push(preset.value);
      }
      this.form[field] = entries.join(", ");
    },

    formatDate(iso) { return formatDate(iso); },

    async exportConfig() {
      this.configExporting = true;
      this.configExportError = null;
      try {
        const url = "/api/v1/admin/config/export" + (this.configIncludePrivateKeys ? "?includePrivateKeys=true" : "");
        const res = await fetch(url);
        if (!res.ok) throw new Error("HTTP " + res.status);
        const data = await res.json();
        const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
        const blobUrl = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = blobUrl;
        a.download = "islandr-config-" + new Date().toISOString().slice(0, 10) + ".json";
        a.click();
        URL.revokeObjectURL(blobUrl);
      } catch (e) {
        this.configExportError = t("settings.config_export_error", { error: e.message });
      } finally {
        this.configExporting = false;
      }
    },

    onImportFile(e) {
      const file = e.target.files[0];
      if (!file) return;
      this.importFileName = file.name;
      const reader = new FileReader();
      reader.onload = (ev) => {
        try {
          this.configImportData = JSON.parse(ev.target.result);
          this.configImportError = null;
          this.configImportResult = null;
        } catch {
          this.configImportData = null;
          this.configImportError = t("settings.config_import_invalid");
        }
      };
      reader.readAsText(file);
    },

    async confirmImport() {
      if (!this.configImportData) return;
      if (!confirm(t("settings.config_import_confirm"))) return;
      this.configImporting = true;
      this.configImportError = null;
      try {
        const res = await fetch("/api/v1/admin/config/import", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(this.configImportData),
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error("HTTP " + res.status + (text ? " — " + text.slice(0, 300) : ""));
        }
        this.configImportResult = await res.json();
        this.configImportData = null;
        await this.load();
      } catch (e) {
        this.configImportError = t("settings.config_import_error", { error: e.message });
      } finally {
        this.configImporting = false;
      }
    },
    async loadEnforcement() {
      try {
        const r = await fetch("/api/v1/enforcement/status");
        if (r.ok) this.enforcement = await r.json();
      } catch { /* non-fatal — the panel just stays hidden */ }
    },
    enforcementModeLabel() {
      const e = this.enforcement;
      if (!e) return "";
      if (!e.runtime.socketMode) return t("settings.enforcement_direct");
      if (e.status === "active") return t("settings.enforcement_socket_active");
      if (e.status === "reconciling") return t("settings.enforcement_socket_reconciling");
      return t("settings.enforcement_socket_degraded");
    },

    async checkVersion() {
      this.versionChecking = true;
      this.versionCheck = null;
      try {
        const r = await fetch("/api/v1/version/check");
        this.versionCheck = await r.json();
      } catch {
        this.versionCheck = { error: t("settings.version_error") };
      } finally {
        this.versionChecking = false;
      }
    },
  },
  template: `
    <div class="page-header">
      <div>
        <h1 style="margin: 0 0 2px">Hub</h1>
        <div class="mono" style="font-size: var(--text-sm); color: var(--fg3)">
          {{ form.wgServerEndpoint || '—' }}
        </div>
      </div>
    </div>

    <div v-if="!loading && !meta.setupComplete" class="callout callout-warning" style="margin-bottom: var(--space-4)">
      <div>{{ t('settings.setup_warn') }}</div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="info" class="callout callout-info" style="margin-bottom: var(--space-4)"><div>{{ info }}</div></div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else style="display:flex; border:1px solid var(--border); border-radius:var(--radius-sm); width:fit-content; overflow:hidden; margin-bottom: var(--space-5); flex-wrap: wrap">
      <button type="button" class="btn btn-sm" :class="settingsTab === 'network' ? 'btn-secondary' : 'btn-ghost'"
              style="border:none; border-radius:0" @click="settingsTab = 'network'">{{ t('settings.tab_network') }}</button>
      <button type="button" class="btn btn-sm" :class="settingsTab === 'tls' ? 'btn-secondary' : 'btn-ghost'"
              style="border:none; border-radius:0" @click="settingsTab = 'tls'">{{ t('settings.tab_tls') }}</button>
      <button type="button" class="btn btn-sm" :class="settingsTab === 'access' ? 'btn-secondary' : 'btn-ghost'"
              style="border:none; border-radius:0" @click="settingsTab = 'access'">{{ t('settings.tab_access') }}</button>
      <button type="button" class="btn btn-sm" :class="settingsTab === 'users' ? 'btn-secondary' : 'btn-ghost'"
              style="border:none; border-radius:0" @click="settingsTab = 'users'">{{ t('settings.tab_users') }}</button>
      <button type="button" class="btn btn-sm" :class="settingsTab === 'advanced' ? 'btn-secondary' : 'btn-ghost'"
              style="border:none; border-radius:0" @click="settingsTab = 'advanced'">{{ t('settings.tab_advanced') }}</button>
    </div>

    <form v-if="!loading" @submit.prevent="save" style="display: flex; flex-direction: column; gap: var(--space-5)">

      <template v-if="settingsTab === 'network'">
      <!-- WireGuard -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-4); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">WireGuard</h2>
        <div class="form-grid">
          <div class="field">
            <label>{{ t('settings.field_interface') }}</label>
            <input class="input mono" :value="wgInterface" disabled readonly />
            <div class="field-hint">{{ t('settings.field_interface_hint') }}</div>
          </div>

          <div class="field">
            <label for="wgSubnet">{{ t('settings.field_subnet') }}</label>
            <input id="wgSubnet" class="input mono" v-model="form.wgSubnet" required placeholder="10.8.0.0/24" />
            <div class="field-hint">{{ t('settings.hint_subnet') }}</div>
          </div>

          <div class="field">
            <label for="wgSubnet6">{{ t('settings.field_subnet6') }}</label>
            <input id="wgSubnet6" class="input mono" v-model="form.wgSubnet6" placeholder="fd11::/64" />
            <div class="field-hint">{{ t('settings.hint_subnet6') }}</div>
          </div>

          <div class="field">
            <label for="wgServerEndpoint">{{ t('settings.field_endpoint') }}</label>
            <input id="wgServerEndpoint" class="input mono" v-model="form.wgServerEndpoint" required placeholder="vpn.example.com:51820" />
            <div class="field-hint">{{ t('settings.hint_endpoint') }}</div>
          </div>

          <div class="field field-full">
            <label for="wgServerPublicKey">{{ t('settings.field_pubkey') }}</label>
            <div style="display: flex; gap: var(--space-2); align-items: center">
              <input id="wgServerPublicKey" class="input mono" v-model="form.wgServerPublicKey" required placeholder="Base64…" style="flex: 1" />
              <button type="button" class="btn btn-ghost btn-sm" :disabled="probing" @click="probeWg" style="white-space: nowrap; flex-shrink: 0">
                <span v-if="probing">…</span>
                <span v-else>{{ t('settings.wg_probe_btn') }}</span>
              </button>
            </div>
            <div class="field-hint">{{ t('settings.field_pubkey_hint') }}</div>
            <div v-if="probeResult" style="margin-top: var(--space-2); display: flex; gap: var(--space-4); flex-wrap: wrap; font-size: var(--text-sm); font-family: var(--font-sans)">
              <span>
                {{ t('settings.probe_status') }}
                <span :style="probeResult.ifStatus === 'up' ? 'color:var(--status-ok)' : probeResult.ifStatus === 'down' ? 'color:var(--status-error)' : 'color:var(--fg3)'">
                  {{ probeResult.ifStatus }}
                </span>
              </span>
              <span class="muted">{{ t('settings.probe_port') }} <span class="mono">{{ probeResult.listenPort }}</span></span>
              <span v-if="probeResult.mtu" class="muted">MTU <span class="mono">{{ probeResult.mtu }}</span></span>
              <span class="muted">{{ t('settings.probe_peers') }} <span class="mono">{{ probeResult.peerCount }}</span></span>
              <button v-if="probeResult.listenPort !== 51820" type="button" class="btn btn-ghost btn-sm"
                      style="padding: 0 var(--space-2); height: 20px; font-size: 11px"
                      @click="form.wgServerEndpoint = form.wgServerEndpoint.replace(/:\d+$/, '') + ':' + probeResult.listenPort">
                {{ t('settings.probe_adopt_port', { port: probeResult.listenPort }) }}
              </button>
            </div>
          </div>

          <div class="field field-full">
            <button type="button" class="btn btn-ghost btn-sm" @click="wgSetupOpen = !wgSetupOpen">
              {{ wgSetupOpen ? t('settings.wg_setup_hide') : t('settings.wg_setup_show') }}
            </button>
            <div v-if="wgSetupOpen" style="margin-top: var(--space-3)">
              <p class="muted" style="font-size: var(--text-xs); margin: 0 0 var(--space-3)">{{ t('settings.wg_setup_intro', { iface: wgInterface }) }}</p>

              <label class="label muted" style="font-size: var(--text-xs)">{{ t('settings.wg_setup_step1') }}</label>
              <div style="display:flex; gap: var(--space-2); align-items:flex-start; margin-bottom: var(--space-3)">
                <pre class="mono" style="flex:1; min-width:0; font-size: var(--text-xs); overflow-x:auto; white-space:pre; margin:0; background: var(--surface-2); padding: var(--space-2); border-radius: var(--radius-sm)">{{ wgSetupCmdKeys }}</pre>
                <button type="button" class="btn btn-ghost btn-sm" :aria-label="t('settings.wg_setup_copy')" :title="wgSetupCopyFailed === 'keys' ? t('settings.wg_setup_copy_failed') : t('settings.wg_setup_copy')" @click="copyWgSetup(wgSetupCmdKeys, 'keys')">
                  <Icon :name="wgSetupCopied === 'keys' ? 'check' : 'copy'" :size="14" />
                </button>
              </div>

              <label class="label muted" style="font-size: var(--text-xs)">{{ t('settings.wg_setup_step2') }}</label>
              <div style="display:flex; gap: var(--space-2); align-items:flex-start; margin-bottom: var(--space-3)">
                <pre class="mono" style="flex:1; min-width:0; font-size: var(--text-xs); overflow-x:auto; white-space:pre; margin:0; background: var(--surface-2); padding: var(--space-2); border-radius: var(--radius-sm)">{{ wgSetupCmdInterface }}</pre>
                <button type="button" class="btn btn-ghost btn-sm" :aria-label="t('settings.wg_setup_copy')" :title="wgSetupCopyFailed === 'iface' ? t('settings.wg_setup_copy_failed') : t('settings.wg_setup_copy')" @click="copyWgSetup(wgSetupCmdInterface, 'iface')">
                  <Icon :name="wgSetupCopied === 'iface' ? 'check' : 'copy'" :size="14" />
                </button>
              </div>

              <p class="field-hint" style="margin:0">{{ t('settings.wg_setup_note', { iface: wgInterface }) }}</p>
            </div>
          </div>

          <div class="field field-full">
            <label>{{ t('settings.field_tunnel_mode') }}</label>
            <div style="display:flex; gap: var(--space-2); flex-wrap:wrap">
              <button type="button" class="btn" :class="form.tunnelMode === 'FULL' ? 'btn-secondary' : 'btn-ghost'"
                      style="display:flex; align-items:center; gap:var(--space-2); flex:1; min-width:220px; text-align:left; padding: var(--space-3)"
                      @click="form.tunnelMode = 'FULL'">
                <Icon name="shield" :size="18" />
                <span>
                  <span style="display:block; font-weight:500">{{ t('settings.tunnel_full_label') }}</span>
                  <span class="muted" style="display:block; font-size:var(--text-xs); font-weight:400; text-transform:none; letter-spacing:0">{{ t('settings.tunnel_full_hint') }}</span>
                </span>
              </button>
              <button type="button" class="btn" :class="form.tunnelMode === 'SPLIT' ? 'btn-secondary' : 'btn-ghost'"
                      style="display:flex; align-items:center; gap:var(--space-2); flex:1; min-width:220px; text-align:left; padding: var(--space-3)"
                      @click="form.tunnelMode = 'SPLIT'">
                <Icon name="networks" :size="18" />
                <span>
                  <span style="display:block; font-weight:500">{{ t('settings.tunnel_split_label') }}</span>
                  <span class="muted" style="display:block; font-size:var(--text-xs); font-weight:400; text-transform:none; letter-spacing:0">{{ t('settings.tunnel_split_hint') }}</span>
                </span>
              </button>
            </div>
          </div>

          <div class="field field-full">
            <label>{{ t('settings.field_allowed_ips_mode') }}</label>
            <div style="display:flex; border:1px solid var(--border); border-radius:var(--radius-sm); width:fit-content; overflow:hidden">
              <button type="button" class="btn btn-sm" :class="form.allowedIpsMode === 'AUTO' ? 'btn-secondary' : 'btn-ghost'"
                      style="border:none; border-radius:0" @click="form.allowedIpsMode = 'AUTO'">{{ t('settings.allowed_ips_auto_label') }}</button>
              <button type="button" class="btn btn-sm" :class="form.allowedIpsMode === 'MANUAL' ? 'btn-secondary' : 'btn-ghost'"
                      style="border:none; border-radius:0" @click="form.allowedIpsMode = 'MANUAL'">{{ t('settings.allowed_ips_manual_label') }}</button>
            </div>
          </div>

          <div v-if="form.tunnelMode === 'SPLIT' && form.allowedIpsMode === 'AUTO'" class="field field-full">
            <label for="splitSupernet">{{ t('settings.field_split_supernet') }}</label>
            <input id="splitSupernet" class="input mono" v-model="form.splitSupernet" placeholder="10.0.0.0/8" />
            <div class="field-hint">{{ t('settings.hint_split_supernet') }}</div>
          </div>

          <div v-if="form.allowedIpsMode === 'MANUAL'" class="field field-full">
            <label for="wgClientAllowedIps">{{ t('settings.field_allowed') }}</label>
            <input id="wgClientAllowedIps" class="input mono" v-model="form.wgClientAllowedIps" placeholder="10.8.0.0/24, 192.168.50.0/24" />
            <div class="field-hint">{{ t('settings.hint_allowed_ips') }}</div>
          </div>

          <div v-if="form.allowedIpsMode === 'AUTO'" class="field field-full">
            <label>{{ t('settings.label_allowed_ips_preview') }}</label>
            <div class="mono" style="padding: var(--space-2) var(--space-3); background: var(--surface-2); border-radius: var(--radius-sm); font-size: var(--text-sm)">{{ computedAllowedIpsPreview || '—' }}</div>
            <div class="field-hint">{{ t('settings.hint_allowed_ips_preview') }}</div>
            <div v-if="form.tunnelMode === 'SPLIT' && sitesOutsideSupernetCount > 0" class="field-hint" style="color:var(--warning-fg)">
              {{ t('settings.hint_sites_outside_supernet', { n: sitesOutsideSupernetCount }) }}
            </div>
          </div>

          <div class="field">
            <label for="wgClientDns">{{ t('settings.field_dns') }}</label>
            <input id="wgClientDns" class="input mono" v-model="form.wgClientDns" placeholder="10.8.0.1 (optional)" />
            <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap; margin-top: var(--space-2)">
              <span style="font-size:var(--text-sm); color:var(--fg3)">{{ t('settings.dns_preset_label_v4') }}</span>
              <button v-for="preset in dnsPresetsV4" :key="preset.name" type="button" class="btn btn-ghost btn-sm"
                      :class="{ 'btn-secondary': isDnsPresetActive(preset, 'wgClientDns') }"
                      @click="toggleDnsPreset(preset, 'wgClientDns')">{{ preset.name }}</button>
            </div>
            <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap; margin-top: var(--space-2)">
              <span style="font-size:var(--text-sm); color:var(--fg3)">{{ t('settings.dns_preset_label_v6') }}</span>
              <button v-for="preset in dnsPresetsV6" :key="preset.name" type="button" class="btn btn-ghost btn-sm"
                      :class="{ 'btn-secondary': isDnsPresetActive(preset, 'wgClientDns') }"
                      @click="toggleDnsPreset(preset, 'wgClientDns')">{{ preset.name }}</button>
            </div>
            <div class="field-hint">{{ t('settings.hint_dns') }}</div>
            <div v-if="form.dnsResolverEnabled" class="field-hint">
              {{ t('settings.hint_dns_resolver_prepends_hub') }}
            </div>
          </div>

          <div class="field field-full">
            <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
              <input type="checkbox" v-model="form.dnsResolverEnabled" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
              <span>{{ t('settings.dns_resolver_label') }}</span>
            </label>
            <div class="field-hint" style="margin-top: var(--space-1)">{{ t('settings.dns_resolver_hint') }}</div>
            <div v-if="form.dnsResolverEnabled" style="margin-top: var(--space-3); max-width: 320px">
              <label for="dnsResolverZone">{{ t('settings.dns_resolver_zone_label') }}</label>
              <input id="dnsResolverZone" class="input mono" v-model="form.dnsResolverZone" placeholder="islandr.internal" />
              <div class="field-hint">{{ t('settings.dns_resolver_zone_hint') }}</div>
            </div>
            <div v-if="form.dnsResolverEnabled" style="margin-top: var(--space-4)">
              <label for="dnsResolverUpstream">{{ t('settings.dns_resolver_upstream_label') }}</label>
              <input id="dnsResolverUpstream" class="input mono" v-model="form.dnsResolverUpstream" placeholder="1.1.1.1, 8.8.8.8" />
              <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap; margin-top: var(--space-2)">
                <span style="font-size:var(--text-sm); color:var(--fg3)">{{ t('settings.dns_preset_label_v4') }}</span>
                <button v-for="preset in dnsPresetsV4" :key="preset.name" type="button" class="btn btn-ghost btn-sm"
                        :class="{ 'btn-secondary': isDnsPresetActive(preset, 'dnsResolverUpstream') }"
                        @click="toggleDnsPreset(preset, 'dnsResolverUpstream')">{{ preset.name }}</button>
              </div>
              <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap; margin-top: var(--space-2)">
                <span style="font-size:var(--text-sm); color:var(--fg3)">{{ t('settings.dns_preset_label_v6') }}</span>
                <button v-for="preset in dnsPresetsV6" :key="preset.name" type="button" class="btn btn-ghost btn-sm"
                        :class="{ 'btn-secondary': isDnsPresetActive(preset, 'dnsResolverUpstream') }"
                        @click="toggleDnsPreset(preset, 'dnsResolverUpstream')">{{ preset.name }}</button>
              </div>
              <div class="field-hint">{{ t('settings.dns_resolver_upstream_hint') }}</div>
            </div>
          </div>

          <div class="field">
            <label>MTU</label>
            <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap">
              <input type="number" class="input mono" v-model.number="form.wgMtu"
                     min="576" max="65535" :placeholder="t('settings.ph_mtu')" style="width: 120px" />
              <span style="font-size:var(--text-sm); color:var(--fg3)">{{ t('mtu.preset_label') }}</span>
              <button type="button" class="btn btn-ghost btn-sm" :class="{ 'btn-secondary': form.wgMtu === 1420 }" @click="form.wgMtu = 1420">1420</button>
              <button type="button" class="btn btn-ghost btn-sm" :class="{ 'btn-secondary': form.wgMtu === 1392 }" @click="form.wgMtu = 1392">1392</button>
              <button type="button" class="btn btn-ghost btn-sm" :class="{ 'btn-secondary': form.wgMtu === 1280 }" @click="form.wgMtu = 1280">1280</button>
              <span v-if="probedIfMtu && probedIfMtu !== form.wgMtu"
                    style="font-size:var(--text-sm); color:var(--fg3)">
                {{ t('settings.mtu_probed_label') }} <span class="mono" style="color:var(--fg2)">{{ probedIfMtu }}</span>
              </span>
              <button v-if="form.wgMtu" type="button" class="btn btn-ghost btn-sm" @click="setIfMtu">
                {{ t('settings.mtu_set_btn') }}
              </button>
            </div>
            <label style="display:inline-flex; align-items:center; gap:var(--space-2); cursor:pointer; user-select:none; font-family:var(--font-sans); font-size:var(--text-sm); color:var(--fg1); font-weight:500; text-transform:none; letter-spacing:0; margin-top:var(--space-2)">
              <input type="checkbox" v-model="form.wgIncludeMtuInConf" style="width:16px; height:16px; accent-color:var(--accent); margin:0" />
              <span>{{ t('settings.label_include_mtu') }}</span>
            </label>
            <div class="field-hint" style="line-height:1.5">
              <div>{{ t('mtu.hint_intro') }}</div>
              <div><strong class="mono">1420</strong> — {{ t('mtu.v1420') }}</div>
              <div><strong class="mono">1392</strong> — {{ t('mtu.v1392') }}</div>
              <div><strong class="mono">1280</strong> — {{ t('mtu.v1280') }}</div>
            </div>
            <div class="field-hint">{{ t('settings.hint_include_mtu') }}</div>
          </div>

          <div class="field">
            <label>{{ t('settings.field_keepalive') }}</label>
            <input type="number" class="input mono" v-model.number="form.wgPersistentKeepalive"
                   min="0" max="65535" :placeholder="t('settings.ph_keepalive')" style="width: 120px" />
            <div class="field-hint">{{ t('settings.hint_keepalive') }}</div>
          </div>
        </div>
      </div>

      <!-- Hub-Standort -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.section_hub_location') }}</h2>
        <p class="field-hint" style="margin: 0 0 var(--space-4)">{{ t('settings.hint_hub_location') }}</p>
        <div class="form-grid">
          <div class="field field-full">
            <label for="hubLocationLabel">{{ t('settings.label_location') }}</label>
            <input id="hubLocationLabel" class="input" v-model="form.hubLocationLabel" :placeholder="t('settings.ph_location')" />
            <div class="field-hint">{{ t('settings.hint_location_label') }}</div>
          </div>
          <div class="field">
            <label for="hubLat">{{ t('settings.label_lat') }}</label>
            <input id="hubLat" class="input mono" type="number" step="any" min="-90" max="90"
                   v-model="form.hubLat" placeholder="50.1109"
                   @paste.prevent="pasteHubCoordinates($event)" />
          </div>
          <div class="field">
            <label for="hubLon">{{ t('settings.label_lon') }}</label>
            <input id="hubLon" class="input mono" type="number" step="any" min="-180" max="180"
                   v-model="form.hubLon" placeholder="8.6821" />
          </div>
        </div>
      </div>
      </template>

      <template v-if="settingsTab === 'advanced'">
      <!-- Private Keys -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-4); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.field_retention') }}</h2>
        <label style="display:inline-flex; align-items:center; gap:var(--space-2); cursor:pointer; user-select:none; font-family:var(--font-sans); font-size:var(--text-sm); color:var(--fg1); font-weight:500; text-transform:none; letter-spacing:0">
          <input type="checkbox" :checked="form.privateKeyRetention !== 'never'"
                 @change="form.privateKeyRetention = $event.target.checked ? 'plaintext' : 'never'"
                 style="width:16px; height:16px; accent-color:var(--accent); margin:0" />
          <span>{{ t('settings.ret_store_label') }}</span>
        </label>
        <div class="field-hint">{{ t('settings.ret_store_hint') }}</div>

        <div v-if="form.privateKeyRetention !== 'never'" style="margin-top:var(--space-3); display:flex; flex-direction:column; gap:var(--space-2)">
          <label for="ret-mode" style="font-size:var(--text-sm); font-weight:500; color:var(--fg2)">{{ t('settings.ret_mode_label') }}</label>
          <select id="ret-mode" class="select" v-model="form.privateKeyRetention" style="max-width:420px">
            <option value="plaintext">{{ t('settings.ret_plaintext') }}</option>
            <option value="encrypted" :disabled="!encryptionKeyConfigured">{{ t('settings.ret_encrypted') }}</option>
          </select>
          <div v-if="!encryptionKeyConfigured" class="field-hint" style="margin-top:var(--space-1)">{{ t('settings.ret_encrypted_unavail') }}</div>
        </div>

        <div v-if="form.privateKeyRetention === 'never' && savedRetention !== 'never'" class="callout callout-warn" style="margin-top:var(--space-3)">
          {{ t('settings.ret_never_warn') }}
        </div>
        <div v-if="form.privateKeyRetention === 'plaintext'" class="callout callout-warn" style="margin-top:var(--space-3)">
          {{ t('settings.ret_plaintext_warn') }}
        </div>
        <div v-if="form.privateKeyRetention === 'encrypted'" class="callout callout-info" style="margin-top:var(--space-3)">
          {{ t('settings.ret_encrypted_hint') }}
        </div>
      </div>

      <!-- Connection activity history retention (#32) -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.field_activity_retention') }}</h2>
        <div class="field-hint" style="margin-bottom: var(--space-3)">{{ t('settings.activity_retention_hint') }}</div>
        <div style="display:flex; align-items:center; gap:var(--space-2)">
          <input id="activityRetentionDays" class="input mono" type="number" min="1" max="3650" style="max-width:120px"
                 v-model.number="form.activityRetentionDays" />
          <span class="muted" style="font-size:var(--text-sm)">{{ t('settings.activity_retention_days_suffix') }}</span>
        </div>
      </div>
      </template>

      <template v-if="settingsTab === 'tls'">
      <!-- TLS / HTTPS (ADR-0015) -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.section_tls') }}</h2>
        <div class="field-hint" style="margin-top: 0">{{ t('settings.tls_intro') }}</div>

        <div v-if="tlsMode === 'none'" class="callout callout-warn" style="margin-top: var(--space-3)">
          {{ t('settings.tls_dummy_active') }}
        </div>

        <div style="margin-top: var(--space-4); display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden">
          <button type="button" class="btn btn-sm" :class="tlsTab === 'letsencrypt' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="tlsTab = 'letsencrypt'">{{ t('settings.acme_title') }}</button>
          <button type="button" class="btn btn-sm" :class="tlsTab === 'origin' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="tlsTab = 'origin'">{{ t('settings.tls_tab_origin') }}</button>
        </div>

        <div v-if="tlsTab === 'letsencrypt'" style="margin-top: var(--space-4)">
          <div v-if="tlsMode === 'acme'">
            <div class="callout callout-info" v-if="tlsDaysUntilExpiry === null || tlsDaysUntilExpiry > 30">
              {{ t('settings.acme_active', { domain: acmeDomain }) }}
            </div>
            <div class="callout callout-warn" v-else>
              {{ t(tlsDaysUntilExpiry >= 0 ? 'settings.tls_expiry_soon' : 'settings.tls_expired', { days: tlsDaysUntilExpiry }) }}
            </div>
            <div style="margin-top: var(--space-3); padding: var(--space-3); background: var(--surface-2); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-2); font-size: var(--text-sm)">
              <div v-if="acmeLastRenewalAt"><span class="muted">{{ t('settings.acme_last_renewal') }}</span> <span class="mono">{{ formatDate(acmeLastRenewalAt) }}</span></div>
              <div v-if="acmeLastAttemptAt"><span class="muted">{{ t('settings.acme_last_attempt') }}</span> <span class="mono">{{ formatDate(acmeLastAttemptAt) }}</span></div>
              <div v-if="acmeLastError" style="color: var(--status-error)">{{ t('settings.acme_last_error') }} {{ acmeLastError }}</div>
            </div>
            <div style="margin-top: var(--space-3); display: flex; gap: var(--space-2)">
              <!-- A stuck attempt (tlsMode already flips to 'acme' on the very
                   first enable, before issuance even runs — see AcmeSettingsStore
                   #cancelPendingSetup's own doc comment) has no working
                   certificate yet (no expiry to show) but sits here with an
                   error and no other way back to the enable form. -->
              <button v-if="acmeLastError && !tlsCertExpiresAt" type="button" class="btn btn-secondary"
                      :disabled="tlsUploading" @click="cancelAcmeSetup">
                {{ t('settings.acme_cancel_btn') }}
              </button>
              <button type="button" class="btn btn-ghost" :disabled="tlsUploading" @click="resetTls">{{ t('settings.tls_reset_btn') }}</button>
            </div>
          </div>

          <div v-if="acmeDnsPendingRecordValue" class="callout callout-warn" style="margin-top: var(--space-3); display: flex; flex-direction: column; gap: var(--space-2)">
            <div>{{ t('settings.acme_dns_pending_hint') }}</div>
            <div style="display:flex; flex-direction:column; gap:var(--space-1); font-size:var(--text-sm)">
              <div><span class="muted">{{ t('settings.acme_dns_record_name') }}</span> <span class="mono">{{ acmeDnsPendingRecordName }}</span></div>
              <div style="display:flex; align-items:center; gap:var(--space-2)">
                <span class="muted">{{ t('settings.acme_dns_record_value') }}</span>
                <span class="mono" style="word-break: break-all">{{ acmeDnsPendingRecordValue }}</span>
                <button type="button" class="btn btn-ghost btn-sm" @click="copyDnsRecord">
                  {{ dnsCopyState === 'copied' ? t('settings.acme_dns_copied_btn') : t('settings.acme_dns_copy_btn') }}
                </button>
              </div>
            </div>
            <div style="display:flex; gap:var(--space-2)">
              <button type="button" class="btn btn-secondary" :disabled="dnsContinuing" @click="continueDns">
                {{ dnsContinuing ? t('settings.acme_dns_continuing') : t('settings.acme_dns_continue_btn') }}
              </button>
              <button type="button" class="btn btn-ghost" :disabled="dnsContinuing" @click="cancelAcmeSetup">
                {{ t('settings.acme_cancel_btn') }}
              </button>
            </div>
          </div>

          <div v-else style="margin-top: var(--space-4); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-3)">
            <div class="field-hint" style="margin-top: 0">{{ t('settings.acme_hint') }}</div>
            <div class="field">
              <label for="acme-domain">{{ t('settings.acme_field_domain') }}</label>
              <input id="acme-domain" class="input mono" v-model="acmeDomainInput" placeholder="vpn.example.com" />
            </div>
            <div class="field">
              <label for="acme-challenge-type">{{ t('settings.acme_field_challenge_type') }}</label>
              <select id="acme-challenge-type" class="select" v-model="acmeChallengeTypeInput" style="max-width:420px">
                <option value="http-01">{{ t('settings.acme_challenge_http01') }}</option>
                <option value="dns-01">{{ t('settings.acme_challenge_dns01') }}</option>
              </select>
              <div class="field-hint">{{ t('settings.acme_challenge_type_hint') }}</div>
            </div>
            <div class="field" v-if="acmeChallengeTypeInput === 'dns-01'">
              <label for="acme-dns-provider">{{ t('settings.acme_field_dns_provider') }}</label>
              <select id="acme-dns-provider" class="select" v-model="acmeDnsProviderInput" style="max-width:420px">
                <option value="cloudflare">{{ t('settings.acme_dns_provider_cloudflare') }}</option>
                <option value="manual">{{ t('settings.acme_dns_provider_manual') }}</option>
              </select>
            </div>
            <div class="field" v-if="acmeChallengeTypeInput === 'dns-01' && acmeDnsProviderInput === 'cloudflare'">
              <label for="acme-dns-token">{{ t('settings.acme_field_dns_token') }}</label>
              <input id="acme-dns-token" class="input mono" type="password" v-model="acmeDnsApiTokenInput" placeholder="•••••••••" />
              <div class="field-hint">{{ t('settings.acme_dns_token_hint') }}</div>
            </div>
            <div class="field-hint" v-if="acmeChallengeTypeInput === 'dns-01' && acmeDnsProviderInput === 'manual'">
              {{ t('settings.acme_dns_provider_manual_hint') }}
            </div>
            <div style="display:flex; gap:var(--space-2)">
              <button type="button" class="btn btn-secondary" :disabled="acmeEnabling || !acmeDomainInput.trim()" @click="enableAcme">
                {{ acmeEnabling ? t('settings.acme_enabling') : t('settings.acme_enable_btn') }}
              </button>
              <button v-if="acmeEnabling" type="button" class="btn btn-ghost" @click="cancelEnableAcme">
                {{ t('settings.acme_cancel_btn') }}
              </button>
            </div>
          </div>
        </div>

        <div v-else style="margin-top: var(--space-4)">
          <div class="field-hint" style="margin: 0 0 var(--space-4)">{{ t('settings.origin_intro') }}</div>

          <div v-if="tlsMode === 'managed'">
            <div class="callout callout-info" v-if="tlsDaysUntilExpiry === null || tlsDaysUntilExpiry > 30">
              {{ t('settings.tls_managed_active', { when: tlsCertExpiresAt ? new Date(tlsCertExpiresAt).toLocaleDateString(lang === 'de' ? 'de-DE' : 'en-US') : '?' }) }}
            </div>
            <div class="callout callout-warn" v-else>
              {{ t(tlsDaysUntilExpiry >= 0 ? 'settings.tls_expiry_soon' : 'settings.tls_expired', { days: tlsDaysUntilExpiry }) }}
            </div>

            <div v-if="tlsCertInfo" style="margin-top: var(--space-3); padding: var(--space-3); background: var(--surface-2); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-2)">
              <div style="font-size: var(--text-xs); font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.08em">{{ t('settings.tls_cert_details') }}</div>
              <div style="display: flex; gap: var(--space-2); font-size: var(--text-sm)">
                <span style="color: var(--fg2); min-width: 100px; flex-shrink: 0">{{ t('settings.tls_cert_domain') }}</span>
                <span class="mono" style="color: var(--fg1)">{{ tlsCertInfo.subjectCn }}</span>
              </div>
              <div v-if="tlsCertInfo.sans && tlsCertInfo.sans.length > 0" style="display: flex; gap: var(--space-2); font-size: var(--text-sm)">
                <span style="color: var(--fg2); min-width: 100px; flex-shrink: 0">{{ t('settings.tls_cert_sans') }}</span>
                <span class="mono" style="color: var(--fg1)">{{ tlsCertInfo.sans.join(', ') }}</span>
              </div>
              <div style="display: flex; gap: var(--space-2); font-size: var(--text-sm)">
                <span style="color: var(--fg2); min-width: 100px; flex-shrink: 0">{{ t('settings.tls_cert_validity') }}</span>
                <span class="mono" style="color: var(--fg1)">{{ t('settings.tls_cert_validity_range', { from: formatDate(tlsCertInfo.notBefore), to: formatDate(tlsCertInfo.notAfter) }) }}</span>
              </div>
              <div style="display: flex; gap: var(--space-2); font-size: var(--text-sm)">
                <span style="color: var(--fg2); min-width: 100px; flex-shrink: 0">{{ t('settings.tls_cert_issuer') }}</span>
                <span style="color: var(--fg1)">{{ tlsCertInfo.issuer }}</span>
              </div>
            </div>
          </div>

          <div v-if="pendingCsrPem" style="margin-bottom: var(--space-4); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-2)">
            <div style="display: flex; align-items: center; justify-content: space-between; gap: var(--space-3)">
              <div style="font-weight: 600; font-size: var(--text-sm)">{{ t('settings.csr_pending_title') }}</div>
              <span class="muted" style="font-size: var(--text-xs)">{{ formatDate(pendingCsrCreatedAt) }}</span>
            </div>
            <div class="field-hint" style="margin: 0">{{ t('settings.csr_pending_hint') }}</div>
            <textarea class="textarea mono" readonly rows="10" style="resize: vertical; width: 100%">{{ pendingCsrPem }}</textarea>
            <div>
              <button type="button" class="btn btn-ghost btn-sm" @click="copyCsr">
                {{ csrCopyState === 'copied' ? t('settings.csr_copied_btn') : t('settings.csr_copy_btn') }}
              </button>
            </div>
          </div>
          <div v-else style="margin-bottom: var(--space-4); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-3)">
            <div>
              <div style="font-weight: 600; font-size: var(--text-sm)">{{ t('settings.csr_generate_title') }}</div>
              <div class="field-hint" style="margin-top: var(--space-1)">{{ t('settings.csr_generate_hint') }}</div>
            </div>
            <div class="field">
              <label for="csr-domain">{{ t('settings.csr_field_domain') }}</label>
              <input id="csr-domain" class="input mono" v-model="csrDomainInput" placeholder="vpn.example.com" />
            </div>
            <div>
              <button type="button" class="btn btn-secondary" :disabled="csrGenerating || !csrDomainInput.trim()" @click="generateCsr">
                {{ csrGenerating ? t('settings.csr_generating') : t('settings.csr_generate_btn') }}
              </button>
            </div>
          </div>

          <div style="display: flex; flex-direction: column; gap: var(--space-3)">
            <div class="field">
              <label for="tls-pem">{{ t('settings.tls_field_pem') }}</label>
              <textarea id="tls-pem" class="textarea mono" v-model="tlsPemInput" rows="12"
                        :placeholder="pendingCsrPem ? t('settings.tls_pem_ph_pending') : t('settings.tls_pem_ph')" style="resize: vertical; width: 100%"></textarea>
              <div class="field-hint" style="line-height:1.5; margin-top:var(--space-2)">
                <div v-if="pendingCsrPem">{{ t('settings.tls_pem_hint_pending') }}</div>
                <div>{{ t('settings.tls_pem_hint_what') }}</div>
                <div>• {{ t('settings.tls_pem_hint_cert') }}</div>
                <div>• {{ t('settings.tls_pem_hint_key') }}</div>
                <div style="margin-top:var(--space-1)">{{ t('settings.tls_pem_hint_format') }}</div>
              </div>
            </div>

            <div v-if="tlsError" class="callout callout-warn">{{ tlsError }}</div>
            <div v-if="tlsInfo" class="callout callout-info">{{ tlsInfo }}</div>

            <div class="field-hint" style="margin: 0">{{ t('settings.tls_activate_note') }}</div>

            <div style="display: flex; gap: var(--space-3)">
              <button type="button" class="btn btn-primary" :disabled="tlsUploading || !tlsPemInput.trim()"
                      @click="uploadTls">
                {{ tlsUploading ? t('settings.tls_uploading') : t('settings.tls_upload_btn') }}
              </button>
              <button type="button" class="btn btn-ghost" v-if="tlsMode === 'managed'" :disabled="tlsUploading" @click="resetTls">
                {{ t('settings.tls_reset_btn') }}
              </button>
            </div>
          </div>
        </div>
      </div>
      </template>

      <template v-if="settingsTab === 'access'">
      <!-- Firewall -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-4); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.section_firewall') }}</h2>
        <div style="display: flex; flex-direction: column; gap: var(--space-2)">
          <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
            <input type="checkbox" v-model="form.firewallDryRun" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
            <span>{{ t('settings.firewall_dry_run_label') }}</span>
          </label>
          <div class="field-hint" style="margin-top: 0">{{ t('settings.firewall_dry_run_hint') }}</div>
          <div v-if="form.firewallDryRun" class="callout callout-warn" style="margin-top: var(--space-2)">
            {{ t('settings.firewall_dry_run_warn') }}
          </div>
        </div>
      </div>

      <!-- Self-Service / Portal -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-4); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.section_self_service') }}</h2>
        <div style="display: flex; flex-direction: column; gap: var(--space-2)">
          <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
            <input type="checkbox" v-model="form.selfServicePeerCreation" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
            <span>{{ t('settings.self_service_peer_creation_label') }}</span>
          </label>
          <div class="field-hint" style="margin-top: 0">{{ t('settings.self_service_peer_creation_hint') }}</div>
          <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0; margin-top: var(--space-3)">
            <input type="checkbox" v-model="form.ironRdpEnabled" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
            <span>{{ t('settings.iron_rdp_label') }}</span>
          </label>
          <div class="field-hint" style="margin-top: 0">{{ t('settings.iron_rdp_hint') }}</div>
        </div>
      </div>
      </template>

      <template v-if="settingsTab === 'users'">
      <!-- Benutzer -->
      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-4); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('settings.section_users') }}</h2>
        <div style="display: flex; flex-direction: column; gap: var(--space-4)">
          <div style="display: flex; flex-direction: column; gap: var(--space-2)">
            <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
              <input type="checkbox" v-model="form.oidcAutoProvision" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
              <span>{{ t('settings.oidc_provision_label') }}</span>
            </label>
            <div class="field-hint" style="margin-top: 0">{{ t('settings.oidc_provision_hint') }}</div>
          </div>
          <div style="display: flex; flex-direction: column; gap: var(--space-2)">
            <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
              <input type="checkbox" v-model="form.gravatarEnabled" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
              <span>{{ t('settings.gravatar_label') }}</span>
            </label>
            <div class="field-hint" style="margin-top: 0">{{ t('settings.gravatar_hint') }}</div>
          </div>
        </div>
      </div>
      </template>

      <!-- Save + Meta + Version -->
      <div style="display: flex; align-items: center; gap: var(--space-4); flex-wrap: wrap">
        <button type="submit" class="btn btn-primary" :disabled="saving">
          {{ saving ? t('settings.btn_saving') : t('settings.btn_save') }}
        </button>
        <div class="muted">
          {{ t('settings.last_changed', { when: formatDate(meta.updatedAt) }) }}<span v-if="meta.updatedBy"> · {{ t('settings.by_whom', { who: meta.updatedBy }) }}</span>
        </div>
      </div>

      <div v-if="meta.version" style="padding-top: var(--space-4); border-top: 1px solid var(--border); display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap">
        <span class="muted" style="font-size: var(--text-sm)">Islandr</span>
        <span class="mono" style="font-size: var(--text-sm); color: var(--fg2)">v{{ meta.version }}</span>
        <button class="btn btn-ghost btn-sm" :disabled="versionChecking" @click="checkVersion">
          {{ versionChecking ? t('settings.version_checking') : t('settings.version_check_btn') }}
        </button>
        <span v-if="versionCheck && !versionCheck.error" style="font-size: var(--text-sm); font-family: var(--font-mono)">
          <span v-if="versionCheck.upToDate" style="color: var(--status-ok)">{{ t('settings.version_current') }}</span>
          <span v-else style="color: var(--status-warn)">
            {{ t('settings.version_available', { latest: versionCheck.latest }) }}
            <a v-if="versionCheck.releaseUrl" :href="versionCheck.releaseUrl" target="_blank" rel="noopener" style="margin-left: var(--space-2)">{{ t('settings.version_release') }}</a>
          </span>
        </span>
        <span v-if="versionCheck && versionCheck.error" style="font-size: var(--text-sm); color: var(--status-warn)">{{ versionCheck.error }}</span>
      </div>

    </form>

    <!-- Deployment & enforcement mode -->
    <div v-if="!loading && settingsTab === 'advanced' && enforcement" style="margin-top: var(--space-8); padding-top: var(--space-6); border-top: 1px solid var(--border)">
      <h2 style="margin-bottom: var(--space-1); font-size: var(--text-md)">{{ t('settings.enforcement_title') }}</h2>
      <p class="field-hint" style="margin-bottom: var(--space-4)">{{ t('settings.enforcement_hint') }}</p>

      <div class="card card-pad" style="max-width: 640px; display: flex; flex-direction: column; gap: var(--space-3)">
        <div style="display: flex; align-items: baseline; gap: var(--space-3)">
          <span class="muted" style="min-width: 120px; font-size: var(--text-xs); text-transform: uppercase; letter-spacing: 0.08em">{{ t('settings.enforcement_deployment') }}</span>
          <span style="font-size: var(--text-sm); color: var(--fg1)">{{ enforcement.runtime.container ? t('settings.enforcement_docker') : t('settings.enforcement_native') }}</span>
        </div>
        <div style="display: flex; align-items: baseline; gap: var(--space-3); flex-wrap: wrap">
          <span class="muted" style="min-width: 120px; font-size: var(--text-xs); text-transform: uppercase; letter-spacing: 0.08em">{{ t('settings.enforcement_mode') }}</span>
          <span style="font-size: var(--text-sm); font-weight: 600" :style="'color: ' + (!enforcement.runtime.socketMode || enforcement.status === 'active' ? 'var(--status-ok)' : 'var(--status-warn)')">{{ enforcementModeLabel() }}</span>
          <span v-if="enforcement.lastProbeAt" class="muted mono" style="font-size: var(--text-xs)">· {{ t('settings.enforcement_last_probe') }} {{ formatDate(enforcement.lastProbeAt) }}</span>
        </div>

        <div v-if="enforcement.runtime.socketMode && enforcement.status !== 'active'" class="callout callout-warn" style="margin: 0">
          {{ t('settings.enforcement_degraded_body') }}
          <span v-if="enforcement.lastError" class="mono" style="display: block; margin-top: 4px; font-size: var(--text-xs)">{{ enforcement.lastError }}</span>
          <a href="https://github.com/chriscohnen/islandr/blob/main/docs/install.md" target="_blank" rel="noopener" style="display: inline-block; margin-top: var(--space-2)">{{ t('settings.enforcement_setup_link') }}</a>
        </div>
        <p v-else-if="!enforcement.runtime.socketMode" class="field-hint" style="margin: 0">
          {{ t('settings.enforcement_native_hint') }}
          <a href="https://github.com/chriscohnen/islandr/blob/main/docs/install.md" target="_blank" rel="noopener" style="margin-left: var(--space-1)">{{ t('settings.enforcement_docker_link') }}</a>
        </p>
      </div>
    </div>

    <!-- Config Export / Import -->
    <div v-if="!loading && settingsTab === 'advanced'" style="margin-top: var(--space-8); padding-top: var(--space-6); border-top: 1px solid var(--border)">
      <h2 style="margin-bottom: var(--space-1); font-size: var(--text-md)">{{ t('settings.section_config') }}</h2>
      <p class="field-hint" style="margin-bottom: var(--space-5)">{{ t('settings.config_hint') }}</p>

      <div style="font-size: var(--text-sm); font-weight: 500; color: var(--fg2); margin-bottom: var(--space-2)">{{ t('settings.config_export_title') }}</div>
      <div style="display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-5)">
        <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; font-size: var(--text-sm); color: var(--fg1)">
          <input type="checkbox" v-model="configIncludePrivateKeys" style="width: 14px; height: 14px; accent-color: var(--accent); margin: 0" />
          <span>{{ t('settings.config_include_keys') }}</span>
        </label>
        <button type="button" class="btn btn-secondary btn-sm" @click="exportConfig" :disabled="configExporting">
          {{ configExporting ? t('settings.config_exporting') : t('settings.config_export_btn') }}
        </button>
      </div>
      <div v-if="configExportError" class="callout callout-error" style="margin-bottom: var(--space-4)">{{ configExportError }}</div>

      <div style="font-size: var(--text-sm); font-weight: 500; color: var(--fg2); margin-bottom: var(--space-2)">{{ t('settings.config_import_title') }}</div>
      <div class="callout callout-warn" style="margin-bottom: var(--space-3)">{{ t('settings.config_import_warn') }}</div>
      <div style="display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-2)">
        <input ref="importFile" type="file" accept=".json" @change="onImportFile" style="display: none" />
        <button type="button" class="btn btn-secondary btn-sm" @click="$refs.importFile.click()">{{ t('settings.config_import_choose') }}</button>
        <span class="muted mono" style="font-size: var(--text-sm)">{{ importFileName || t('settings.config_import_nofile') }}</span>
        <button v-if="configImportData" type="button" class="btn btn-danger btn-sm" @click="confirmImport" :disabled="configImporting">
          {{ configImporting ? t('settings.config_importing') : t('settings.config_import_btn') }}
        </button>
      </div>
      <div v-if="configImportData && !configImportError" class="field-hint">
        {{ t('settings.config_import_preview', {
          users:     configImportData.users?.length     || 0,
          peers:     configImportData.peers?.length     || 0,
          sites:     configImportData.sites?.length     || 0,
          resources: configImportData.resources?.length || 0
        }) }}
      </div>
      <div v-if="configImportError"  class="callout callout-error"   style="margin-top: var(--space-2)">{{ configImportError }}</div>
      <div v-if="configImportResult" class="callout callout-success"  style="margin-top: var(--space-2)">
        {{ t('settings.config_import_done', {
          users:     configImportResult.users,
          peers:     configImportResult.peers,
          sites:     configImportResult.sites,
          resources: configImportResult.resources
        }) }}
      </div>
    </div>
  `,
});
