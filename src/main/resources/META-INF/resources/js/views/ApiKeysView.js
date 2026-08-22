import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// API keys for the external automation API (issue #15, ADR-0026). List +
// create (one-time raw-key reveal, same pattern as a peer's QR/.conf) +
// revoke. No edit — a key's label/scope isn't mutable in v1, only its
// existence is (create a new one, revoke the old one).
export default defineComponent({
  name: "ApiKeysView",
  components: { Icon },
  data() {
    return {
      keys: [],
      loading: true,
      error: null,
      creating: false,
      newLabel: "",
      submitting: false,
      revealedKey: null, // { label, rawKey } while the one-time modal is open
      lang: locale.current,
    };
  },
  computed: { _lang() { return locale.current; } },
  async mounted() {
    await this.load();
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/api-keys");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.keys = await res.json();
      } catch (e) {
        this.error = t("apikeys.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.creating = true;
      this.newLabel = "";
    },
    cancelCreate() {
      this.creating = false;
      this.newLabel = "";
    },
    async submitCreate() {
      this.submitting = true;
      try {
        const res = await fetch("/api/v1/api-keys", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ label: this.newLabel }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const created = await res.json();
        this.revealedKey = { label: created.apiKey.label, rawKey: created.rawKey };
        await this.load();
        this.cancelCreate();
      } catch (e) {
        this.error = t("apikeys.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    dismissKey() {
      this.revealedKey = null;
    },
    async copyKey() {
      if (!this.revealedKey) return;
      try { await navigator.clipboard.writeText(this.revealedKey.rawKey); } catch {}
    },
    async revoke(k) {
      if (!confirm(t("apikeys.confirm_revoke", { label: k.label }))) return;
      try {
        const res = await fetch("/api/v1/api-keys/" + k.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("apikeys.error_revoke", { error: e.message });
      }
    },
  },
  template: `
    <div class="page-header">
      <div>
        <h1>{{ t('apikeys.title') }} <span v-if="keys.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ keys.length }}</span></h1>
        <p class="page-sub">{{ t('apikeys.subtitle') }}</p>
      </div>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('apikeys.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="keys.length === 0" class="empty-state">
      <h2>{{ t('apikeys.empty_title') }}</h2>
      <p>{{ t('apikeys.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('apikeys.th_label') }}</th>
          <th>{{ t('apikeys.th_prefix') }}</th>
          <th>{{ t('apikeys.th_created') }}</th>
          <th>{{ t('apikeys.th_last_used') }}</th>
          <th>{{ t('apikeys.th_status') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="k in keys" :key="k.id">
          <td>{{ k.label }}</td>
          <td class="mono">{{ k.keyPrefix }}…</td>
          <td class="muted" style="font-size: var(--text-xs)">{{ new Date(k.createdAt).toLocaleString(lang) }} · {{ k.createdBy }}</td>
          <td class="muted" style="font-size: var(--text-xs)">
            {{ k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString(lang) : t('apikeys.never_used') }}
          </td>
          <td>
            <span v-if="k.revoked" class="badge"><Icon name="unlink" :size="13" />{{ t('apikeys.revoked') }}</span>
            <span v-else class="badge badge-success"><Icon name="check" :size="13" />{{ t('apikeys.active') }}</span>
          </td>
          <td style="text-align: right">
            <button v-if="!k.revoked" class="btn btn-ghost btn-sm" @click="revoke(k)">
              <Icon name="trash" :size="13" />{{ t('apikeys.btn_revoke') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- Create dialog -->
    <div v-if="creating" class="modal-backdrop" @click.self="cancelCreate">
      <div class="modal modal-sm">
        <div class="modal-header">
          <h2>{{ t('apikeys.modal_create') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="cancelCreate">✕</button>
        </div>
        <form @submit.prevent="submitCreate">
          <div class="modal-body">
            <div class="field">
              <label for="akLabel">{{ t('apikeys.field_label') }}</label>
              <input id="akLabel" class="input" type="text" v-model="newLabel" required :placeholder="t('apikeys.field_label_ph')" />
              <div class="field-hint">{{ t('apikeys.field_label_hint') }}</div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="cancelCreate">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('apikeys.btn_saving') : t('apikeys.btn_create') }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- One-time key reveal -->
    <div v-if="revealedKey" class="modal-backdrop" @click.self="dismissKey">
      <div class="modal modal-sm">
        <div class="modal-header">
          <h2>{{ t('apikeys.reveal_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="dismissKey">✕</button>
        </div>
        <div class="modal-body">
          <div class="callout callout-warn" style="margin-bottom: var(--space-4)">
            {{ t('apikeys.reveal_warning') }}
          </div>
          <div class="field">
            <label>{{ t('apikeys.field_key') }}</label>
            <pre class="code-block">{{ revealedKey.rawKey }}</pre>
          </div>
          <div class="field-hint">{{ t('apikeys.reveal_hint') }}</div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-secondary" @click="copyKey">
            <Icon name="copy" :size="13" />{{ t('apikeys.btn_copy') }}
          </button>
          <button type="button" class="btn btn-primary" @click="dismissKey">{{ t('apikeys.btn_done') }}</button>
        </div>
      </div>
    </div>
  `,
});
