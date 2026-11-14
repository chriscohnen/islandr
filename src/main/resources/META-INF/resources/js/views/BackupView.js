import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale, formatDate } from "/js/i18n.js";
import { confirmDialog } from "/js/confirmDialog.js";
import { onEscape } from "/js/keyboard.js";

// Backups to an S3-compatible bucket (backup-s3): the bucket, a backup key the
// admin writes down, "back up now", and a restore that works on a freshly
// installed instance from nothing but the bucket's details and that key.
// Islandr never deletes anything in the bucket — expiry is the bucket's own
// lifecycle rule (see the install docs).
const EMPTY_CONN = () => ({ endpoint: "", region: "", bucket: "", prefix: "", accessKey: "", secretKey: "", pathStyle: true });

export default defineComponent({
  name: "BackupView",
  components: { Icon },
  data() {
    return {
      lang: locale.current,
      status: null,
      loading: true,
      error: null,
      form: EMPTY_CONN(),
      saving: false,
      saved: false,
      formError: null,
      keyBusy: false,
      keyError: null,
      newKey: null,          // the key, while the one-time dialog is open
      keyNoted: false,
      copied: false,
      schedForm: { schedule: "off", time: "03:00", dayOfWeek: 1 },
      schedBusy: false,
      schedError: null,
      schedSaved: false,
      runBusy: false,
      runError: null,
      runResult: null,
      listing: null,         // null = not loaded yet
      listBusy: false,
      listError: null,
      selected: null,
      restoreKey: "",
      useOwnConn: false,     // a fresh instance: give the bucket's details with the restore
      restoreConn: EMPTY_CONN(),
      restoreBusy: false,
      restoreError: null,
      restoreResult: null,
    };
  },
  computed: {
    _lang() { return locale.current; },
    configured() { return !!(this.status && this.status.endpoint && this.status.bucket && this.status.secretKeySet); },
    canListStored() { return !this.useOwnConn && this.configured; },
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => { if (this.newKey && this.keyNoted) this.closeKey(); });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    formatDate(iso) { return formatDate(iso); },
    bytes(n) {
      if (n == null) return "—";
      if (n < 1024) return n + " B";
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
      return (n / 1024 / 1024).toFixed(1) + " MB";
    },
    async fail(res) {
      const text = (await res.text().catch(() => "")) || "";
      return text.length > 0 && text.length < 400 ? text : "HTTP " + res.status;
    },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/admin/backup");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.status = await res.json();
        this.schedForm = { schedule: this.status.schedule || "off", time: this.status.scheduleTime || "03:00", dayOfWeek: this.status.scheduleDay || 1 };
        this.form = {
          endpoint: this.status.endpoint || "", region: this.status.region || "", bucket: this.status.bucket || "",
          prefix: this.status.prefix || "", accessKey: this.status.accessKey || "", secretKey: "",
          pathStyle: this.status.pathStyle !== false,
        };
      } catch (e) {
        this.error = t("backup.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    connBody(c) {
      return {
        endpoint: c.endpoint.trim(), region: c.region.trim() || null, bucket: c.bucket.trim(),
        prefix: c.prefix.trim() || null, accessKey: c.accessKey.trim(), secretKey: c.secretKey || null,
        pathStyle: c.pathStyle,
      };
    },
    async save() {
      this.saving = true; this.formError = null; this.saved = false;
      try {
        const res = await fetch("/api/v1/admin/backup/config", {
          method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(this.connBody(this.form)),
        });
        if (!res.ok) { this.formError = await this.fail(res); return; }
        this.status = await res.json();
        this.form.secretKey = "";
        this.saved = true;
      } catch (e) {
        this.formError = String(e);
      } finally {
        this.saving = false;
      }
    },
    async saveSchedule() {
      this.schedBusy = true; this.schedError = null; this.schedSaved = false;
      try {
        const res = await fetch("/api/v1/admin/backup/schedule", {
          method: "PUT", headers: { "content-type": "application/json" },
          body: JSON.stringify({ schedule: this.schedForm.schedule, time: this.schedForm.time, dayOfWeek: Number(this.schedForm.dayOfWeek) }),
        });
        if (!res.ok) { this.schedError = await this.fail(res); return; }
        this.status = await res.json();
        this.schedSaved = true;
      } catch (e) {
        this.schedError = String(e);
      } finally {
        this.schedBusy = false;
      }
    },
    async makeKey() {
      const replace = !!(this.status && this.status.backupKeySet);
      if (replace && !await confirmDialog(t("backup.confirm_new_key"))) return;
      this.keyBusy = true; this.keyError = null;
      try {
        const res = await fetch("/api/v1/admin/backup/key", {
          method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ replace }),
        });
        if (!res.ok) { this.keyError = await this.fail(res); return; }
        this.newKey = (await res.json()).key;
        this.keyNoted = false; this.copied = false;
        await this.load();
      } catch (e) {
        this.keyError = String(e);
      } finally {
        this.keyBusy = false;
      }
    },
    async copyKey() {
      try { await navigator.clipboard.writeText(this.newKey); this.copied = true; } catch (e) { /* the key is on screen to write down */ }
    },
    closeKey() { this.newKey = null; },
    async run() {
      this.runBusy = true; this.runError = null; this.runResult = null;
      try {
        const res = await fetch("/api/v1/admin/backup/run", { method: "POST", headers: { "content-type": "application/json" } });
        if (!res.ok) { this.runError = await this.fail(res); return; }
        this.runResult = await res.json();
        this.listing = null;
      } catch (e) {
        this.runError = String(e);
      } finally {
        this.runBusy = false;
      }
    },
    async loadList() {
      this.listBusy = true; this.listError = null; this.selected = null;
      try {
        const body = this.useOwnConn ? { conn: this.connBody(this.restoreConn) } : {};
        const res = await fetch("/api/v1/admin/backup/list", {
          method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
        });
        if (!res.ok) { this.listError = await this.fail(res); return; }
        this.listing = (await res.json()).backups;
      } catch (e) {
        this.listError = String(e);
      } finally {
        this.listBusy = false;
      }
    },
    async restore() {
      if (!this.selected || !this.restoreKey.trim()) return;
      if (!await confirmDialog(t("backup.confirm_restore"))) return;
      this.restoreBusy = true; this.restoreError = null; this.restoreResult = null;
      try {
        const body = { name: this.selected, key: this.restoreKey.trim() };
        if (this.useOwnConn) body.conn = this.connBody(this.restoreConn);
        const res = await fetch("/api/v1/admin/backup/restore", {
          method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
        });
        if (!res.ok) { this.restoreError = await this.fail(res); return; }
        this.restoreResult = await res.json();
        this.restoreKey = "";
      } catch (e) {
        this.restoreError = String(e);
      } finally {
        this.restoreBusy = false;
      }
    },
  },
  template: `
    <div>
      <div class="page-header">
        <div>
          <h1>{{ t('backup.title') }}</h1>
          <p class="page-sub">{{ t('backup.subtitle') }}</p>
        </div>
      </div>

      <div v-if="loading" class="card card-pad"><div class="skeleton" style="height: 120px"></div></div>
      <div v-else-if="error" class="callout callout-warning">{{ error }}</div>
      <template v-else>

        <!-- 1. Where backups go -->
        <form class="card card-pad" style="margin-bottom: var(--space-5)" @submit.prevent="save">
          <h2 style="margin-top: 0">{{ t('backup.bucket_title') }}</h2>
          <p class="muted">{{ t('backup.bucket_hint') }}</p>
          <div v-if="status && !status.secretsEncrypted" class="callout callout-warning" style="margin-bottom: var(--space-4)">
            {{ t('backup.unencrypted_secrets') }}
          </div>
          <div class="form-grid">
            <div class="field">
              <label for="bkEndpoint">{{ t('backup.field_endpoint') }}</label>
              <input id="bkEndpoint" class="input mono" v-model="form.endpoint" placeholder="https://s3.eu-central-1.amazonaws.com" required />
            </div>
            <div class="field">
              <label for="bkRegion">{{ t('backup.field_region') }}</label>
              <input id="bkRegion" class="input mono" v-model="form.region" placeholder="us-east-1" />
              <div class="field-hint">{{ t('backup.hint_region') }}</div>
            </div>
            <div class="field">
              <label for="bkBucket">{{ t('backup.field_bucket') }}</label>
              <input id="bkBucket" class="input mono" v-model="form.bucket" required />
            </div>
            <div class="field">
              <label for="bkPrefix">{{ t('backup.field_prefix') }}</label>
              <input id="bkPrefix" class="input mono" v-model="form.prefix" placeholder="islandr-backups/" />
            </div>
            <div class="field">
              <label for="bkAccess">{{ t('backup.field_access') }}</label>
              <input id="bkAccess" class="input mono" v-model="form.accessKey" autocomplete="off" required />
            </div>
            <div class="field">
              <label for="bkSecret">{{ t('backup.field_secret') }}</label>
              <input id="bkSecret" class="input mono" type="password" v-model="form.secretKey" autocomplete="new-password"
                     :placeholder="status && status.secretKeySet ? t('backup.secret_unchanged') : ''" :required="!(status && status.secretKeySet)" />
            </div>
          </div>
          <label style="margin-top: var(--space-3); display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
            <input type="checkbox" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" v-model="form.pathStyle" />
            <span>{{ t('backup.field_path_style') }}</span>
          </label>
          <div class="field-hint">{{ t('backup.hint_path_style') }}</div>
          <div class="callout callout-info" style="margin-top: var(--space-4)">{{ t('backup.hint_no_delete') }}</div>
          <div v-if="formError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ formError }}</div>
          <div style="margin-top: var(--space-4); display: flex; gap: var(--space-3); align-items: center">
            <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? t('backup.saving') : t('backup.btn_save') }}</button>
            <span v-if="saved" class="badge badge-success"><Icon name="check" :size="12" />{{ t('backup.saved') }}</span>
          </div>
        </form>

        <!-- 2. The key -->
        <div class="card card-pad" style="margin-bottom: var(--space-5)">
          <h2 style="margin-top: 0">{{ t('backup.key_title') }}</h2>
          <p class="muted">{{ t('backup.key_hint') }}</p>
          <div style="display: flex; gap: var(--space-3); align-items: center; flex-wrap: wrap">
            <span v-if="status.backupKeySet" class="badge badge-success"><Icon name="key" :size="12" />{{ t('backup.key_set') }}</span>
            <span v-else class="badge badge-warning"><Icon name="key" :size="12" />{{ t('backup.key_missing') }}</span>
            <button class="btn" :class="status.backupKeySet ? 'btn-ghost' : 'btn-primary'" :disabled="keyBusy" @click="makeKey">
              {{ status.backupKeySet ? t('backup.btn_new_key') : t('backup.btn_make_key') }}
            </button>
          </div>
          <div v-if="keyError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ keyError }}</div>
        </div>

        <!-- 3. Back up now -->
        <div class="card card-pad" style="margin-bottom: var(--space-5)">
          <h2 style="margin-top: 0">{{ t('backup.run_title') }}</h2>
          <p class="muted">{{ t('backup.run_hint') }}</p>
          <button class="btn btn-primary" :disabled="runBusy || !configured || !status.backupKeySet" @click="run">
            {{ runBusy ? t('backup.running') : t('backup.btn_run') }}
          </button>
          <div v-if="runResult" class="callout callout-ok" style="margin-top: var(--space-3)">
            <Icon name="check" :size="13" /> {{ t('backup.run_done', { name: runResult.name, size: bytes(runResult.size) }) }}
          </div>
          <div v-if="runError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ runError }}</div>
        </div>

        <!-- 3b. Schedule -->
        <form class="card card-pad" style="margin-bottom: var(--space-5)" @submit.prevent="saveSchedule">
          <h2 style="margin-top: 0">{{ t('backup.sched_title') }}</h2>
          <p class="muted">{{ t('backup.sched_hint') }}</p>
          <div class="form-grid">
            <div class="field">
              <label for="bkSched">{{ t('backup.sched_label') }}</label>
              <select id="bkSched" class="input" v-model="schedForm.schedule">
                <option value="off">{{ t('backup.sched_off') }}</option>
                <option value="daily">{{ t('backup.sched_daily') }}</option>
                <option value="weekly">{{ t('backup.sched_weekly') }}</option>
              </select>
            </div>
            <div v-if="schedForm.schedule === 'weekly'" class="field">
              <label for="bkDay">{{ t('backup.sched_day') }}</label>
              <select id="bkDay" class="input" v-model="schedForm.dayOfWeek">
                <option v-for="d in [1,2,3,4,5,6,7]" :key="d" :value="d">{{ t('backup.day_' + d) }}</option>
              </select>
            </div>
            <div v-if="schedForm.schedule !== 'off'" class="field">
              <label for="bkTime">{{ t('backup.sched_time') }}</label>
              <input id="bkTime" class="input mono" type="time" v-model="schedForm.time" required />
              <div class="field-hint">{{ t('backup.sched_zone', { zone: status.zone }) }}</div>
            </div>
          </div>
          <div v-if="!status.backupKeySet" class="field-hint">{{ t('backup.sched_needs_key') }}</div>
          <div v-if="schedError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ schedError }}</div>
          <div style="margin-top: var(--space-4); display: flex; gap: var(--space-3); align-items: center; flex-wrap: wrap">
            <button type="submit" class="btn btn-primary" :disabled="schedBusy">{{ schedBusy ? t('backup.saving') : t('backup.btn_save') }}</button>
            <span v-if="schedSaved" class="badge badge-success"><Icon name="check" :size="12" />{{ t('backup.saved') }}</span>
          </div>
          <div v-if="status.lastRunAt" style="margin-top: var(--space-4); display: flex; gap: var(--space-2); align-items: baseline; flex-wrap: wrap">
            <span v-if="status.lastRunOk" class="badge badge-success"><Icon name="check" :size="12" />{{ t('backup.last_ok') }}</span>
            <span v-else class="badge badge-warning">{{ t('backup.last_failed') }}</span>
            <span class="muted" style="font-size: var(--text-sm)">{{ formatDate(status.lastRunAt) }}</span>
            <span class="mono muted" style="font-size: var(--text-xs); overflow-wrap: anywhere">{{ status.lastRunMessage }}</span>
          </div>
        </form>

        <!-- 4. Restore (also: bootstrap a fresh instance) -->
        <div class="card card-pad">
          <h2 style="margin-top: 0">{{ t('backup.restore_title') }}</h2>
          <p class="muted">{{ t('backup.restore_hint') }}</p>

          <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
            <input type="checkbox" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" v-model="useOwnConn" @change="listing = null; selected = null" />
            <span>{{ t('backup.use_own_conn') }}</span>
          </label>
          <div class="field-hint">{{ t('backup.use_own_conn_hint') }}</div>
          <div v-if="useOwnConn" class="form-grid" style="margin-top: var(--space-3)">
            <div class="field"><label>{{ t('backup.field_endpoint') }}</label><input class="input mono" v-model="restoreConn.endpoint" placeholder="https://…" /></div>
            <div class="field"><label>{{ t('backup.field_region') }}</label><input class="input mono" v-model="restoreConn.region" placeholder="us-east-1" /></div>
            <div class="field"><label>{{ t('backup.field_bucket') }}</label><input class="input mono" v-model="restoreConn.bucket" /></div>
            <div class="field"><label>{{ t('backup.field_prefix') }}</label><input class="input mono" v-model="restoreConn.prefix" placeholder="islandr-backups/" /></div>
            <div class="field"><label>{{ t('backup.field_access') }}</label><input class="input mono" v-model="restoreConn.accessKey" autocomplete="off" /></div>
            <div class="field"><label>{{ t('backup.field_secret') }}</label><input class="input mono" type="password" v-model="restoreConn.secretKey" autocomplete="new-password" /></div>
          </div>

          <div style="margin-top: var(--space-4)">
            <button class="btn" :disabled="listBusy || (!useOwnConn && !configured)" @click="loadList">
              {{ listBusy ? t('backup.loading_list') : t('backup.btn_list') }}
            </button>
          </div>
          <div v-if="listError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ listError }}</div>

          <template v-if="listing">
            <div v-if="listing.length === 0" class="empty-state" style="margin-top: var(--space-4)">{{ t('backup.list_empty') }}</div>
            <table v-else class="table" style="margin-top: var(--space-4)">
              <thead><tr>
                <th></th><th>{{ t('backup.col_when') }}</th><th>{{ t('backup.col_host') }}</th><th>{{ t('backup.col_version') }}</th><th>{{ t('backup.col_size') }}</th>
              </tr></thead>
              <tbody>
                <tr v-for="b in listing" :key="b.name" style="cursor: pointer" @click="selected = b.name">
                  <td><input type="radio" :checked="selected === b.name" :aria-label="b.name" @change="selected = b.name" /></td>
                  <td>{{ formatDate(b.createdAt || b.lastModified) }}</td>
                  <td class="mono">{{ b.host || '—' }}</td>
                  <td class="mono">{{ b.islandrVersion || '—' }}</td>
                  <td class="mono">{{ bytes(b.size) }}</td>
                </tr>
              </tbody>
            </table>

            <div v-if="listing.length" class="field" style="margin-top: var(--space-4); max-width: 34rem">
              <label for="bkRestoreKey">{{ t('backup.field_restore_key') }}</label>
              <input id="bkRestoreKey" class="input mono" v-model="restoreKey" autocomplete="off" spellcheck="false" placeholder="IBK1-XXXX-XXXX-…" />
            </div>
            <div v-if="listing.length" style="margin-top: var(--space-3)">
              <button class="btn btn-primary" :disabled="restoreBusy || !selected || !restoreKey.trim()" @click="restore">
                {{ restoreBusy ? t('backup.restoring') : t('backup.btn_restore') }}
              </button>
            </div>
          </template>
          <div v-if="restoreError" class="callout callout-warning" style="margin-top: var(--space-3)">{{ restoreError }}</div>
          <div v-if="restoreResult" class="callout callout-ok" style="margin-top: var(--space-3)">
            <Icon name="check" :size="13" />
            {{ t('backup.restore_done', { users: restoreResult.users, peers: restoreResult.peers, sites: restoreResult.sites, resources: restoreResult.resources }) }}
          </div>
        </div>
      </template>

      <!-- The key, shown once. Calm, but it cannot be closed before it is noted. -->
      <div v-if="newKey" class="modal-backdrop">
        <div class="modal modal-sm">
          <div class="modal-header"><h2>{{ t('backup.newkey_title') }}</h2></div>
          <div class="modal-body">
            <div class="callout callout-warning" style="margin-bottom: var(--space-4)">{{ t('backup.newkey_warning') }}</div>
            <pre class="code-block" style="font-size: var(--text-base); white-space: pre-wrap; word-break: break-all">{{ newKey }}</pre>
            <div class="field-hint">{{ t('backup.newkey_hint') }}</div>
            <label style="margin-top: var(--space-4); display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
              <input type="checkbox" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" v-model="keyNoted" />
              <span>{{ t('backup.newkey_noted') }}</span>
            </label>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-secondary" @click="copyKey">
              <Icon :name="copied ? 'check' : 'copy'" :size="13" />{{ copied ? t('backup.copied') : t('backup.btn_copy') }}
            </button>
            <button type="button" class="btn btn-primary" :disabled="!keyNoted" @click="closeKey">{{ t('backup.btn_done') }}</button>
          </div>
        </div>
      </div>
    </div>
  `,
});
