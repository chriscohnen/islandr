import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import { onEscape } from "/js/keyboard.js";
import { Icon } from "/js/Icons.js";

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
  components: { Icon },
  data() {
    return {
      lang: locale.current,
      loading: true,
      error: null,
      providers: [],
      editing: null,       // providerKey oder null
      draft: null,         // siehe draftFor()
      saving: false,
      pendingSwitch: null, // providerKey (fixed) oder custom-Provider-id, wenn Wechsel-Confirm offen ist
      // Generische OIDC-Provider (Okta/Auth0/Keycloak/beliebig, issue #69) —
      // eigene Liste + eigenes Edit-Panel, weil das Feld-Set (Issuer-URL/
      // Domain + Discovery) sich zu stark von MS365/Google unterscheidet, um
      // es ins bestehende draft/edit-Modell zu pressen. Nehmen aber an der
      // GLEICHEN Mutual-Exclusion teil wie die zwei festen Provider — daher
      // fließen sie in activeProviderUnified mit ein.
      customProviders: [],
      customEditing: null,  // custom-Provider-id, "new:<preset>" (preset|"custom"), oder null
      customDraft: null,
      customSaving: false,
      customError: null,
      customRediscovering: false,
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
    // The one active custom provider, if the currently active OIDC provider
    // (mutual exclusion spans both tables, issue #69) happens to be a custom
    // one rather than MS365/Google.
    activeCustomProvider() {
      return this.customProviders.find((p) => p.enabled) || null;
    },
    otherCustomProviders() {
      return this.customProviders.filter((p) => !p.enabled);
    },
    // True once ANY provider (fixed or custom) is active — gates whether the
    // fixed-provider strips still offer "Aktivieren" vs. only "Verwenden"
    // (a swap), matching the existing single-active-provider invariant now
    // that a second provider *family* can hold that slot.
    anyProviderActive() {
      return !!this.activeProvider || !!this.activeCustomProvider;
    },
    editingCustomProvider() {
      return this.customProviders.find((p) => p.id === this.customEditing) || null;
    },
    // "new:<preset|custom>" while creating; the preset drives which fields
    // the add-form shows (domain-only for auth0/okta, full issuer URL for
    // the fully generic tile).
    customCreatingPreset() {
      if (!this.customEditing || !this.customEditing.startsWith("new:")) return null;
      return this.customEditing.slice(4);
    },
  },
  async mounted() {
    await Promise.all([this.load(), this.loadCustomProviders(), this.loadGwsSettings()]);
    this._offEscape = onEscape(() => { if (this.pendingSwitch) this.abortSwitch(); });
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
      this.cancelCustomEdit();
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
      this.requestActivateAny("fixed", providerKey);
    },
    // Mutual exclusion now spans MS365/Google *and* every custom provider
    // (issue #69) — this is the one place that decides whether a swap needs
    // confirming, regardless of which family the currently-active and
    // newly-requested provider belong to.
    requestActivateAny(kind, key) {
      const current = this.activeProvider
          ? { kind: "fixed", key: this.activeProvider.providerKey }
          : this.activeCustomProvider
          ? { kind: "custom", key: this.activeCustomProvider.id }
          : null;
      if (current && (current.kind !== kind || current.key !== key)) {
        this.pendingSwitch = { kind, key };
        return;
      }
      this.setEnabledAny(kind, key, true);
    },
    setEnabledAny(kind, key, enabled) {
      return kind === "custom" ? this.setCustomEnabled(key, enabled) : this.setEnabled(key, enabled);
    },
    confirmSwitch() {
      const target = this.pendingSwitch;
      this.pendingSwitch = null;
      if (target) this.setEnabledAny(target.kind, target.key, true);
    },
    abortSwitch() { this.pendingSwitch = null; },
    // Labels for the confirm dialog, which must name whichever provider
    // (fixed or custom) is on each side of the swap.
    pendingSwitchLabel(side) {
      const p = side === "current"
          ? (this.activeProvider
              ? { kind: "fixed", key: this.activeProvider.providerKey }
              : { kind: "custom", key: this.activeCustomProvider && this.activeCustomProvider.id })
          : this.pendingSwitch;
      if (!p || !p.key) return "";
      return p.kind === "custom"
          ? (this.customProviders.find((c) => c.id === p.key) || {}).displayName || p.key
          : this.providerLabel(p.key);
    },

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

    // -- generic OIDC providers (Okta/Auth0/Keycloak/any issuer, issue #69) --

    async loadCustomProviders() {
      try {
        const res = await fetch("/api/v1/identity/custom-providers");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.customProviders = await res.json();
      } catch (e) {
        this.error = t("identity.error_load", { error: e.message });
      }
    },
    startCreateCustom(preset) {
      this.cancelEdit();
      this.customEditing = "new:" + preset;
      this.customDraft = { preset: preset === "custom" ? null : preset,
                            domain: "", issuerUrl: "", displayName: "",
                            clientId: "", clientSecret: "", allowedDomains: "" };
      this.customError = null;
    },
    startEditCustom(id) {
      const p = this.customProviders.find((x) => x.id === id);
      if (!p) return;
      this.cancelEdit();
      this.customEditing = id;
      this.customDraft = { preset: p.preset, domain: "", issuerUrl: p.issuerUrl,
                            displayName: p.displayName, clientId: p.clientId || "",
                            clientSecret: "", allowedDomains: p.allowedDomains || "" };
      this.customError = null;
    },
    cancelCustomEdit() {
      this.customEditing = null;
      this.customDraft = null;
      this.customError = null;
    },
    async saveCustomDraft() {
      if (!this.customEditing || !this.customDraft) return;
      this.customSaving = true;
      this.customError = null;
      try {
        const isNew = this.customEditing.startsWith("new:");
        const body = isNew
            ? { preset: this.customDraft.preset, domain: this.customDraft.domain,
                issuerUrl: this.customDraft.preset ? null : this.customDraft.issuerUrl,
                displayName: this.customDraft.displayName,
                clientId: this.customDraft.clientId, clientSecret: this.customDraft.clientSecret,
                allowedDomains: this.customDraft.allowedDomains }
            : { displayName: this.customDraft.displayName, issuerUrl: this.customDraft.issuerUrl,
                clientId: this.customDraft.clientId, allowedDomains: this.customDraft.allowedDomains,
                ...(this.customDraft.clientSecret ? { clientSecret: this.customDraft.clientSecret } : {}) };
        const res = await fetch(
            isNew ? "/api/v1/identity/custom-providers" : "/api/v1/identity/custom-providers/" + this.customEditing,
            { method: isNew ? "POST" : "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || ("HTTP " + res.status));
        }
        await this.loadCustomProviders();
        this.cancelCustomEdit();
      } catch (e) {
        this.customError = t("identity.error_save", { error: e.message });
      } finally {
        this.customSaving = false;
      }
    },
    async setCustomEnabled(id, enabled) {
      try {
        const res = await fetch("/api/v1/identity/custom-providers/" + id, {
          method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ enabled }),
        });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || ("HTTP " + res.status));
        }
        await Promise.all([this.load(), this.loadCustomProviders()]);
      } catch (e) {
        alert(t("identity.error_toggle", { error: e.message }));
      }
    },
    async deleteCustomProvider(id) {
      if (!confirm(t("identity.custom_delete_confirm"))) return;
      try {
        const res = await fetch("/api/v1/identity/custom-providers/" + id, { method: "DELETE" });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || ("HTTP " + res.status));
        }
        await this.loadCustomProviders();
      } catch (e) {
        alert(t("identity.error_toggle", { error: e.message }));
      }
    },
    async rediscoverCustom(id) {
      this.customRediscovering = true;
      try {
        const res = await fetch("/api/v1/identity/custom-providers/" + id + "/rediscover",
            { method: "POST", headers: { "content-type": "application/json" } });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          throw new Error(err.error || ("HTTP " + res.status));
        }
        await this.loadCustomProviders();
      } catch (e) {
        alert(t("identity.error_toggle", { error: e.message }));
      } finally {
        this.customRediscovering = false;
      }
    },
    customPresetLabel(preset) {
      if (preset === "auth0") return t("identity.custom_preset_auth0");
      if (preset === "okta") return t("identity.custom_preset_okta");
      return t("identity.custom_preset_generic");
    },
    customDomainPlaceholder(preset) {
      return preset === "auth0" ? "your-tenant.auth0.com" : "your-org.okta.com";
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

      <!-- A2) Aktiver Custom-Provider (issue #69) — eigener, schlankerer Hero,
           weil das Feldset (Issuer-URL/Discovery statt Tenant) zu abweichend
           ist, um den MS/Google-Hero wiederzuverwenden. -->
      <section v-else-if="activeCustomProvider && customEditing !== activeCustomProvider.id"
               class="card identity-hero">
        <div class="identity-hero-main">
          <div class="idp-logo idp-logo--lg idp-logo--custom">
            <Icon name="identity" :size="24" />
          </div>
          <div class="identity-hero-text">
            <div class="eyebrow">{{ t('identity.active') }}</div>
            <h2>{{ activeCustomProvider.displayName }}</h2>
            <div class="muted mono" style="font-size: var(--text-sm)">{{ activeCustomProvider.issuerUrl }}</div>
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
          <div><dt>{{ t('identity.domains') }}</dt><dd class="mono">{{ activeCustomProvider.allowedDomains || "—" }}</dd></div>
          <div><dt>{{ t('identity.client_id') }}</dt><dd class="mono">{{ activeCustomProvider.clientId || "—" }}</dd></div>
          <div><dt>{{ t('identity.secret') }}</dt><dd>{{ activeCustomProvider.clientSecretSet ? t('identity.secret_set') : t('identity.secret_missing') }}</dd></div>
        </dl>
        <div class="identity-hero-actions">
          <button class="btn btn-secondary btn-sm" @click="startEditCustom(activeCustomProvider.id)">{{ t('identity.btn_edit') }}</button>
          <button class="btn btn-ghost btn-sm" @click="setCustomEnabled(activeCustomProvider.id, false)">{{ t('identity.btn_disable') }}</button>
        </div>
      </section>

      <!-- B) Empty-Hero: kein Provider aktiv -->
      <section v-else-if="!editing && !customEditing" class="card identity-hero identity-hero--empty">
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
          <!-- Auth0/Okta/generic: plain text + a neutral icon, not the
               official colored logo artwork — these two are B2B IdPs with no
               published "any RP may use our button" permission the way
               Microsoft/Google's consumer sign-in kits grant (see #69). -->
          <button v-for="preset in ['auth0', 'okta', 'custom']" :key="preset" type="button"
                  class="identity-choice" @click="startCreateCustom(preset)">
            <span class="idp-logo idp-logo--xl idp-logo--custom">
              <Icon name="identity" :size="28" />
            </span>
            <span class="identity-choice-label">{{ customPresetLabel(preset) }}</span>
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
              {{ t('identity.setup_intro_a') }}<template v-if="editing === 'microsoft'">{{ t('identity.setup_intro_ms') }}</template><template v-else>{{ t('identity.setup_intro_google') }}</template>{{ t('identity.setup_intro_b') }}
            </div>
          </div>
        </div>

        <div v-if="anyProviderActive && !(activeProvider && activeProvider.providerKey === editing)"
             class="callout callout-warn" style="margin-bottom: var(--space-4)">
          {{ t('identity.edit_replaces', { current: pendingSwitchLabel('current') }) }}
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
                   :placeholder="editingProvider && editingProvider.clientSecretSet ? t('identity.secret_ph_set') : t('identity.secret_ph_unset')" />
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

      <!-- ============== CUSTOM-OIDC EDIT/CREATE-FORM (issue #69) ============== -->
      <section v-if="customEditing" class="card identity-hero identity-edit">
        <div class="identity-hero-main">
          <div class="idp-logo idp-logo--lg idp-logo--custom">
            <Icon name="identity" :size="24" />
          </div>
          <div class="identity-hero-text">
            <div class="eyebrow">{{ t('identity.config') }}</div>
            <h2>{{ customCreatingPreset ? customPresetLabel(customCreatingPreset) : (editingCustomProvider && editingCustomProvider.displayName) }}</h2>
            <div class="muted" style="font-size: var(--text-sm)">{{ t('identity.custom_setup_intro') }}</div>
          </div>
        </div>

        <div v-if="customError" class="error-banner" style="margin-bottom: var(--space-4)">{{ customError }}</div>

        <div v-if="anyProviderActive && !(editingCustomProvider && editingCustomProvider.enabled)"
             class="callout callout-warn" style="margin-bottom: var(--space-4)">
          {{ t('identity.edit_replaces', { current: pendingSwitchLabel('current') }) }}
        </div>

        <div class="form-grid">
          <div class="field field-full">
            <label>{{ t('identity.custom_display_name') }}</label>
            <input class="input" type="text" v-model="customDraft.displayName" placeholder="Keycloak" />
          </div>

          <!-- Auth0/Okta preset: just a domain, issuer templated server-side. -->
          <div v-if="customCreatingPreset && customCreatingPreset !== 'custom'" class="field field-full">
            <label>{{ t('identity.custom_domain') }}</label>
            <input class="input mono" type="text" v-model="customDraft.domain"
                   :placeholder="customDomainPlaceholder(customCreatingPreset)" />
            <div class="field-hint">{{ t('identity.custom_domain_hint') }}</div>
          </div>
          <!-- Generic: full issuer URL, pasted directly. -->
          <div v-else class="field field-full">
            <label>{{ t('identity.custom_issuer_url') }}</label>
            <input class="input mono" type="text" v-model="customDraft.issuerUrl"
                   placeholder="https://idp.example.com" />
            <div class="field-hint">{{ t('identity.custom_issuer_url_hint') }}</div>
          </div>

          <div class="field">
            <label>{{ t('identity.client_id') }}</label>
            <input class="input mono" type="text" v-model="customDraft.clientId" placeholder="OAuth 2.0 Client ID" />
          </div>
          <div class="field">
            <label>{{ t('identity.secret') }}</label>
            <input class="input mono" type="password" v-model="customDraft.clientSecret"
                   :placeholder="editingCustomProvider && editingCustomProvider.clientSecretSet ? t('identity.secret_ph_set') : t('identity.secret_ph_unset')" />
          </div>
          <div class="field field-full">
            <label>{{ t('identity.domains') }} <span class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0">(optional)</span></label>
            <input class="input mono" type="text" v-model="customDraft.allowedDomains" placeholder="firma.de, tochter.de" />
            <div class="field-hint">{{ t('identity.domains_hint') }}</div>
          </div>
        </div>

        <div v-if="editingCustomProvider" class="field" style="margin-bottom: var(--space-4)">
          <label>{{ t('identity.custom_discovery_status') }}</label>
          <div class="mono" style="font-size: var(--text-sm)">
            <span v-if="editingCustomProvider.discovered" style="color: var(--status-ok)">{{ t('identity.custom_discovered') }}</span>
            <span v-else class="muted">{{ t('identity.custom_not_discovered') }}</span>
          </div>
        </div>

        <div class="identity-hero-actions">
          <button class="btn btn-primary btn-sm" :disabled="customSaving" @click="saveCustomDraft">
            {{ customSaving ? t('identity.btn_saving') : t('identity.btn_save') }}
          </button>
          <button v-if="editingCustomProvider" class="btn btn-secondary btn-sm" :disabled="customRediscovering"
                  @click="rediscoverCustom(editingCustomProvider.id)">
            {{ customRediscovering ? t('identity.custom_rediscovering') : t('identity.custom_rediscover') }}
          </button>
          <button class="btn btn-ghost btn-sm" :disabled="customSaving" @click="cancelCustomEdit">{{ t('identity.btn_cancel') }}</button>
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
              <template v-if="isConfigured(p) && anyProviderActive">{{ t('identity.strip_inactive') }}</template>
              <template v-else-if="isConfigured(p)">{{ t('identity.strip_active') }}</template>
              <template v-else>{{ t('identity.strip_none') }}</template>
            </div>
          </div>
          <div class="identity-strip-actions">
            <!-- Kein Provider ist aktiv → nur Wechsel-Button wenn konfiguriert -->
            <template v-if="!anyProviderActive">
              <button v-if="isConfigured(p)" class="btn btn-ghost btn-sm"
                      @click="requestActivate(p.providerKey)">
                {{ t('identity.btn_activate') }}
              </button>
              <button class="btn btn-secondary btn-sm" @click="startEdit(p.providerKey)">
                {{ isConfigured(p) ? t('identity.btn_edit') : t('identity.btn_setup') }}
              </button>
            </template>
            <!-- Irgendein Provider (fest oder custom) ist aktiv → nur Wechsel anbieten, kein Setup -->
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

        <!-- Weitere OIDC-Provider (Okta/Auth0/Keycloak/beliebig, issue #69) -->
        <div v-for="p in otherCustomProviders" :key="p.id" class="identity-strip">
          <span class="idp-logo idp-logo--sm idp-logo--custom">
            <Icon name="identity" :size="16" />
          </span>
          <div class="identity-strip-text">
            <div class="identity-strip-name">{{ p.displayName }}</div>
            <div class="muted" style="font-size: var(--text-xs)">
              <template v-if="p.discovered && p.clientSecretSet && anyProviderActive">{{ t('identity.strip_inactive') }}</template>
              <template v-else-if="p.discovered && p.clientSecretSet">{{ t('identity.strip_active') }}</template>
              <template v-else>{{ t('identity.strip_none') }}</template>
            </div>
          </div>
          <div class="identity-strip-actions">
            <template v-if="!anyProviderActive">
              <button v-if="p.discovered && p.clientSecretSet" class="btn btn-ghost btn-sm"
                      @click="requestActivateAny('custom', p.id)">
                {{ t('identity.btn_activate') }}
              </button>
              <button class="btn btn-secondary btn-sm" @click="startEditCustom(p.id)">{{ t('identity.btn_edit') }}</button>
              <button class="btn btn-ghost btn-sm" @click="deleteCustomProvider(p.id)" :title="t('identity.btn_delete')">
                <Icon name="trash" :size="14" />
              </button>
            </template>
            <template v-else>
              <span class="muted" style="font-size: var(--text-xs); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
                {{ t('identity.strip_blocked') }}
              </span>
              <button v-if="p.discovered && p.clientSecretSet" class="btn btn-ghost btn-sm"
                      @click="requestActivateAny('custom', p.id)">
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
          <div class="modal-header">
            <h2>{{ t('identity.confirm_switch_title') }}</h2>
            <button class="btn btn-ghost btn-sm" @click="abortSwitch">✕</button>
          </div>
          <div class="modal-body">
            <p>
              {{ t('identity.confirm_switch', { current: pendingSwitchLabel('current'), next: pendingSwitchLabel('pending') }) }}
            </p>
            <p class="muted" style="font-size: var(--text-sm)">
              {{ t('identity.confirm_info') }}
            </p>
          </div>
          <div class="modal-footer">
            <button class="btn btn-ghost btn-sm" @click="abortSwitch">{{ t('identity.confirm_cancel') }}</button>
            <button class="btn btn-primary btn-sm" @click="confirmSwitch">{{ t('identity.confirm_ok') }}</button>
          </div>
        </div>
      </div>
    </template>
  `,
});
