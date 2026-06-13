import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

// Settings form. GET + PUT against /api/v1/settings (the singleton row).
// All WireGuard topology that ends up in client .conf files lives here —
// see docs/adr/0008-runtime-settings-in-db.md.
export default defineComponent({
  name: "SettingsView",
  data() {
    return {
      loading: true,
      saving: false,
      probing: false,
      probeResult: null,
      probedIfMtu: null,
      error: null,
      info: null,
      savedRetention: "never",  // retention at last load/save — used to detect unsaved switch to never
      encryptionKeyConfigured: false,
      form: {
        wgSubnet: "",
        wgSubnet6: "",
        wgServerPublicKey: "",
        wgServerEndpoint: "",
        wgClientAllowedIps: "",
        wgClientDns: "",
        privateKeyRetention: "never",
        gravatarEnabled: false,
        oidcAutoProvision: true,
        firewallDryRun: true,
        selfServicePeerCreation: true,
        wgMtu: null,
        wgIncludeMtuInConf: false,
      },
      meta: { updatedAt: null, updatedBy: null, setupComplete: false },
      lang: locale.current,
    };
  },
  async mounted() {
    await this.load();
  },
  computed: { _lang() { return locale.current; } },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/settings");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
        this.savedRetention = s.privateKeyRetention || "never";
        this.encryptionKeyConfigured = !!s.encryptionKeyConfigured;
        this.form = {
          wgSubnet: s.wgSubnet || "",
          wgSubnet6: s.wgSubnet6 || "",
          wgServerPublicKey: s.wgServerPublicKey || "",
          wgServerEndpoint: s.wgServerEndpoint || "",
          wgClientAllowedIps: s.wgClientAllowedIps || "",
          wgClientDns: s.wgClientDns || "",
          privateKeyRetention: s.privateKeyRetention || "never",
          gravatarEnabled: !!s.gravatarEnabled,
          oidcAutoProvision: s.oidcAutoProvision !== false,
          firewallDryRun: !!s.firewallDryRun,
          selfServicePeerCreation: s.selfServicePeerCreation !== false,
          wgMtu: s.wgMtu || null,
          wgIncludeMtuInConf: !!s.wgIncludeMtuInConf,
        };
        this.meta = {
          updatedAt: s.updatedAt,
          updatedBy: s.updatedBy,
          setupComplete: s.setupComplete,
          version: s.version || null,
        };
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
        this.encryptionKeyConfigured = !!s.encryptionKeyConfigured;
        this.info = t("settings.saved");
        this.$emit("settings-changed", s);
      } catch (e) {
        this.error = t("settings.error_save", { error: e.message });
      } finally {
        this.saving = false;
      }
    },

    async probeWg() {
      this.probing = true;
      this.error = null;
      try {
        const iface = this.form.wgServerEndpoint
            ? (this.form.wgServerEndpoint.split(":")[0] === "wg0" ? "wg0" : "wg0")
            : "wg0";
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
          this.info = `MTU ${this.form.wgMtu} am Interface gesetzt.`;
        } else {
          const body = await res.json().catch(() => ({}));
          this.error = "MTU setzen fehlgeschlagen: " + (body.error || res.status);
        }
      } catch (e) {
        this.error = "MTU setzen fehlgeschlagen: " + e.message;
      }
    },

    formatDate(iso) {
      if (!iso) return "—";
      return new Date(iso).toLocaleString("de-DE");
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('settings.title') }}</h1>
    </div>

    <div v-if="!loading && !meta.setupComplete" class="callout callout-warning">
      <div>{{ t('settings.setup_warn') }}</div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="info" class="callout callout-info"><div>{{ info }}</div></div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <form v-else class="card card-pad" @submit.prevent="save">
      <div class="form-grid">
        <div class="field">
          <label for="wgSubnet">{{ t('settings.field_subnet') }}</label>
          <input id="wgSubnet" class="input mono" v-model="form.wgSubnet" required placeholder="10.8.0.0/24" />
          <div class="field-hint">IPv4-CIDR. Peer-IPs werden gegen dieses Subnetz validiert.</div>
        </div>

        <div class="field">
          <label for="wgSubnet6">{{ t('settings.field_subnet6') }}</label>
          <input id="wgSubnet6" class="input mono" v-model="form.wgSubnet6" placeholder="fd11::/64" />
          <div class="field-hint">IPv6-ULA-CIDR für Dual-Stack-Peers (optional). Leer lassen für IPv4-only.</div>
        </div>

        <div class="field">
          <label for="wgServerEndpoint">{{ t('settings.field_endpoint') }}</label>
          <input id="wgServerEndpoint" class="input mono" v-model="form.wgServerEndpoint" required placeholder="vpn.example.com:51820" />
          <div class="field-hint">Host:Port — wandert in jede .conf als [Peer] Endpoint.</div>
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
          <label for="wgClientAllowedIps">{{ t('settings.field_allowed') }}</label>
          <input id="wgClientAllowedIps" class="input mono" v-model="form.wgClientAllowedIps" required placeholder="10.8.0.0/24, 192.168.50.0/24" />
          <div class="field-hint">Komma-separiert. Welche Netze fließen durch den Tunnel.</div>
        </div>

        <div class="field">
          <label for="wgClientDns">{{ t('settings.field_dns') }}</label>
          <input id="wgClientDns" class="input mono" v-model="form.wgClientDns" placeholder="10.8.0.1 (optional)" />
          <div class="field-hint">Leer lassen, wenn kein DNS-Eintrag in die .conf soll.</div>
        </div>

        <div class="field">
          <label>MTU</label>
          <div style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap">
            <input type="number" class="input mono" v-model.number="form.wgMtu"
                   min="576" max="65535" placeholder="z. B. 1420" style="width: 120px" />
            <span v-if="probedIfMtu && probedIfMtu !== form.wgMtu"
                  style="font-size:var(--text-sm); color:var(--fg3)">
              Gemessen: <span class="mono" style="color:var(--fg2)">{{ probedIfMtu }}</span>
            </span>
            <button v-if="form.wgMtu" type="button" class="btn btn-ghost btn-sm" @click="setIfMtu">
              Am WG-Interface setzen
            </button>
          </div>
          <label style="display:inline-flex; align-items:center; gap:var(--space-2); cursor:pointer; user-select:none; font-family:var(--font-sans); font-size:var(--text-sm); color:var(--fg1); font-weight:500; text-transform:none; letter-spacing:0; margin-top:var(--space-2)">
            <input type="checkbox" v-model="form.wgIncludeMtuInConf" style="width:16px; height:16px; accent-color:var(--accent); margin:0" />
            <span>MTU in Client-.conf einschließen</span>
          </label>
          <div class="field-hint">Wird nach "Verbindung testen" automatisch ermittelt und gespeichert. Nur bei Verbindungsproblemen (Fragmentierung) in die .conf aufnehmen.</div>
        </div>

        <div class="field field-full">
          <label>{{ t('settings.field_retention') }}</label>
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
      </div>

      <h2 style="margin-top: var(--space-7); margin-bottom: var(--space-3); font-size: var(--text-md)">{{ t('settings.section_firewall') }}</h2>
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

      <h2 style="margin-top: var(--space-7); margin-bottom: var(--space-3); font-size: var(--text-md)">{{ t('settings.section_self_service') }}</h2>
      <div style="display: flex; flex-direction: column; gap: var(--space-2)">
        <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
          <input type="checkbox" v-model="form.selfServicePeerCreation" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
          <span>{{ t('settings.self_service_peer_creation_label') }}</span>
        </label>
        <div class="field-hint" style="margin-top: 0">{{ t('settings.self_service_peer_creation_hint') }}</div>
      </div>

      <h2 style="margin-top: var(--space-7); margin-bottom: var(--space-3); font-size: var(--text-md)">{{ t('settings.section_users') }}</h2>
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

      <div style="margin-top: var(--space-6); display: flex; align-items: center; gap: var(--space-4)">
        <button type="submit" class="btn btn-primary" :disabled="saving">
          {{ saving ? t('settings.btn_saving') : t('settings.btn_save') }}
        </button>
        <div class="muted">
          Zuletzt geändert: {{ formatDate(meta.updatedAt) }}<span v-if="meta.updatedBy"> · von {{ meta.updatedBy }}</span>
        </div>
      </div>

      <div v-if="meta.version" style="margin-top: var(--space-5); padding-top: var(--space-4); border-top: 1px solid var(--border); display: flex; align-items: center; gap: var(--space-3)">
        <span class="muted" style="font-size: var(--text-sm)">Islandr</span>
        <span class="mono" style="font-size: var(--text-sm); color: var(--fg2)">v{{ meta.version }}</span>
      </div>
    </form>
  `,
});
