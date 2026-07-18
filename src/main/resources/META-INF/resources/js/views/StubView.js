import { defineComponent } from "vue";
import { t } from "/js/i18n.js";

// Single placeholder component reused for every sidebar entry whose backend
// + UI is not built yet. Route definitions pass `title` and `note` as props.
// Once a real view is built, replace the route entry — no need to grep for
// per-page stubs.
export default defineComponent({
  name: "StubView",
  props: {
    title: { type: String, required: true },
    note: { type: String, default: null },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
  },
  template: `
    <div class="page-header">
      <h1>{{ title }}</h1>
    </div>
    <div class="stub">
      <h2>{{ t('stub.coming') }}</h2>
      <p>{{ note || t('stub.note') }}</p>
    </div>
  `,
});
