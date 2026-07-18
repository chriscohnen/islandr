import { defineComponent } from "vue";
import { t, locale, relativeTime, formatDate } from "/js/i18n.js";

// /firewall — admin reads the authoritative nftables ruleset Islandr last
// applied + can force a resync. The display is read-only by design; rules
// are generated from DB state, so the way to "edit" a rule is to change
// the underlying grant / port / role / peer, which triggers a recompute.
export default defineComponent({
  name: "FirewallView",
  data() {
    return {
      lang: locale.current,
      data: null,
      loading: true,
      error: null,
      resyncing: false,
      resyncInfo: null,
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
        const res = await fetch("/api/v1/firewall");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.data = await res.json();
      } catch (e) {
        this.error = t("firewall.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async resync() {
      this.resyncing = true;
      this.error = null;
      this.resyncInfo = null;
      try {
        const res = await fetch("/api/v1/firewall/resync", { method: "POST" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.data = await res.json();
        this.resyncInfo = this.data.status === "ok"
            ? t("firewall.resync_ok", { n: this.data.ruleCount })
            : t("firewall.resync_fail");
      } catch (e) {
        this.error = t("firewall.error_resync", { error: e.message });
      } finally {
        this.resyncing = false;
      }
    },
    relativeTime(iso) { return relativeTime(iso); },
    formatDate(iso) { return formatDate(iso); },
    statusLabel(s) {
      if (s === "ok") return t("firewall.status_ok");
      if (s === "failed") return t("firewall.status_fail");
      return t("firewall.status_never");
    },
    statusBadge(s) {
      if (s === "ok") return "badge-success";
      if (s === "failed") return "badge-warning";
      return "badge-neutral";
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('firewall.title') }}</h1>
      <div style="display: flex; gap: var(--space-3); align-items: center">
        <button class="btn btn-ghost btn-sm" :disabled="loading" @click="load">{{ t('firewall.reload_btn') }}</button>
        <button class="btn btn-primary btn-sm" :disabled="resyncing" @click="resync">
          {{ resyncing ? t('firewall.resyncing') : t('firewall.resync_btn') }}
        </button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="data && data.dryRun" class="callout callout-warn" style="margin-bottom: var(--space-4)">
      <div>{{ t('firewall.dry_run_banner') }} <router-link to="/settings" style="color:inherit;text-decoration:underline">{{ t('firewall.dry_run_link') }}</router-link></div>
    </div>
    <div v-if="resyncInfo" class="callout callout-info"><div>{{ resyncInfo }}</div></div>

    <div v-if="loading && !data" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="data">
      <div class="card card-pad" style="margin-bottom: var(--space-5)">
        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-4)">
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('firewall.status') }}</div>
            <div>
              <span :class="['badge', statusBadge(data.status)]">{{ statusLabel(data.status) }}</span>
            </div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('firewall.rules') }}</div>
            <div style="font-family: var(--font-mono); font-size: var(--text-xl); font-weight: 600">{{ data.ruleCount }}</div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('firewall.last_ok') }}</div>
            <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm); color: var(--fg1)">
              <span v-if="data.lastOkAt" :title="formatDate(data.lastOkAt)">{{ relativeTime(data.lastOkAt) }}</span>
              <span v-else>—</span>
            </div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('firewall.last_try') }}</div>
            <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm); color: var(--fg1)">
              <span v-if="data.lastAttemptAt" :title="formatDate(data.lastAttemptAt)">{{ relativeTime(data.lastAttemptAt) }}</span>
              <span v-else>—</span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="data.stderr" class="callout callout-warning">
        <div>
          <strong>{{ t('firewall.nft_fail') }}</strong>
          {{ t('firewall.nft_desc') }}
          <pre class="conf-block" style="margin-top: var(--space-2); max-height: 200px">{{ data.stderr }}</pre>
        </div>
      </div>

      <div class="card card-pad">
        <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--space-3)">
          <h2 style="font-size: var(--text-md); margin: 0">{{ t('firewall.ruleset') }}</h2>
          <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
            {{ t('firewall.ruleset_desc') }}
          </div>
        </div>
        <pre class="conf-block" v-if="data.rulesetText" style="max-height: 600px">{{ data.rulesetText }}</pre>
        <div v-else class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
          {{ t('firewall.ruleset_empty') }}
        </div>
      </div>
    </div>
  `,
});
