import { defineComponent, ref } from "vue";
import { auth } from "/js/app.js";
import { t, setLocale, locale } from "/js/i18n.js";
import { Icon } from "/js/Icons.js";

// Login-Card mit Provider-Hierarchie:
//   1. Aktivierter OIDC-Provider (max. einer) erscheint als prominenter Primär-Button oben.
//   2. Lokaler Admin-Login ist standardmäßig eingeklappt — ein Link "Mit lokalem Konto
//      anmelden" öffnet das User/Passwort-Formular. Damit bleibt der Recovery-Pfad
//      jederzeit erreichbar, ohne den OIDC-Happy-Path zu verschütten.
//   3. Wenn KEIN Provider aktiv ist, ist das lokale Formular sofort sichtbar
//      (das ist dann der einzige Weg rein).
export default defineComponent({
  name: "LoginView",
  components: { Icon },
  data() {
    return {
      username: "admin",
      password: "",
      showPassword: false,
      loading: false,
      error: null,
      providers: { microsoft: false, google: false },
      providersLoaded: false,
      showLocal: false,
      lang: locale.current,
    };
  },
  computed: {
    _lang() { return locale.current; },
    activeOidc() {
      if (this.providers.microsoft) return "microsoft";
      if (this.providers.google) return "google";
      return null;
    },
    activeOidcLabel() {
      // Reference this.lang so Vue re-evaluates when the locale changes.
      void this.lang;
      return this.activeOidc === "microsoft" ? this.t("login.ms") : this.t("login.google");
    },
  },
  async mounted() {
    // IdP-Fehler aus URL-Parameter aufgreifen (?error=…&detail=…)
    const q = new URLSearchParams(window.location.search);
    if (q.get("error")) {
      this.error = "Anmeldung fehlgeschlagen: " + (q.get("detail") || q.get("error"));
      this.showLocal = true;  // damit der Admin sich notfalls lokal einloggen kann
    }
    try {
      const res = await fetch("/api/v1/auth/providers");
      if (res.ok) {
        const list = await res.json();
        for (const p of list) this.providers[p.providerKey] = !!p.enabled;
      }
    } catch {
      // Provider-Liste ist optional — Lokal-Login funktioniert immer
    } finally {
      this.providersLoaded = true;
      if (!this.activeOidc) this.showLocal = true;  // kein OIDC → Lokal direkt zeigen
    }
  },
  methods: {
    async submitLocal() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/auth/login", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ username: this.username, password: this.password }),
        });
        if (res.status === 401) {
          this.error = t("login.err_credentials");
          return;
        }
        if (res.status === 503) {
          this.error = t("login.err_no_local");
          return;
        }
        if (!res.ok) {
          this.error = "Login fehlgeschlagen (HTTP " + res.status + ").";
          return;
        }
        await auth.refresh();
        // Router guard picks the right landing page (dashboard for admin,
        // mein-zugang for everyone else).
        this.$router.push({ name: "root" });
      } catch (e) {
        this.error = "Login fehlgeschlagen: " + e.message;
      } finally {
        this.loading = false;
      }
    },
    startOidc(provider) {
      window.location.href = "/api/v1/auth/oidc/" + provider + "/start";
    },
    switchLang(lang) {
      setLocale(lang);
      this.lang = lang;
    },
    t(key) { return t(key); },
  },
  template: `
    <div class="center-card-page">
      <div class="center-card login-card">

        <!-- Primary lockup: mark + "islandr Gateway" -->
        <div class="login-lockup-wrap">
          <svg class="login-lockup" viewBox="4 8 238 62" role="img" aria-label="islandr">
            <g transform="translate(6,12) scale(0.4375)">
              <path d="M26 96 L52 84 L70 58 L98 40 M70 58 L64 27" fill="none" stroke="#1F94AD" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
              <circle cx="26" cy="96" r="5" fill="#1F94AD"/>
              <circle cx="52" cy="84" r="4.5" fill="#1F94AD"/>
              <circle cx="70" cy="58" r="6" fill="#1F94AD"/>
              <circle cx="98" cy="40" r="5" fill="#1F94AD"/>
              <circle cx="64" cy="27" r="13" fill="#6FD3E8" opacity="0.22"/>
              <circle cx="64" cy="27" r="8.5" fill="#36BAD4"/>
            </g>
            <text x="74" y="53" font-family="'IBM Plex Sans', system-ui, sans-serif" font-size="42" font-weight="600" letter-spacing="-1" fill="#172B3A">island<tspan fill="#1F94AD">r</tspan></text>
          </svg>
          <span class="login-product-name">Gateway</span>
        </div>

        <p class="login-tagline">{{ t('login.tagline') }}</p>

        <div v-if="!providersLoaded" class="muted login-subtitle">{{ t('login.loading_providers') }}</div>

        <template v-else>
          <h1 class="login-title">{{ t('login.title') }}</h1>
          <p class="login-subtitle">{{ t('login.subtitle') }}</p>

          <div v-if="error" class="error-banner" style="margin-bottom: var(--space-4)">{{ error }}</div>

          <button v-if="activeOidc === 'microsoft'"
                  type="button"
                  class="btn btn-block oauth-btn-primary"
                  @click="startOidc('microsoft')">
            <span class="oauth-mark oauth-mark--ms" aria-hidden="true">
              <span></span><span></span><span></span><span></span>
            </span>
            <span>{{ activeOidcLabel }}</span>
          </button>

          <button v-if="activeOidc === 'google'"
                  type="button"
                  class="btn btn-block oauth-btn-primary"
                  @click="startOidc('google')">
            <span class="oauth-mark oauth-mark--google" aria-hidden="true">G</span>
            <span>{{ activeOidcLabel }}</span>
          </button>

          <template v-if="activeOidc && !showLocal">
            <div class="login-divider"><span>{{ t('login.or') }}</span></div>
            <button type="button" class="login-local-toggle" @click="showLocal = true">
              {{ t('login.local') }}
            </button>
          </template>

          <form v-if="showLocal" class="login-local-form" @submit.prevent="submitLocal">
            <div v-if="activeOidc" class="login-divider" style="margin-top: 0"><span>{{ t('login.local_section') }}</span></div>

            <div class="field">
              <label for="username">{{ t('login.user') }}</label>
              <input id="username" name="username" class="input" type="text" v-model="username"
                     autocomplete="username" required />
            </div>

            <div class="field">
              <label for="password">{{ t('login.password') }}</label>
              <div class="input-reveal">
                <input id="password" name="password" class="input" :type="showPassword ? 'text' : 'password'"
                       v-model="password" autocomplete="current-password" required />
                <button type="button" class="input-reveal-btn" @click="showPassword = !showPassword"
                        :aria-label="showPassword ? t('common.pw_hide') : t('common.pw_show')"
                        :title="showPassword ? t('common.pw_hide') : t('common.pw_show')">
                  <Icon :name="showPassword ? 'eye-off' : 'eye'" :size="16" />
                </button>
              </div>
            </div>

            <button type="submit"
                    :class="['btn', 'btn-block', activeOidc ? 'btn-secondary' : 'btn-primary']"
                    :disabled="loading">
              {{ loading ? t('login.loading') : t('login.submit') }}
            </button>
          </form>
        </template>

        <!-- Sprachwechsel -->
        <div class="login-lang-toggle">
          <button :class="['login-lang-btn', lang === 'en' && 'active']" @click="switchLang('en')">EN</button>
          <span class="login-lang-sep">·</span>
          <button :class="['login-lang-btn', lang === 'de' && 'active']" @click="switchLang('de')">DE</button>
        </div>
      </div>

      <p class="login-footer-legal">
        Self-hosted WireGuard<sup>®</sup> access management
      </p>
    </div>
  `,
});
