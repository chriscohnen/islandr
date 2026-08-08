import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

// /dns — showcase + live status for the built-in resource-name DNS resolver
// (ADR-0023). Read-only by design, same split as Firewall: the actual
// enable/zone/upstream toggles live in Settings -> Netzwerk, this page is
// "is it on, and what can it currently resolve" plus a quick manual lookup.
export default defineComponent({
  name: "DnsView",
  data() {
    return {
      lang: locale.current,
      status: null,
      loading: true,
      error: null,
      lookupName: "",
      lookupResult: null,
      lookupError: null,
      lookingUp: false,
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
        const res = await fetch("/api/v1/dns/status");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.status = await res.json();
      } catch (e) {
        this.error = t("dns.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async lookup() {
      if (!this.lookupName.trim()) return;
      this.lookingUp = true;
      this.lookupError = null;
      this.lookupResult = null;
      try {
        const res = await fetch("/api/v1/dns/lookup", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ name: this.lookupName.trim() }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.lookupResult = await res.json();
      } catch (e) {
        this.lookupError = t("dns.error_lookup", { error: e.message });
      } finally {
        this.lookingUp = false;
      }
    },
    statusBadge() {
      if (!this.status) return "badge-neutral";
      if (!this.status.enabled) return "badge-neutral";
      return this.status.running ? "badge-success" : "badge-warning";
    },
    statusLabel() {
      if (!this.status) return "";
      if (!this.status.enabled) return t("dns.status_off");
      return this.status.running ? t("dns.status_active") : t("dns.status_blocked");
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('dns.title') }}</h1>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="load">{{ t('firewall.reload_btn') }}</button>
    </div>

    <div class="callout callout-info" style="margin-bottom: var(--space-4)">
      <div>{{ t('dns.intro') }}</div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading && !status" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="status">
      <div v-if="!status.enabled" class="callout callout-warn" style="margin-bottom: var(--space-4)">
        <div>{{ t('dns.disabled_hint') }} <router-link :to="{ path: '/settings', query: { tab: 'network' } }" style="color:inherit;text-decoration:underline">{{ t('dns.disabled_link') }}</router-link></div>
      </div>
      <div v-else-if="!status.running" class="callout callout-warn" style="margin-bottom: var(--space-4)">
        <div>{{ t('dns.blocked_hint') }} <router-link :to="{ path: '/settings', query: { tab: 'network' } }" style="color:inherit;text-decoration:underline">{{ t('dns.disabled_link') }}</router-link></div>
      </div>

      <div class="card card-pad" style="margin-bottom: var(--space-5)">
        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-4)">
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dns.status') }}</div>
            <div><span :class="['badge', statusBadge()]">{{ statusLabel() }}</span></div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dns.zone') }}</div>
            <div class="mono" style="font-size: var(--text-sm)">{{ status.zone }}</div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dns.bind_address') }}</div>
            <div class="mono" style="font-size: var(--text-sm)">{{ status.bindAddress ? (status.bindAddress + ':' + status.port) : '—' }}</div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dns.resolvable_count') }}</div>
            <div style="font-family: var(--font-mono); font-size: var(--text-xl); font-weight: 600">{{ status.resolvableCount }}</div>
          </div>
          <div>
            <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dns.upstream') }}</div>
            <div class="mono" style="font-size: var(--text-sm)">{{ (status.upstreams || []).join(', ') || '—' }}</div>
          </div>
        </div>
      </div>

      <div class="card card-pad" style="margin-bottom: var(--space-5)">
        <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('dns.resolvable_names_title') }}</h2>
        <p class="field-hint" style="margin-bottom: var(--space-3)">{{ t('dns.resolvable_names_hint') }}</p>
        <div v-if="!status.resolvableNames || status.resolvableNames.length === 0" class="muted" style="font-size: var(--text-sm)">
          {{ t('dns.resolvable_names_empty') }}
        </div>
        <div v-else style="display: flex; flex-direction: column; gap: var(--space-1); max-height: 240px; overflow-y: auto">
          <button v-for="name in status.resolvableNames" :key="name" type="button"
                  class="mono" style="text-align: left; background: none; border: none; padding: 2px 0; cursor: pointer; color: var(--fg1); font-size: var(--text-sm)"
                  :title="t('dns.resolvable_names_use_hint')"
                  @click="lookupName = name">{{ name }}</button>
        </div>
      </div>

      <div class="card card-pad">
        <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('dns.lookup_title') }}</h2>
        <p class="field-hint" style="margin-bottom: var(--space-4)">{{ t('dns.lookup_hint') }}</p>
        <form @submit.prevent="lookup" style="display: flex; align-items: flex-end; gap: var(--space-3); flex-wrap: wrap">
          <div class="field" style="margin: 0; flex: 1; min-width: 260px">
            <label for="dnsLookupName">{{ t('dns.lookup_label') }}</label>
            <input id="dnsLookupName" class="input mono" v-model="lookupName"
                   :placeholder="'fileserver.homeoffice.' + (status.zone || 'islandr.internal')" />
          </div>
          <button type="submit" class="btn btn-secondary" :disabled="lookingUp || !lookupName.trim()">
            {{ lookingUp ? t('dns.lookup_btn_busy') : t('dns.lookup_btn') }}
          </button>
        </form>

        <div v-if="lookupError" class="callout callout-error" style="margin-top: var(--space-4)">{{ lookupError }}</div>

        <div v-if="lookupResult" style="margin-top: var(--space-4)">
          <div v-if="lookupResult.result === 'answer'" class="callout callout-success">
            <div>{{ t('dns.lookup_result_answer', { ip: lookupResult.ip }) }}</div>
            <div class="mono" style="font-size: var(--text-sm); margin-top: 2px; opacity: 0.85">{{ lookupResult.fqdn }}</div>
          </div>
          <div v-else-if="lookupResult.result === 'nxdomain'" class="callout callout-warn">
            <div>{{ t('dns.lookup_result_nxdomain') }}</div>
          </div>
          <div v-else-if="lookupResult.ip" class="callout callout-info">
            <div>{{ t('dns.lookup_result_upstream_answer', { ip: lookupResult.ip, upstream: lookupResult.upstream }) }}</div>
          </div>
          <div v-else class="callout callout-info">
            <div>{{ t('dns.lookup_result_not_managed') }}</div>
          </div>
        </div>
      </div>
    </div>
  `,
});
