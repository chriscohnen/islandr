import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

// Identity-Seite mit Hierarchie: aktiver Provider als Hero oben, alle anderen
// als kompakte "Strip"-Karten darunter. Genau einer kann aktiv sein — das wird
// vom Backend erzwungen (siehe OidcProviderService.deactivateOthers). Beim
// Wechsel zeigt das Frontend einen Confirm-Dialog, damit der Admin nicht
// versehentlich aussperrt.
//
// States:
//   - keiner aktiv, keiner konfiguriert  → Empty-Hero "Wähle einen Provider"
//   - keiner aktiv, einer konfiguriert   → Empty-Hero + Strip "Entwurf vorhanden"
//   - einer aktiv                        → Provider-Hero + andere Strip darunter
// In allen States ist die ENV-Admin-Strip ganz unten als Sicherheitsanker.
export default defineComponent({
  name: "IdentityView",
  data() {
    return {
      lang: locale.current,
      loading: true,
      error: null,
      providers: [],
      editing: null,       // providerKey oder null
      draft: null,         // siehe draftFor()
      saving: false,
      pendingSwitch: null, // providerKey, wenn Wechsel-Confirm offen ist
      // Google Workspace SA config
      gwsForm: { serviceAccountJson: "", impersonationEmail: "" },
      gwsConfigured: false,
      gwsSaving: false,
      gwsSaved: false,
      gwsError: null,
    };
  },
  computed: {
    _lang() { return locale.current; },
    activeProvider() {
      return this.providers.find((p) => p.enabled) || null;
    },
    otherProviders() {
      return this.providers.filter((p) => !p.enabled);
    },
    editingProvider() {
      return this.providers.find((p) => p.providerKey === this.editing) || null;
    },
  },
  async mounted() { await Promise.all([this.load(), this.loadGwsSettings()]); },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/identity/providers");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.providers = await res.json();
      } catch (e) {
        this.error = t("identity.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    draftFor(p) {
      return {
        clientId: p.clientId || "",
        clientSecret: "",
        tenantId: p.tenantId || "",
        allowedDomains: p.allowedDomains || "",
      };
    },
    redirectUriFor(providerKey) {
      return window.location.origin + "/api/v1/auth/oidc/" + providerKey + "/callback";
    },
    providerLabel(key) {
      return key === "microsoft" ? t("identity.ms") : t("identity.google");
    },
    providerSubtitle(p) {
      if (p.providerKey === "microsoft") {
        return p.tenantId ? "Tenant " + this.shortTenant(p.tenantId) : "Single-Tenant Entra ID";
      }
      return p.allowedDomains || "OAuth 2.0 Client";
    },
    shortTenant(tid) {
      if (!tid) return "—";
      return tid.length > 13 ? tid.slice(0, 8) + "…" + tid.slice(-4) : tid;
    },
    startEdit(providerKey) {
      const p = this.providers.find((x) => x.providerKey === providerKey);
      if (!p) return;
      this.editing = providerKey;
      this.draft = this.draftFor(p);
    },
    cancelEdit() {
      this.editing = null;
      this.draft = null;
    },

    // PUT mit dem aktuellen Draft; optional enabled flag mitsenden
    async putDraft(providerKey, enabledOverride) {
      const body = {
        clientId: this.draft.clientId,
        tenantId: this.draft.tenantId,
        allowedDomains: this.draft.allowedDomains,
      };
      if (this.draft.clientSecret && this.draft.clientSecret.length > 0) {
        body.clientSecret = this.draft.clientSecret;
      }
      if (typeof enabledOverride === "boolean") body.enabled = enabledOverride;

      const res = await fetch("/api/v1/identity/providers/" + providerKey, {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || ("HTTP " + res.status));
      }
      return await res.json();
    },
    async saveDraft() {
      if (!this.editing) return;
      this.saving = true;
      try {
        await this.putDraft(this.editing);
        await this.load();
        this.cancelEdit();
      } catch (e) {
        alert(t("identity.error_save", { error: e.message }));
      } finally {
        this.saving = false;
      }
    },

    // Toggle eines konfigurierten Providers (ohne Draft im Edit-Modus)
    async setEnabled(providerKey, enabled) {
      try {
        const res = await fetch("/api/v1/identity/providers/" + providerKey, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ enabled }),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || ("HTTP " + res.status));
        }
        await this.load();
      } catch (e) {
        alert(t("identity.error_toggle", { error: e.message }));
      }
    },
    requestActivate(providerKey) {
      // Falls schon einer aktiv ist und es nicht derselbe ist: erst Confirm-Dialog.
      if (this.activeProvider && this.activeProvider.providerKey !== providerKey) {
        this.pendingSwitch = providerKey;
        return;
      }
      this.setEnabled(providerKey, true);
    },
    confirmSwitch() {
      const target = this.pendingSwitch;
      this.pendingSwitch = null;
      if (target) this.setEnabled(target, true);
    },
    abortSwitch() { this.pendingSwitch = null; },

    async loadGwsSettings() {
      try {
        const res = await fetch("/api/v1/settings");
        if (!res.ok) return;
        const s = await res.json();
        this.gwsConfigured = !!s.googleWsConfigured;
        this.gwsForm.impersonationEmail = s.googleWsImpersonationEmail || "";
        // Never prefill the JSON — force re-paste on change
      } catch { /* non-critical */ }
    },
    async saveGwsSettings() {
      this.gwsSaving = true;
      this.gwsSaved = false;
      this.gwsError = null;
      try {
        const res = await fetch("/api/v1/settings/google-workspace", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            serviceAccountJson: this.gwsForm.serviceAccountJson || null,
            impersonationEmail: this.gwsForm.impersonationEmail || null,
          }),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || "HTTP " + res.status);
        }
        const s = await res.json();
        this.gwsConfigured = !!s.googleWsConfigured;
        this.gwsForm.serviceAccountJson = "";
        this.gwsSaved = true;
      } catch (e) {
        this.gwsError = t("identity.gws_error", { error: e.message });
      } finally {
        this.gwsSaving = false;
      }
    },
    async clearGwsSettings() {
      if (!confirm(t("identity.gws_clear") + "?")) return;
      this.gwsForm = { serviceAccountJson: "", impersonationEmail: "" };
      await this.saveGwsSettings();
    },

    isConfigured(p) {
      return !!p.clientId && p.clientSecretSet &&
             (p.providerKey !== "microsoft" || !!p.tenantId);
    },

    adminConsentUrl(p) {
      if (p.providerKey !== "microsoft" || !p.tenantId || !p.clientId) return null;
      const redirect = encodeURIComponent(this.redirectUriFor("microsoft"));
      return "https://login.microsoftonline.com/" + p.tenantId
        + "/adminconsent?client_id=" + encodeURIComponent(p.clientId)
        + "&redirect_uri=" + redirect;
    },
  },
  template: `
    <div class="page-header">
      <div>
        <h1>{{ t('identity.title') }}</h1>
        <p class="page-sub">{{ t('identity.subtitle') }}</p>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <template v-else>
      <!-- ====================== HERO ====================== -->
      <!-- A) Aktiver Provider, nicht im Edit-Modus -->
      <section v-if="activeProvider && editing !== activeProvider.providerKey" class="card identity-hero">
        <div class="identity-hero-main">
          <div :class="['idp-logo', 'idp-logo--lg', 'idp-logo--' + activeProvider.providerKey]">
            <span v-if="activeProvider.providerKey === 'microsoft'" class="oauth-mark oauth-mark--ms" aria-hidden="true">
              <span></span><span></span><span></span><span></span>
            </span>
            <span v-else class="oauth-mark oauth-mark--google" aria-hidden="true">G</span>
          </div>
          <div class="identity-hero-text">
            <div class="eyebrow">{{ t('identity.active') }}</div>
            <h2>{{ providerLabel(activeProvider.providerKey) }}</h2>
            <div class="muted mono" style="font-size: var(--text-sm)">{{ providerSubtitle(activeProvider) }}</div>
          </div>
          <div class="identity-hero-status">
            <span class="badge badge-success status-pill">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/><path d="m8 12 3 3 5-6"/>
              </svg>
              {{ t('identity.connected') }}
            </span>
          </div>
        </div>

        <dl class="identity-hero-meta">
          <div><dt>{{ t('identity.domains') }}</dt><dd class="mono">{{ activeProvider.allowedDomains || "—" }}</dd></div>
          <div v-if="activeProvider.providerKey === 'microsoft'">
            <dt>{{ t('identity.tenant') }}</dt><dd class="mono">{{ activeProvider.tenantId || "—" }}</dd>
          </div>
          <div><dt>{{ t('identity.client_id') }}</dt><dd class="mono">{{ activeProvider.clientId || "—" }}</dd></div>
          <div><dt>{{ t('identity.secret') }}</dt><dd>{{ activeProvider.clientSecretSet ? t('identity.secret_set') : t('identity.secret_missing') }}</dd></div>
        </dl>

        <div class="identity-hero-actions">
          <button class="btn btn-secondary btn-sm" @click="startEdit(activeProvider.providerKey)">{{ t('identity.btn_edit') }}</button>
          <button class="btn btn-ghost btn-sm" @click="setEnabled(activeProvider.providerKey, false)">{{ t('identity.btn_disable') }}</button>
        </div>

        <div v-if="adminConsentUrl(activeProvider)" class="callout callout-info" style="margin-top: var(--space-4)">
          <div>
            <strong>{{ t('identity.consent_title') }}</strong>
            <p style="margin: var(--space-2) 0 var(--space-3)">
              {{ t('identity.consent_desc') }}
            </p>
            <a :href="adminConsentUrl(activeProvider)" target="_blank" rel="noopener" class="btn btn-secondary btn-sm">
              {{ t('identity.consent_btn') }}
            </a>
          </div>
        </div>
      </section>

      <!-- B) Empty-Hero: kein Provider aktiv -->
      <section v-else-if="!editing" class="card identity-hero identity-hero--empty">
        <div class="eyebrow">{{ t('identity.step1') }}</div>
        <h2>{{ t('identity.choose_title') }}</h2>
        <p class="muted">
          {{ t('identity.choose_desc') }}
        </p>
        <div class="identity-choice-grid">
          <button v-for="key in ['microsoft', 'google']" :key="key" type="button"
                  class="identity-choice" @click="startEdit(key)">
            <span :class="['idp-logo', 'idp-logo--xl', 'idp-logo--' + key]">
              <span v-if="key === 'microsoft'" class="oauth-mark oauth-mark--ms" aria-hidden="true">
                <span></span><span></span><span></span><span></span>
              </span>
              <span v-else class="oauth-mark oauth-mark--google" aria-hidden="true">G</span>
            </span>
            <span class="identity-choice-label">{{ providerLabel(key) }}</span>
            <span v-if="isConfigured(providers.find((x) => x.providerKey === key) || {})"
                  class="eyebrow identity-choice-hint">{{ t('identity.draft') }}</span>
          </button>
        </div>
      </section>

      <!-- ====================== EDIT-FORM ====================== -->
      <section v-if="editing" class="card identity-hero identity-edit">
        <div class="identity-hero-main">
          <div :class="['idp-logo', 'idp-logo--lg', 'idp-logo--' + editing]">
            <span v-if="editing === 'microsoft'" class="oauth-mark oauth-mark--ms" aria-hidden="true">
              <span></span><span></span><span></span><span></span>
            </span>
            <span v-else class="oauth-mark oauth-mark--google" aria-hidden="true">G</span>
          </div>
          <div class="identity-hero-text">
            <div class="eyebrow">{{ t('identity.config') }}</div>
            <h2>{{ providerLabel(editing) }}</h2>
            <div class="muted" style="font-size: var(--text-sm)">
              Trage die OAuth-Anwendungsdaten ein, die im
              <template v-if="editing === 'microsoft'">Azure-Portal (App-Registrierung)</template>
              <template v-else>Google Cloud Console (OAuth-Client)</template>
              erzeugt wurden.
            </div>
          </div>
        </div>

        <div v-if="activeProvider && activeProvider.providerKey !== editing"
             class="callout callout-warn" style="margin-bottom: var(--space-4)">
          {{ t('identity.edit_replaces', { current: providerLabel(activeProvider.providerKey) }) }}
        </div>

        <div class="field">
          <label>{{ t('identity.redirect') }}</label>
          <pre class="code-block">{{ redirectUriFor(editing) }}</pre>
          <div class="field-hint">{{ t('identity.redirect_hint') }}</div>
        </div>

        <div class="form-grid">
          <div class="field">
            <label>{{ t('identity.client_id') }}</label>
            <input class="input mono" type="text" v-model="draft.clientId"
                   :placeholder="editing === 'microsoft' ? 'Application (client) ID' : 'OAuth 2.0 Client ID'" />
          </div>

          <div class="field">
            <label>{{ t('identity.secret') }}</label>
            <input class="input mono" type="password" v-model="draft.clientSecret"
                   :placeholder="editingProvider && editingProvider.clientSecretSet ? 'gesetzt – neu eingeben zum Rotieren' : 'noch nicht gesetzt'" />
            <div class="field-hint">{{ t('identity.secret_hint') }}</div>
          </div>

          <div v-if="editing === 'microsoft'" class="field field-full">
            <label>{{ t('identity.tenant') }}</label>
            <input class="input mono" type="text" v-model="draft.tenantId"
                   placeholder="11111111-2222-3333-4444-555555555555" />
            <div class="field-hint">{{ t('identity.tenant_hint') }}</div>
          </div>

          <div class="field field-full">
            <label>{{ t('identity.domains') }} <span class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0">(optional)</span></label>
            <input class="input mono" type="text" v-model="draft.allowedDomains"
                   placeholder="firma.de, tochter.de" />
            <div class="field-hint">{{ t('identity.domains_hint') }}</div>
          </div>
        </div>

        <div class="identity-hero-actions">
          <button class="btn btn-primary btn-sm" :disabled="saving" @click="saveDraft">
            {{ saving ? t('identity.btn_saving') : t('identity.btn_save') }}
          </button>
          <button class="btn btn-ghost btn-sm" :disabled="saving" @click="cancelEdit">{{ t('identity.btn_cancel') }}</button>
        </div>
      </section>

      <!-- ====================== STRIP-KARTEN ====================== -->
      <div class="identity-strips">
        <div v-for="p in otherProviders" :key="p.providerKey" class="identity-strip">
          <span :class="['idp-logo', 'idp-logo--sm', 'idp-logo--' + p.providerKey]">
            <span v-if="p.providerKey === 'microsoft'" class="oauth-mark oauth-mark--ms" aria-hidden="true">
              <span></span><span></span><span></span><span></span>
            </span>
            <span v-else class="oauth-mark oauth-mark--google" aria-hidden="true">G</span>
          </span>
          <div class="identity-strip-text">
            <div class="identity-strip-name">{{ providerLabel(p.providerKey) }}</div>
            <div class="muted" style="font-size: var(--text-xs)">
              <template v-if="isConfigured(p) && activeProvider">{{ t('identity.strip_inactive') }}</template>
              <template v-else-if="isConfigured(p)">{{ t('identity.strip_active') }}</template>
              <template v-else>{{ t('identity.strip_none') }}</template>
            </div>
          </div>
          <div class="identity-strip-actions">
            <!-- Provider ist aktiv → nur Wechsel-Button wenn konfiguriert -->
            <template v-if="!activeProvider">
              <button v-if="isConfigured(p)" class="btn btn-ghost btn-sm"
                      @click="requestActivate(p.providerKey)">
                {{ t('identity.btn_activate') }}
              </button>
              <button class="btn btn-secondary btn-sm" @click="startEdit(p.providerKey)">
                {{ isConfigured(p) ? t('identity.btn_edit') : t('identity.btn_setup') }}
              </button>
            </template>
            <!-- Ein anderer Provider ist aktiv → nur Wechsel anbieten, kein Setup -->
            <template v-else>
              <span class="muted" style="font-size: var(--text-xs); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
                {{ t('identity.strip_blocked') }}
              </span>
              <button v-if="isConfigured(p)" class="btn btn-ghost btn-sm"
                      @click="requestActivate(p.providerKey)">
                {{ t('identity.btn_use') }}
              </button>
            </template>
          </div>
        </div>

        <!-- Lokal-Admin: immer da, immer als Sicherheitsanker -->
        <div class="identity-strip identity-strip--local">
          <span class="idp-logo idp-logo--sm idp-logo--local" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 2v6h-6"/><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/>
              <circle cx="12" cy="15" r="2"/><path d="M12 17v3"/><path d="M8 13v-2a4 4 0 0 1 8 0v2"/>
            </svg>
          </span>
          <div class="identity-strip-text">
            <div class="identity-strip-name">{{ t('identity.local_label') }}</div>
            <div class="muted" style="font-size: var(--text-xs)">
              {{ t('identity.local_desc') }}
            </div>
          </div>
        </div>
      </div>

      <!-- ========= GOOGLE WORKSPACE DIRECTORY IMPORT CONFIG ========= -->
      <template v-if="providers.some(p => p.providerKey === 'google')">
        <h2 style="margin-top: var(--space-8); margin-bottom: var(--space-3); font-size: var(--text-md)">{{ t('identity.gws_section') }}</h2>
        <p class="muted" style="font-size: var(--text-sm); margin-bottom: var(--space-4)">{{ t('identity.gws_desc') }}</p>
        <div class="card card-pad" style="max-width: 560px">
          <div v-if="gwsError" class="error-banner" style="margin-bottom: var(--space-3)">{{ gwsError }}</div>
          <div v-if="gwsSaved" style="margin-bottom: var(--space-3); font-size: var(--text-sm); color: var(--status-ok)">{{ t('identity.gws_saved') }}</div>
          <p style="font-size: var(--text-sm); margin-bottom: var(--space-4)">
            <span v-if="gwsConfigured" style="color: var(--status-ok)">{{ t('identity.gws_configured') }}</span>
            <span v-else class="muted">{{ t('identity.gws_not_configured') }}</span>
          </p>
          <div class="field">
            <label>{{ t('identity.gws_sa_json') }}</label>
            <textarea class="input" rows="4"
                      style="font-family: var(--font-mono); font-size: var(--text-xs); resize: vertical"
                      v-model="gwsForm.serviceAccountJson"
                      :placeholder="t('identity.gws_sa_json_ph')" />
            <span class="muted" style="font-size: var(--text-xs); display: block; margin-top: var(--space-1)">
              {{ t('identity.gws_sa_json_hint') }}
              <a href="https://console.cloud.google.com/iam-admin/serviceaccounts" target="_blank" rel="noopener">console.cloud.google.com</a>
            </span>
          </div>
          <div class="field" style="margin-bottom: var(--space-5)">
            <label>{{ t('identity.gws_email') }}</label>
            <input class="input" type="email"
                   v-model="gwsForm.impersonationEmail"
                   :placeholder="t('identity.gws_email_ph')" />
            <span class="muted" style="font-size: var(--text-xs); display: block; margin-top: var(--space-1)">{{ t('identity.gws_email_hint') }}</span>
          </div>
          <div style="display: flex; gap: var(--space-3)">
            <button class="btn btn-primary btn-sm" :disabled="gwsSaving" @click="saveGwsSettings">
              {{ gwsSaving ? t('identity.gws_saving') : t('identity.gws_save') }}
            </button>
            <button v-if="gwsConfigured" class="btn btn-ghost btn-sm" @click="clearGwsSettings">
              {{ t('identity.gws_clear') }}
            </button>
          </div>
        </div>
      </template>

      <!-- ====================== CONFIRM-DIALOG ====================== -->
      <div v-if="pendingSwitch" class="modal-backdrop" @click.self="abortSwitch">
        <div class="modal modal-sm">
          <h2>Provider wechseln?</h2>
          <p>
            {{ t('identity.confirm_switch', { current: providerLabel(activeProvider.providerKey), next: providerLabel(pendingSwitch) }) }}
          </p>
          <p class="muted" style="font-size: var(--text-sm)">
            {{ t('identity.confirm_info') }}
          </p>
          <div class="modal-actions">
            <button class="btn btn-ghost btn-sm" @click="abortSwitch">{{ t('identity.confirm_cancel') }}</button>
            <button class="btn btn-primary btn-sm" @click="confirmSwitch">{{ t('identity.confirm_ok') }}</button>
          </div>
        </div>
      </div>
    </template>
  `,
});
