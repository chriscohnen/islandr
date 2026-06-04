import { defineComponent } from "vue";

// Single placeholder component reused for every sidebar entry whose backend
// + UI is not built yet. Route definitions pass `title` and `note` as props.
// Once a real view is built, replace the route entry — no need to grep for
// per-page stubs.
export default defineComponent({
  name: "StubView",
  props: {
    title: { type: String, required: true },
    note: { type: String, default: "Diese Seite ist in der Roadmap, aber noch nicht implementiert." },
  },
  template: `
    <div class="page-header">
      <h1>{{ title }}</h1>
    </div>
    <div class="stub">
      <h2>Kommt noch</h2>
      <p>{{ note }}</p>
    </div>
  `,
});
