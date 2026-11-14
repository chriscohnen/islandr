import { defineComponent } from "vue";

// Tiny line graph for the dashboard tiles (dashboard-live-tiles). No axes, no
// numbers of its own — the tile beside it carries the figure; this only shows
// which way it has been going. Values are plain numbers, oldest first.
//
// `max` pins the top of the scale (e.g. 100 for a percentage); without it the
// graph scales to its own highest point, with a floor so a flat line of tiny
// numbers does not look like a mountain. Null/NaN points are skipped.
const W = 100;
const H = 28;
const PAD = 2;

export default defineComponent({
  name: "Sparkline",
  props: {
    values: { type: Array, default: () => [] },
    max: { type: Number, default: null },
    floor: { type: Number, default: 1 }, // smallest top of an auto scale
    label: { type: String, default: "" },
  },
  computed: {
    points() {
      const vs = this.values.map((v) => (typeof v === "number" && isFinite(v) ? v : null));
      const top = this.max != null ? this.max : Math.max(this.floor, ...vs.filter((v) => v != null));
      const n = vs.length;
      const pts = [];
      vs.forEach((v, i) => {
        if (v == null) return;
        const x = n <= 1 ? W : (i / (n - 1)) * W;
        const y = H - PAD - (Math.min(v, top) / top) * (H - 2 * PAD);
        pts.push([x, y]);
      });
      return pts;
    },
    line() {
      const p = this.points;
      if (p.length < 2) return "";
      return "M" + p.map(([x, y]) => x.toFixed(2) + "," + y.toFixed(2)).join(" L");
    },
    area() {
      const p = this.points;
      if (p.length < 2) return "";
      return this.line + " L" + p[p.length - 1][0].toFixed(2) + "," + H + " L" + p[0][0].toFixed(2) + "," + H + " Z";
    },
  },
  template: `
    <svg class="sparkline" :viewBox="'0 0 ${W} ${H}'" preserveAspectRatio="none" role="img" :aria-label="label">
      <title v-if="label">{{ label }}</title>
      <template v-if="line">
        <path class="sparkline-area" :d="area" />
        <path class="sparkline-line" :d="line" />
      </template>
      <line v-else x1="0" :x2="${W}" :y1="${H - 2}" :y2="${H - 2}" class="sparkline-empty" />
    </svg>
  `,
});
