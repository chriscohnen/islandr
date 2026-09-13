import { defineComponent } from "vue";
import { t } from "/js/i18n.js";
import { avatarVersion, chooseAndUploadAvatar, removeAvatar } from "/js/avatarUpload.js";

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
    return { imgFailed: false, busy: false };
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
  methods: {
    t(key, vars) { return t(key, vars); },
    async upload() {
      if (this.busy || !this.user.id) return;
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
    <!-- Editable: the badge is always visible, so the action is reachable by
         touch and not hidden behind a hover. -->
    <span v-else class="avatar-edit-wrap">
      <button type="button" class="avatar-edit-btn" :disabled="busy" @click="upload"
              :title="t('avatar.change')" :aria-label="t('avatar.change')">
        <span class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: (size * 0.4) + 'px' }">
          <img v-if="hasImage" :src="src" :alt="user.name || ''" @error="imgFailed = true" />
          <span v-else class="avatar-initials" :style="{ backgroundColor: bgColor }">{{ initials }}</span>
        </span>
        <span class="avatar-edit-badge" aria-hidden="true">✎</span>
      </button>
      <button v-if="hasImage" type="button" class="avatar-remove-btn" :disabled="busy" @click="remove"
              :title="t('avatar.remove')" :aria-label="t('avatar.remove')">✕</button>
    </span>
  `,
});
