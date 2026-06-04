import { defineComponent } from "vue";

// Avatar with two-stage fallback:
//   1. <img> at /api/v1/users/{id}/avatar (covers MS Graph / Google / Gravatar cache)
//   2. Deterministic initials on cool-color background when the image 404s
//
// The color palette is the "cool set" from CLAUDE.md — same user always gets
// the same swatch, never random brights. We pick the swatch with a tiny string
// hash on the user's name.
const COOL_PALETTE = [
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
  },
  data() {
    return { imgFailed: false };
  },
  computed: {
    src() {
      return this.user.id ? "/api/v1/users/" + this.user.id + "/avatar" : null;
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
  },
  template: `
    <span class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: (size * 0.4) + 'px' }">
      <img v-if="src && !imgFailed" :src="src" :alt="user.name || ''" @error="imgFailed = true" />
      <span v-else class="avatar-initials" :style="{ backgroundColor: bgColor }">{{ initials }}</span>
    </span>
  `,
});
