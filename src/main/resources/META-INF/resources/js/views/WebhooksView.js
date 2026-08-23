import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";
import { onEscape } from "/js/keyboard.js";

// Outgoing webhooks (issue #68) — a URL + a per-webhook filter of which
// event types get delivered to it, HMAC-signed. List + create/edit modal +
// a one-time secret-reveal modal (same pattern as a peer's QR/.conf: shown
// once, never re-displayed after).
export default defineComponent({
  name: "WebhooksView",
  components: { Icon },
  data() {
    return {
      webhooks: [],
      eventTypes: [],
      loading: true,
      error: null,
      modal: null,       // "create" | "edit" | null
      editId: null,
      form: { url: "", description: "", eventTypes: [], format: "generic", secret: "", headerName: "", headerValue: "" },
      submitting: false,
      formError: null,
      revealedSecret: null, // { secret, format } while the one-time modal is open
      testResults: {},      // webhookId -> { success, status, error } | "pending"
      lang: locale.current,
    };
  },
  computed: { _lang() { return locale.current; } },
  async mounted() {
    await Promise.all([this.load(), this.loadEventTypes()]);
    this._offEscape = onEscape(() => {
      if (this.revealedSecret) this.dismissSecret();
      else if (this.modal) this.closeModal();
    });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    eventTypeLabel(key) {
      const translated = t("webhooks.event." + key);
      return translated === "webhooks.event." + key ? key : translated;
    },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/webhooks");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.webhooks = await res.json();
      } catch (e) {
        this.error = t("webhooks.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async loadEventTypes() {
      try {
        const res = await fetch("/api/v1/webhooks/event-types");
        if (res.ok) this.eventTypes = await res.json();
      } catch {}
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { url: "", description: "", eventTypes: [], format: "generic", secret: "", headerName: "", headerValue: "" };
      this.formError = null;
    },
    openEdit(w) {
      this.modal = "edit";
      this.editId = w.id;
      this.form = { url: w.url, description: w.description || "", eventTypes: [...w.eventTypes],
                     format: w.format, secret: "", headerName: w.headerName || "", headerValue: "" };
      this.formError = null;
    },
    closeModal() {
      this.modal = null;
      this.editId = null;
      this.formError = null;
    },
    toggleEventType(key) {
      const i = this.form.eventTypes.indexOf(key);
      if (i >= 0) this.form.eventTypes.splice(i, 1);
      else this.form.eventTypes.push(key);
    },
    selectAllEventTypes() {
      this.form.eventTypes = [...this.eventTypes];
    },
    selectNoEventTypes() {
      this.form.eventTypes = [];
    },
    async submit() {
      this.submitting = true;
      this.formError = null;
      try {
        const url = this.editId ? "/api/v1/webhooks/" + this.editId : "/api/v1/webhooks";
        const method = this.editId ? "PUT" : "POST";
        const res = await fetch(url, {
          method,
          headers: { "content-type": "application/json" },
          body: JSON.stringify(this.form),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const saved = await res.json();
        if (!this.editId && saved.secret && this.form.format !== "gotify") {
          // Creation response carries the plaintext secret exactly once —
          // only worth the "reveal once, never again" ceremony for the
          // server-generated HMAC secret. A Gotify token is the admin's own
          // value, already known to them; nothing to lose if not saved here.
          this.revealedSecret = { secret: saved.secret, format: this.form.format };
        }
        await this.load();
        this.closeModal();
      } catch (e) {
        this.formError = t("webhooks.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async setEnabled(w, enabled) {
      try {
        const res = await fetch("/api/v1/webhooks/" + w.id, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ enabled }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("webhooks.error_toggle", { error: e.message });
      }
    },
    async rotateSecret(w) {
      if (!confirm(t("webhooks.confirm_rotate"))) return;
      try {
        const res = await fetch("/api/v1/webhooks/" + w.id + "/rotate-secret", {
          method: "POST", headers: { "content-type": "application/json" },
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const body = await res.json();
        this.revealedSecret = { secret: body.secret, format: "generic" };
      } catch (e) {
        this.error = t("webhooks.error_toggle", { error: e.message });
      }
    },
    dismissSecret() {
      this.revealedSecret = null;
    },
    async copySecret() {
      if (!this.revealedSecret) return;
      try { await navigator.clipboard.writeText(this.revealedSecret.secret); } catch {}
    },
    async testFire(w) {
      this.testResults = { ...this.testResults, [w.id]: "pending" };
      try {
        const res = await fetch("/api/v1/webhooks/" + w.id + "/test", {
          method: "POST", headers: { "content-type": "application/json" },
        });
        const body = await res.json();
        this.testResults = { ...this.testResults, [w.id]: body };
      } catch (e) {
        this.testResults = { ...this.testResults, [w.id]: { success: false, error: e.message } };
      }
    },
    async deleteWebhook(w) {
      if (!confirm(t("webhooks.confirm_delete", { url: w.url }))) return;
      try {
        const res = await fetch("/api/v1/webhooks/" + w.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("webhooks.error_delete", { error: e.message });
      }
    },
  },
  template: `
    <div class="page-header">
      <div>
        <h1>{{ t('webhooks.title') }} <span v-if="webhooks.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ webhooks.length }}</span></h1>
        <p class="page-sub">{{ t('webhooks.subtitle') }}</p>
      </div>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('webhooks.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="webhooks.length === 0" class="empty-state">
      <h2>{{ t('webhooks.empty_title') }}</h2>
      <p>{{ t('webhooks.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('webhooks.th_url') }}</th>
          <th>{{ t('webhooks.th_filter') }}</th>
          <th>{{ t('webhooks.th_status') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="w in webhooks" :key="w.id">
          <td>
            <div class="mono">{{ w.url }}</div>
            <div v-if="w.description" class="muted" style="font-size: var(--text-xs)">{{ w.description }}</div>
            <span v-if="w.format === 'gotify'" class="tag" style="margin-top: var(--space-1)">Gotify</span>
            <span v-if="w.headerName" class="tag mono" style="margin-top: var(--space-1)" :title="t('webhooks.header_tag_title')">{{ w.headerName }}</span>
          </td>
          <td>
            <span v-if="w.eventTypes.length === 0" class="muted" style="font-size: var(--text-xs)">{{ t('webhooks.no_filter') }}</span>
            <span v-for="et in w.eventTypes" :key="et" class="tag" style="margin: 0 var(--space-1) var(--space-1) 0">{{ eventTypeLabel(et) }}</span>
          </td>
          <td>
            <span v-if="!w.enabled" class="badge">
              <Icon name="unlink" :size="13" />{{ t('webhooks.disabled') }}
            </span>
            <span v-else-if="w.lastDeliveryStatus === 'ok'" class="badge badge-success">
              <Icon name="check" :size="13" />{{ t('webhooks.delivery_ok') }}
            </span>
            <span v-else-if="w.lastDeliveryStatus === 'failed'" class="badge badge-danger" :title="w.lastDeliveryError || ''">
              <Icon name="unlink" :size="13" />{{ t('webhooks.delivery_failed') }}
            </span>
            <span v-else class="muted" style="font-size: var(--text-xs)">{{ t('webhooks.never_delivered') }}</span>
            <div v-if="testResults[w.id]" style="font-size: var(--text-xs); margin-top: var(--space-1)">
              <span v-if="testResults[w.id] === 'pending'" class="muted">{{ t('webhooks.testing') }}</span>
              <span v-else-if="testResults[w.id].success" style="color: var(--success-fg)">{{ t('webhooks.test_ok') }}</span>
              <span v-else style="color: var(--danger-fg)">{{ t('webhooks.test_failed', { error: testResults[w.id].error || testResults[w.id].status }) }}</span>
            </div>
          </td>
          <td style="text-align: right; white-space: nowrap">
            <button class="btn btn-ghost btn-sm" @click="testFire(w)">{{ t('webhooks.btn_test') }}</button>
            <button class="btn btn-ghost btn-sm" @click="setEnabled(w, !w.enabled)">
              {{ w.enabled ? t('webhooks.btn_disable') : t('webhooks.btn_enable') }}
            </button>
            <button class="btn btn-ghost btn-sm" @click="openEdit(w)"><Icon name="edit" :size="13" />{{ t('webhooks.btn_edit') }}</button>
            <button v-if="w.format !== 'gotify'" class="btn btn-ghost btn-sm" @click="rotateSecret(w)">{{ t('webhooks.btn_rotate') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteWebhook(w)"><Icon name="trash" :size="13" />{{ t('webhooks.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- Create/edit modal -->
    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('webhooks.modal_create') : t('webhooks.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label>{{ t('webhooks.field_format') }}</label>
              <div style="display: flex; gap: var(--space-4)">
                <label style="display: flex; align-items: center; gap: var(--space-2); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 400">
                  <input type="radio" value="generic" v-model="form.format" style="accent-color: var(--accent)" />
                  {{ t('webhooks.format_generic') }}
                </label>
                <label style="display: flex; align-items: center; gap: var(--space-2); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 400">
                  <input type="radio" value="gotify" v-model="form.format" style="accent-color: var(--accent)" />
                  {{ t('webhooks.format_gotify') }}
                </label>
              </div>
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="whUrl">{{ form.format === 'gotify' ? t('webhooks.field_url_gotify') : t('webhooks.field_url') }}</label>
              <input id="whUrl" class="input mono" type="text" v-model="form.url" required
                     :placeholder="form.format === 'gotify' ? 'https://gotify.example.com' : 'https://example.com/hook'" />
              <div v-if="form.format === 'gotify'" class="field-hint">{{ t('webhooks.field_url_gotify_hint') }}</div>
            </div>
            <div v-if="form.format === 'gotify'" class="field" style="margin-bottom: var(--space-4)">
              <label for="whToken">{{ t('webhooks.field_token') }}</label>
              <input id="whToken" class="input mono" type="password" v-model="form.secret"
                     :placeholder="editId ? t('webhooks.field_token_ph_edit') : t('webhooks.field_token_ph_new')" />
              <div class="field-hint">{{ t('webhooks.field_token_hint') }}</div>
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="whDesc">{{ t('webhooks.field_desc') }}</label>
              <input id="whDesc" class="input" type="text" v-model="form.description" :placeholder="t('webhooks.field_desc_ph')" />
            </div>
            <!-- Optional extra auth header (e.g. Authorization/X-API-Key) some
                 receivers require alongside the HMAC signature. headerValue
                 follows the same one-time-secret convention as the webhook's
                 own secret: never prefilled on edit, blank means "no change". -->
            <div class="form-grid" style="margin-bottom: var(--space-4)">
              <div class="field">
                <label for="whHeaderName">{{ t('webhooks.field_header_name') }}</label>
                <input id="whHeaderName" class="input mono" type="text" v-model="form.headerName" placeholder="Authorization" />
              </div>
              <div class="field">
                <label for="whHeaderValue">{{ t('webhooks.field_header_value') }}</label>
                <input id="whHeaderValue" class="input mono" type="password" v-model="form.headerValue"
                       :placeholder="editId && form.headerName ? t('webhooks.field_header_value_ph_edit') : 'Bearer …'" />
              </div>
              <div class="field-hint field-full">{{ t('webhooks.field_header_hint') }}</div>
            </div>
            <div class="field">
              <label>{{ t('webhooks.field_events') }}</label>
              <div class="field-hint" style="margin-bottom: var(--space-2)">{{ t('webhooks.field_events_hint') }}</div>
              <div style="display: flex; gap: var(--space-2); margin-bottom: var(--space-2)">
                <button type="button" class="btn btn-ghost btn-sm" @click="selectAllEventTypes">{{ t('webhooks.select_all') }}</button>
                <button type="button" class="btn btn-ghost btn-sm" @click="selectNoEventTypes">{{ t('webhooks.select_none') }}</button>
              </div>
              <div style="display: flex; flex-direction: column; gap: var(--space-2)">
                <label v-for="et in eventTypes" :key="et"
                       style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 400">
                  <input type="checkbox" :checked="form.eventTypes.includes(et)" @change="toggleEventType(et)"
                         style="width: 16px; height: 16px; accent-color: var(--accent)" />
                  {{ eventTypeLabel(et) }}
                </label>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('webhooks.btn_saving') : t('webhooks.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- One-time secret reveal — same posture as a peer's QR/.conf download. -->
    <div v-if="revealedSecret" class="modal-backdrop" @click.self="dismissSecret">
      <div class="modal modal-sm">
        <div class="modal-header">
          <h2>{{ t('webhooks.secret_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="dismissSecret">✕</button>
        </div>
        <div class="modal-body">
          <div class="callout callout-warn" style="margin-bottom: var(--space-4)">
            {{ t('webhooks.secret_warning') }}
          </div>
          <div class="field">
            <label>{{ t('webhooks.field_secret') }}</label>
            <pre class="code-block">{{ revealedSecret.secret }}</pre>
          </div>
          <div class="field-hint">{{ t('webhooks.secret_hint') }}</div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-secondary" @click="copySecret">
            <Icon name="copy" :size="13" />{{ t('webhooks.btn_copy') }}
          </button>
          <button type="button" class="btn btn-primary" @click="dismissSecret">{{ t('webhooks.btn_done') }}</button>
        </div>
      </div>
    </div>
  `,
});
