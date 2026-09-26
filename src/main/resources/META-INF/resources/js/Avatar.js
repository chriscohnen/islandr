import { defineComponent } from "vue";
import { t } from "/js/i18n.js";
import { avatarVersion, chooseAndUploadAvatar, removeAvatar } from "/js/avatarUpload.js";
import { onEscape } from "/js/keyboard.js";
import { Icon } from "/js/Icons.js";

// Avatar with two-stage fallback:
//   1. <img> at /api/v1/users/{id}/avatar (covers MS Graph / Google / Gravatar cache)
//   2. Deterministic initials on cool-color background when the image 404s
//
// The color palette is the "cool set" from CLAUDE.md — same user always gets
// the same swatch, never random brights. We pick the swatch with a tiny string
// hash on the user's name.
export const COOL_PALETTE = [
  "#2A6F7A", "#3A7691", "#3F8AA5", "#4A9DB8", "#3F7E8A",
  "#5B8FA6", "#34728C", "#487A95", "#2D6273", "#3B8095",
];

function pickColor(name) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return COOL_PALETTE[Math.abs(hash) % COOL_PALETTE.length];
}

function initials(name) {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export default defineComponent({
  name: "Avatar",
  components: { Icon },
  props: {
    user: { type: Object, required: true },  // { id, name, ... }
    size: { type: Number, default: 32 },
    // Turns the avatar into a control that uploads a picture (issue #85).
    // Callers pass this only where the viewer is allowed to change it: an
    // admin on any user, a user on themselves.
    editable: { type: Boolean, default: false },
  },
  emits: ["changed", "error"],
  data() {
    return { imgFailed: false, busy: false, menuOpen: false };
  },
  computed: {
    src() {
      if (!this.user.id) return null;
      // avatarVersion is bumped after an upload, so every avatar of that user
      // on the page reloads — not only the one that was clicked.
      const v = avatarVersion[this.user.id];
      return "/api/v1/users/" + this.user.id + "/avatar" + (v ? "?v=" + v : "");
    },
    hasImage() {
      return !!this.src && !this.imgFailed;
    },
    initials() {
      return initials(this.user.name || this.user.email || "");
    },
    bgColor() {
      return pickColor(this.user.name || this.user.email || "?");
    },
  },
  watch: {
    "user.id"() { this.imgFailed = false; },
    src() { this.imgFailed = false; },
  },
  beforeUnmount() {
    if (this._offMenuEscape) this._offMenuEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    // The pencil is the only entry point (issue: removing a picture used to
    // sit as its own permanent icon next to the avatar, visible on every
    // touch device since there is no hover there to reveal it on). With a
    // picture already set there are two things the pencil could mean, so it
    // opens a two-item menu instead of assuming "change"; with no picture
    // there is only one thing to do, so it skips the menu entirely.
    onEditClick() {
      if (this.hasImage) this.toggleMenu();
      else this.upload();
    },
    toggleMenu() {
      if (this.menuOpen) this.closeMenu();
      else this.openMenu();
    },
    openMenu() {
      this.menuOpen = true;
      this._offMenuEscape = onEscape(() => this.closeMenu());
    },
    closeMenu() {
      this.menuOpen = false;
      if (this._offMenuEscape) { this._offMenuEscape(); this._offMenuEscape = null; }
    },
    // Focus leaving the whole control (not just moving between its own
    // buttons) closes the menu — relatedTarget is null for a click landing
    // outside any focusable element, which $el.contains would miss.
    onFocusOut(evt) {
      if (this.$el.contains(evt.relatedTarget)) return;
      this.closeMenu();
    },
    async upload() {
      if (this.busy || !this.user.id) return;
      this.closeMenu();
      this.busy = true;
      try {
        const r = await chooseAndUploadAvatar(this.user.id);
        if (r.ok) this.$emit("changed");
        else if (r.error) this.$emit("error", r.error);
      } finally {
        this.busy = false;
      }
    },
    async remove() {
      if (this.busy || !this.user.id) return;
      this.closeMenu();
      if (!confirm(t("avatar.confirm_remove"))) return;
      this.busy = true;
      try {
        const r = await removeAvatar(this.user.id);
        if (r.ok) this.$emit("changed");
        else if (r.error) this.$emit("error", r.error);
      } finally {
        this.busy = false;
      }
    },
  },
  template: `
    <span v-if="!editable" class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: (size * 0.4) + 'px' }">
      <img v-if="hasImage" :src="src" :alt="user.name || ''" @error="imgFailed = true" />
      <span v-else class="avatar-initials" :style="{ backgroundColor: bgColor }">{{ initials }}</span>
    </span>
    <!-- Editable: the pencil badge fades in on hover or keyboard focus, and
         stays visible where there is no hover at all (app.css) — quiet
         without becoming hover-only. It is the only permanent control:
         removing a picture is one tap behind it, in the menu, not a second
         icon standing next to the avatar on every touch device. -->
    <span v-else class="avatar-edit-wrap" @focusout="onFocusOut">
      <button type="button" class="avatar-edit-btn" :disabled="busy" @click="onEditClick"
              :aria-haspopup="hasImage ? 'true' : null" :aria-expanded="hasImage ? String(menuOpen) : null"
              :title="t('avatar.change')" :aria-label="t('avatar.change')">
        <span class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: (size * 0.4) + 'px' }">
          <img v-if="hasImage" :src="src" :alt="user.name || ''" @error="imgFailed = true" />
          <span v-else class="avatar-initials" :style="{ backgroundColor: bgColor }">{{ initials }}</span>
        </span>
        <span class="avatar-edit-badge" aria-hidden="true"><Icon name="edit" :size="8" /></span>
      </button>
      <div v-if="menuOpen" class="avatar-edit-menu" role="menu">
        <button type="button" role="menuitem" class="avatar-edit-menu-item" :disabled="busy" @click="upload">
          {{ t('avatar.change') }}
        </button>
        <button type="button" role="menuitem" class="avatar-edit-menu-item avatar-edit-menu-item-danger" :disabled="busy" @click="remove">
          {{ t('avatar.remove') }}
        </button>
      </div>
    </span>
  `,
});
