import { createApp, defineComponent, reactive } from "vue";
import { t, setLocale, locale } from "/js/i18n.js";
import { createRouter, createWebHashHistory } from "vue-router";

import LoginView from "/js/views/LoginView.js";
import UsersView from "/js/views/UsersView.js";
import PeersView from "/js/views/PeersView.js";
import SettingsView from "/js/views/SettingsView.js";
import IdentityView from "/js/views/IdentityView.js";
import MyAccessView from "/js/views/MyAccessView.js";
import AuditView from "/js/views/AuditView.js";
import DashboardView from "/js/views/DashboardView.js";
import SitesView from "/js/views/SitesView.js";
import ResourcesView from "/js/views/ResourcesView.js";
import AllResourcesView from "/js/views/AllResourcesView.js";
import PortGroupsView from "/js/views/PortGroupsView.js";
import RolesView from "/js/views/RolesView.js";
import AclMatrixView from "/js/views/AclMatrixView.js";
import FirewallView from "/js/views/FirewallView.js";
import StubView from "/js/views/StubView.js";
import Avatar from "/js/Avatar.js";
import { Icon } from "/js/Icons.js";

// ---------------------------------------------------------------------------
// Auth store. /api/v1/auth/me is the SINGLE source of truth — the session
// cookie is HttpOnly, so the SPA can't read it directly. session.me mirrors
// what /me last returned; null means "not logged in" (or "we haven't asked
// yet" — distinguished by session.loaded).
//
// No localStorage flag — the previous "islandr.auth" key was only set by the
// local-admin login path and stayed empty after an OIDC callback, which broke
// the post-callback redirect. Trust the cookie + /me, nothing else.
// ---------------------------------------------------------------------------
export const session = reactive({
  loaded: false,
  me: null,  // { principal, provider, userId, isAdmin, expiresAt } or null
});

export const auth = {
  isLoggedIn() {
    return session.me !== null;
  },
  isAdmin() {
    return !!(session.me && session.me.isAdmin);
  },
  async refresh() {
    try {
      const res = await fetch("/api/v1/auth/me");
      if (res.status === 401) {
        session.me = null;
        session.loaded = true;
        return null;
      }
      if (!res.ok) return session.me;
      session.me = await res.json();
      session.loaded = true;
      return session.me;
    } catch {
      // Network glitch — keep last known state, don't kick the user out.
      return session.me;
    }
  },
  clear() {
    session.me = null;
    session.loaded = true;  // we know the state: logged out
  },
};

// ---------------------------------------------------------------------------
// Router — hash-based so Quarkus doesn't need an SPA fallback.
// requiresAdmin routes are visible only to admins; non-admins get bounced to
// /mein-zugang. The sidebar mirrors these routes — both lists must agree.
// ---------------------------------------------------------------------------
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    // Root resolves in the guard below — we need to wait for /me before we
    // can decide between dashboard / mein-zugang / login. Make it a named
    // route so the guard can recognise it without string compare.
    { path: "/", name: "root", component: { render: () => null }, meta: { requiresAuth: true } },
    { path: "/login", name: "login", component: LoginView },

    { path: "/mein-zugang", name: "my-access", component: MyAccessView, meta: { requiresAuth: true } },

    { path: "/dashboard", name: "dashboard", component: DashboardView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/peers", name: "peers", component: PeersView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/acl", name: "acl", component: AclMatrixView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/roles-list", name: "roles-list", component: RolesView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/networks", name: "sites", component: SitesView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/networks/:siteId/resources", name: "resources", component: ResourcesView, props: true, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/resources", name: "all-resources", component: AllResourcesView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/port-groups", name: "port-groups", component: PortGroupsView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/firewall", name: "firewall", component: FirewallView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/users", name: "users", component: UsersView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/identity", name: "identity", component: IdentityView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/audit", name: "audit", component: AuditView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: "/settings", name: "settings", component: SettingsView, meta: { requiresAuth: true, requiresAdmin: true } },
  ],
});

router.beforeEach(async (to) => {
  // Always make sure /me has been asked at least once before we route. Without
  // this, a post-OIDC-callback page load lands on / with no session info yet,
  // and we'd bounce to /login even though the cookie is set.
  if (!session.loaded) await auth.refresh();

  if (to.name === "root") {
    if (!auth.isLoggedIn()) return { name: "login" };
    return { name: auth.isAdmin() ? "dashboard" : "my-access" };
  }
  if (to.name === "login" && auth.isLoggedIn()) {
    return { name: auth.isAdmin() ? "dashboard" : "my-access" };
  }
  if (to.meta.requiresAuth && !auth.isLoggedIn()) {
    return { name: "login" };
  }
  if (to.meta.requiresAdmin && !auth.isAdmin()) {
    return { name: "my-access" };
  }
  return true;
});

