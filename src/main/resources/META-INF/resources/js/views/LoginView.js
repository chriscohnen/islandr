import { defineComponent, ref } from "vue";
import { auth } from "/js/app.js";
import { t, setLocale, locale } from "/js/i18n.js";
import { Icon } from "/js/Icons.js";
import { browserSupportsWebauthn, loginWithSecurityKey } from "/js/webauthnClient.js";
import { drawNightScene } from "/js/LoginNightSky.js";

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
      // The one enabled generic OIDC provider (issue #69), if that's what's
      // active instead of MS365/Google — { providerKey, displayName } | null.
      activeCustom: null,
      providersLoaded: false,
      showLocal: false,
      // Security keys for the local recovery admin only (ADR-0028) — offered
      // alongside the password, never instead of it (decision 2026-09-22,
      // Option A: the offline reset covers the lockout risk, but there is no
      // reason to bet the only way in on it when the password still works).
      webauthnUsable: false,   // a key exists and works from this hostname
      webauthnElsewhere: false, // a key exists, but not for this hostname
      webauthnLoading: false,
      lang: locale.current,
      // The topbar carries the theme toggle, and the topbar does not exist
      // before signing in — so this screen was stuck with whatever the system
      // or a previous session decided. Same key and same attribute as the one
      // in the shell, so the choice carries straight through.
      theme: document.documentElement.getAttribute("data-theme") || "dark",
    };
  },
  computed: {
    _lang() { return locale.current; },
    activeOidc() {
      if (this.providers.microsoft) return "microsoft";
      if (this.providers.google) return "google";
      if (this.activeCustom) return this.activeCustom.providerKey;
      return null;
    },
    activeOidcLabel() {
      // Reference this.lang so Vue re-evaluates when the locale changes.
      void this.lang;
      if (this.activeOidc === "microsoft") return this.t("login.ms");
      if (this.activeOidc === "google") return this.t("login.google");
      // Generic OIDC provider (issue #69) — admin-typed display name, no
      // client-side i18n label to look up.
      return this.activeCustom ? this.activeCustom.displayName : "";
    },
  },
  async mounted() {
    this.$nextTick(() => this.drawSpace());
    this._onResize = () => { this.drawSpace(); this.sizeMeteorLayer(); };
    window.addEventListener("resize", this._onResize);
    this.$nextTick(() => { this.sizeMeteorLayer(); this.scheduleMeteor(true); });

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
        for (const p of list) {
          if (p.kind === "custom") {
            if (p.enabled) this.activeCustom = { providerKey: p.providerKey, displayName: p.displayName };
          } else {
            this.providers[p.providerKey] = !!p.enabled;
          }
        }
      }
    } catch {
      // Provider-Liste ist optional — Lokal-Login funktioniert immer
    } finally {
      this.providersLoaded = true;
      if (!this.activeOidc) this.showLocal = true;  // kein OIDC → Lokal direkt zeigen
    }

    // Not gated on browser support at all — a browser with no
    // PublicKeyCredential simply never asks. Best-effort: this only decides
    // whether a button appears, never blocks the password path underneath it.
    if (browserSupportsWebauthn()) {
      try {
        const res = await fetch("/api/v1/auth/webauthn/availability");
        if (res.ok) {
          const a = await res.json();
          this.webauthnUsable = !!a.usableHere;
          this.webauthnElsewhere = !!a.registered && !a.usableHere;
        }
      } catch {
        // Same as the OIDC provider list above — silently no button.
      }
    }
  },
  unmounted() {
    window.removeEventListener("resize", this._onResize);
    clearTimeout(this._meteorTimer);
    cancelAnimationFrame(this._meteorFrame);
  },

  methods: {
    sizeMeteorLayer() {
      const c = this.$refs.meteor;
      if (!c || !c.clientWidth) return;
      const dpr = window.devicePixelRatio || 1;
      c.width = Math.round(c.clientWidth * dpr);
      c.height = Math.round(c.clientHeight * dpr);
      c.getContext("2d").setTransform(dpr, 0, 0, dpr, 0, 0);
    },

    /**
     * A shooting star every few minutes, and nothing in between.
     *
     * Deliberately not a loop: the timer sleeps, one streak runs for under a
     * second, then it sleeps again. A permanent requestAnimationFrame on a
     * sign-in screen would cost battery for a still image — which is what the
     * reference this sky came from does.
     *
     * @param first true for the opening shot, which comes sooner so the effect
     *              is not invisible to anyone who signs in promptly.
     */
    scheduleMeteor(first) {
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
      const wait = first ? 22000 + Math.random() * 20000 : 90000 + Math.random() * 60000;
      this._meteorTimer = setTimeout(() => {
        // Nothing to see on a hidden tab, and no reason to burn frames on it.
        if (document.hidden) { this.scheduleMeteor(false); return; }
        this.runMeteor(() => this.scheduleMeteor(false));
      }, wait);
    },

    runMeteor(done) {
      const c = this.$refs.meteor;
      // Only against the night sky. A streak across a pale page is a scratch.
      if (!c || this.theme === "light" || !c.clientWidth) { done(); return; }
      const x = c.getContext("2d");
      const w = c.clientWidth, h = c.clientHeight;
      const horizon = this._horizonY || h;

      // Upper half, travelling down and outward — the direction a real one
      // takes across a window.
      const dir = Math.random() < 0.5 ? 1 : -1;
      const x0 = dir === 1 ? Math.random() * w * 0.45 : w * 0.55 + Math.random() * w * 0.45;
      const y0 = Math.random() * h * 0.35;
      const len = 140 + Math.random() * 120;
      const angle = (18 + Math.random() * 14) * Math.PI / 180;
      const dx = dir * Math.cos(angle), dy = Math.sin(angle);
      const travel = Math.max(w, h) * 0.45;
      const dur = 850 + Math.random() * 350;
      const start = performance.now();

      const frame = (now) => {
        const p = Math.min(1, (now - start) / dur);
        x.clearRect(0, 0, w, h);
        // Fade in fast, out slowly — a streak that ends abruptly reads as a
        // rendering glitch rather than something that passed.
        const alpha = p < 0.15 ? p / 0.15 : 1 - (p - 0.15) / 0.85;
        const hx = x0 + dx * travel * p, hy = y0 + dy * travel * p;
        // It burns up at the horizon rather than streaking across the water.
        if (hy < horizon) {
          const tx = hx - dx * len, ty = hy - dy * len;
          const g = x.createLinearGradient(tx, ty, hx, hy);
          g.addColorStop(0, "rgba(180, 220, 240, 0)");
          g.addColorStop(1, "rgba(215, 240, 252, " + (0.7 * alpha).toFixed(3) + ")");
          x.strokeStyle = g;
          x.lineWidth = 1.2;
          x.lineCap = "round";
          x.beginPath();
          x.moveTo(tx, ty);
          x.lineTo(hx, hy);
          x.stroke();
        }
        if (p < 1) {
          this._meteorFrame = requestAnimationFrame(frame);
        } else {
          x.clearRect(0, 0, w, h);
          done();
        }
      };
      this._meteorFrame = requestAnimationFrame(frame);
    },

    drawSpace() {
      const c = this.$refs.space;
      if (!c) return;
      const dpr = window.devicePixelRatio || 1;
      const w = c.clientWidth, h = c.clientHeight;
      if (!w || !h) return;
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
      const x = c.getContext("2d");
      x.setTransform(dpr, 0, 0, dpr, 0, 0);

      const dark = document.documentElement.getAttribute("data-theme") !== "light";
      // The wash is a hero wash and stays inside the 3% the brief allows: three
      // stops of near-black navy, not a colour ramp. Light mode gets the
      // canvas colour it already has — a night sky poured over a pale page is
      // not subtlety, it is a different product.
      const g = x.createRadialGradient(w / 2, h / 2, 50, w / 2, h / 2, Math.max(w, h));
      if (dark) {
        // Anchored to the app's own dark canvas (#0A0C11) rather than the
        // reference's absolute values. The reference was a standalone page;
        // here the sign-in screen sits in a product with a defined canvas, and
        // a wash starting at #0f1a30 read as a brighter, bluer page than
        // everything behind it.
        g.addColorStop(0, "#0d1119");
        g.addColorStop(0.5, "#0a0c11");
        g.addColorStop(1, "#06070b");
      } else {
        g.addColorStop(0, "#ffffff");
        g.addColorStop(0.6, "#f7f9fb");
        g.addColorStop(1, "#eef3f7");
      }
      x.fillStyle = g;
      x.fillRect(0, 0, w, h);

      // Night: the turning sky and the islands take over from the flat stars.
      if (dark) {
        const r = this.$refs;
        if (r.sky) this._horizonY = drawNightScene(r, w, h);
        return;
      }

      // Deterministic per size, so a resize does not reshuffle the sky under
      // someone who is mid-thought. Not Math.random().
      let seed = 1013904223;
      const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);

      const count = Math.round((w * h) / 18000);
      for (let i = 0; i < count; i++) {
        const sx = Math.round(rnd() * w);
        const sy = Math.round(rnd() * h);
        const size = rnd() < 0.8 ? 1 : 1.5;
        const a = rnd() * 0.5 + 0.15;
        x.fillStyle = "rgba(31, 148, 173, " + a * 0.55 + ")";
        x.fillRect(sx, sy, size, size);
      }
    },
    toggleTheme() {
      this.theme = this.theme === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", this.theme);
      localStorage.setItem("islandr.theme", this.theme);
      // The night layers were hidden until now and measured zero wide.
      this.$nextTick(() => { this.drawSpace(); this.sizeMeteorLayer(); });
    },
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
    async loginWithKey() {
      this.webauthnLoading = true;
      this.error = null;
      try {
        await loginWithSecurityKey();
        await auth.refresh();
        this.$router.push({ name: "root" });
      } catch (e) {
        // A cancelled browser dialog (Escape, no key inserted) lands here too
        // — the same "just try again" message covers both, since neither is
        // worth distinguishing for the person looking at it.
        this.error = t("login.webauthn_error", { error: e.message });
      } finally {
        this.webauthnLoading = false;
      }
    },
    switchLang(lang) {
      setLocale(lang);
      this.lang = lang;
    },
    t(key) { return t(key); },
  },
  template: `
    <div class="center-card-page">
      <!-- Background wash, plus the faint teal dots of the light theme. Drawn
           once and on resize. -->
      <canvas ref="space" class="login-space" aria-hidden="true"></canvas>
      <!-- Night scene (dark theme only), same as the marketing site's hero:
           a sky turning once every 12 minutes, islands on the horizon, their
           lights and the tunnels from the hub. Everything is drawn once per
           size; the turning is a CSS rotation the compositor does. -->
      <div v-show="theme === 'dark'" class="login-night" aria-hidden="true">
        <div ref="skyWrap" class="login-sky"><canvas ref="sky"></canvas></div>
        <!-- Separate layer so a meteor never forces the sky to be repainted:
             cleared and redrawn only during the second or so a streak lasts. -->
        <canvas ref="meteor" class="login-layer"></canvas>
        <canvas ref="sea" class="login-layer"></canvas>
        <svg ref="routes" class="login-layer login-routes"></svg>
        <div ref="lights" class="login-layer"></div>
      </div>

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
              <circle class="login-lockup-pulse-outer" cx="64" cy="27" r="13" fill="#6FD3E8" opacity="0.22"/>
              <circle class="login-lockup-pulse-inner" cx="64" cy="27" r="8.5" fill="#9FECF8"/>
            </g>
            <text class="login-lockup-word" x="74" y="53" font-family="'IBM Plex Sans', system-ui, sans-serif" font-size="42" font-weight="600" letter-spacing="-1">island<tspan fill="#1F94AD">r</tspan></text>
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

          <!-- Generic OIDC provider (Okta/Auth0/Keycloak/any issuer, issue
               #69) — plain neutral icon, not a colored brand mark (same
               reasoning as the admin Identity page's tiles). -->
          <button v-if="activeOidc && activeOidc !== 'microsoft' && activeOidc !== 'google'"
                  type="button"
                  class="btn btn-block oauth-btn-primary"
                  @click="startOidc(activeOidc)">
            <span class="oauth-mark" aria-hidden="true"><Icon name="identity" :size="18" /></span>
            <span>{{ activeOidcLabel }}</span>
          </button>

          <template v-if="activeOidc && !showLocal">
            <div class="login-divider"><span>{{ t('login.or') }}</span></div>
            <button type="button" class="login-local-toggle" @click="showLocal = true">
              {{ t('login.local') }}
            </button>
          </template>

          <template v-if="showLocal">
            <div v-if="activeOidc" class="login-divider" style="margin-top: 0"><span>{{ t('login.local_section') }}</span></div>

            <!-- Security key for the local recovery admin (ADR-0028). Offered
                 next to the password, never instead of it — the offline reset
                 covers the lockout risk, but there is no reason to bet the
                 only way in on a key that could be lost too. -->
            <button v-if="webauthnUsable" type="button" class="btn btn-block btn-secondary"
                    :disabled="webauthnLoading" @click="loginWithKey" style="margin-bottom: var(--space-3)">
              <Icon name="key" :size="16" />
              <span>{{ webauthnLoading ? t('login.loading') : t('login.webauthn_submit') }}</span>
            </button>
            <p v-else-if="webauthnElsewhere" class="muted" style="font-size: var(--text-xs); margin: 0 0 var(--space-3)">
              {{ t('login.webauthn_elsewhere') }}
            </p>
            <div v-if="webauthnUsable" class="login-divider" style="margin-top: 0"><span>{{ t('login.or') }}</span></div>
          </template>

          <form v-if="showLocal" class="login-local-form" @submit.prevent="submitLocal">
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

        <!-- Sprach- und Themewechsel -->
        <div class="login-lang-toggle">
          <button :class="['login-lang-btn', lang === 'en' && 'active']" @click="switchLang('en')">EN</button>
          <span class="login-lang-sep">·</span>
          <button :class="['login-lang-btn', lang === 'de' && 'active']" @click="switchLang('de')">DE</button>
          <span class="login-lang-sep">·</span>
          <button type="button" class="login-lang-btn" @click="toggleTheme"
                  :title="theme === 'dark' ? t('common.theme_light') : t('common.theme_dark')"
                  :aria-label="theme === 'dark' ? t('common.theme_light') : t('common.theme_dark')">
            <Icon :name="theme === 'dark' ? 'sun' : 'moon'" :size="14" />
          </button>
        </div>
      </div>

      <p class="login-footer-legal">
        Self-hosted WireGuard<sup>®</sup> access management
      <br>
        <a href="https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"
           target="_blank" rel="noopener">{{ t('login.license') }}</a>
      </p>
    </div>
  `,
});