// ---------------------------------------------------------------------------
// Root — login view OR the sidebar shell. Shell fetches /api/v1/settings so it
// can show the setup-incomplete banner and forward `retention` to views that
// need it (UsersView, PeersView, MyAccessView).
// ---------------------------------------------------------------------------
const App = defineComponent({
  name: "App",
  components: { Avatar, Icon },
  data() {
    return {
      setupComplete: true,
      retention: "never",
      selfServicePeerCreation: true,
      googleWsAvailable: false,
      session,
        theme: document.documentElement.getAttribute("data-theme") || "light",
      lang: locale.current,
    };
  },
  computed: {
    me() { return this.session.me; },
    isAdmin() { return !!(this.me && this.me.isAdmin); },
  },
  watch: {
    "$route"(to) {
      if (to.meta.requiresAuth) {
        if (this.isAdmin) this.refreshSettings();
        this.refreshMe();
      }
    },
  },
  async mounted() {
    if (this.$route.meta.requiresAuth) {
      await this.refreshMe();
      if (this.isAdmin) await this.refreshSettings();
    }
  },
  methods: {
    async refreshSettings() {
      try {
        const res = await fetch("/api/v1/settings");
        if (!res.ok) return;
        const s = await res.json();
        this.setupComplete = !!s.setupComplete;
        this.retention = s.privateKeyRetention || "never";
        this.selfServicePeerCreation = s.selfServicePeerCreation !== false;
        this.googleWsAvailable = !!s.googleWsConfigured;
      } catch {
        // ignore — banner just won't show
      }
    },
    async refreshMe() {
      // Periodically re-validate that the cookie is still good — if the
      // server says 401 we land here with me=null, and the next navigation
      // hits the router guard which sends us to /login. No explicit push.
      await auth.refresh();
      // Apply the user's stored locale preference on load — but only if the
      // user hasn't already picked something explicitly in this browser session
      // (localStorage wins over the server value to avoid a jarring mid-session
      // switch when two tabs are open with different languages).
      if (session.me) {
        try {
          const res = await fetch("/api/v1/users/me");
          if (res.ok) {
            const profile = await res.json();
            if (profile.preferredLocale && !localStorage.getItem("islandr.locale")) {
              setLocale(profile.preferredLocale);
              this.lang = profile.preferredLocale;
            }
          }
        } catch { /* non-critical */ }
      }
    },
    onSettingsChanged(s) {
      this.setupComplete = !!s.setupComplete;
      this.retention = s.privateKeyRetention || "never";
      this.selfServicePeerCreation = s.selfServicePeerCreation !== false;
      this.googleWsAvailable = !!s.googleWsConfigured;
    },
    toggleTheme() {
      this.theme = this.theme === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", this.theme);
      localStorage.setItem("islandr.theme", this.theme);
    },
    async logout() {
      try { await fetch("/api/v1/auth/logout", { method: "POST" }); } catch {}
      auth.clear();
      this.$router.push({ name: "login" });
    },
    switchLang(lang) {
      setLocale(lang);
      this.lang = lang;
      // Persist to server if logged in — fire-and-forget, non-critical.
      if (session.me) {
        fetch("/api/v1/users/me/locale", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ locale: lang }),
        }).catch(() => {});
      }
    },
    t(key, vars) { return t(key, vars); },
  },
  template: `
    <template v-if="$route.name === 'login'">
      <router-view />
    </template>
    <div v-else class="app-shell">
      <aside class="sidebar">
        <div class="wordmark">
          <svg width="22" height="22" viewBox="0 0 128 128" aria-hidden="true" focusable="false">
            <g transform="translate(1,6)">
              <path d="M26 96 L52 84 L70 58 L98 40 M70 58 L64 27" fill="none" stroke="#3BBBD2" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/>
              <circle cx="26" cy="96" r="5" fill="#3BBBD2"/>
              <circle cx="52" cy="84" r="4.5" fill="#3BBBD2"/>
              <circle cx="70" cy="58" r="6" fill="#3BBBD2"/>
              <circle cx="98" cy="40" r="5" fill="#3BBBD2"/>
              <circle cx="64" cy="27" r="13" fill="#6FD3E8" opacity="0.22"/>
              <circle cx="64" cy="27" r="8.5" fill="#7FE0F0"/>
            </g>
          </svg>
          <span>island<span class="accent">r</span></span>
        </div>

        <template v-if="isAdmin">
          <div class="section-label">{{ t('nav.overview') }}</div>
          <router-link to="/dashboard" class="nav-item"><Icon name="dashboard" />{{ t('nav.dashboard') }}</router-link>

          <div class="section-label">{{ t('nav.admin') }}</div>
          <router-link to="/peers" class="nav-item"><Icon name="peers" />{{ t('nav.peers') }}</router-link>
          <router-link to="/networks" class="nav-item"><Icon name="networks" />{{ t('nav.networks') }}</router-link>
          <router-link to="/resources" class="nav-item"><Icon name="resources" />{{ t('nav.resources') }}</router-link>
          <router-link to="/port-groups" class="nav-item"><Icon name="portGroups" />{{ t('nav.port_groups') }}</router-link>
          <router-link to="/roles-list" class="nav-item"><Icon name="roles" />{{ t('nav.roles') }}</router-link>
          <router-link to="/acl" class="nav-item"><Icon name="acl" />{{ t('nav.roles_acl') }}</router-link>
          <router-link to="/users" class="nav-item"><Icon name="users" />{{ t('nav.users') }}</router-link>

          <div class="section-label">{{ t('nav.system') }}</div>
          <router-link to="/identity" class="nav-item"><Icon name="identity" />{{ t('nav.identity') }}</router-link>
          <router-link to="/firewall" class="nav-item"><Icon name="firewall" />{{ t('nav.firewall') }}</router-link>
          <router-link to="/audit" class="nav-item"><Icon name="audit" />{{ t('nav.audit') }}</router-link>

          <div class="sidebar-footer">
            <router-link to="/settings" class="nav-item"><Icon name="settings" />{{ t('nav.settings') }}</router-link>
          </div>
        </template>

        <template v-else>
          <div class="section-label">{{ t('nav.my_account') }}</div>
          <router-link to="/mein-zugang" class="nav-item"><Icon name="peers" />{{ t('nav.my_access') }}</router-link>
        </template>
      </aside>

      <header class="topbar">
        <div class="spacer"></div>
        <div class="topbar-lang-toggle">
          <button :class="['topbar-lang-btn', lang === 'en' && 'active']" @click="switchLang('en')">EN</button>
          <span>·</span>
          <button :class="['topbar-lang-btn', lang === 'de' && 'active']" @click="switchLang('de')">DE</button>
        </div>
        <button class="btn btn-ghost btn-sm theme-toggle" @click="toggleTheme" :title="theme === 'dark' ? 'Zum Hellmodus wechseln' : 'Zum Dunkelmodus wechseln'" aria-label="Theme wechseln">
          <Icon v-if="theme === 'dark'" name="sun" :size="16" />
          <Icon v-else name="moon" :size="16" />
        </button>
        <div v-if="me" class="topbar-user">
          <Avatar :user="{ id: me.userId, name: me.principal }" :size="28" />
          <span class="topbar-user-name">{{ me.principal }}</span>
          <span v-if="me.provider !== 'local'" class="badge badge-info" style="margin-left: var(--space-2)">
            {{ me.provider === 'microsoft' ? 'MS365' : 'Google' }}
          </span>
          <span v-if="isAdmin" class="badge badge-info" style="margin-left: var(--space-2)">Admin</span>
        </div>
        <button class="btn btn-ghost btn-sm" @click="logout">{{ t('nav.logout') }}</button>
      </header>

      <main class="main">
        <div v-if="isAdmin && !setupComplete && $route.name !== 'settings'" class="callout callout-warning">
          <div>
            <strong>Setup unvollständig.</strong>
            Der WireGuard-Server-Public-Key trägt noch den Platzhalter.
            <router-link to="/settings">Jetzt in den Einstellungen ergänzen.</router-link>
          </div>
        </div>
        <router-view :retention="retention" :self-service-peer-creation="selfServicePeerCreation" :google-ws-available="googleWsAvailable" @settings-changed="onSettingsChanged" />
      </main>
    </div>
  `,
});

createApp(App).use(router).mount("#app");
