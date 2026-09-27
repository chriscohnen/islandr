import { defineComponent, reactive } from "vue";
import { t } from "/js/i18n.js";

/**
 * The app's own confirmation, in place of the browser's `confirm()`.
 *
 * <p>A native dialog is the wrong instrument here for three reasons, and only
 * the first is cosmetic: it ignores the theme and renders an OS grey box over
 * a dark console; it prefixes every question with the hostname, which turns
 * "Delete this peer?" into something that reads like a browser warning; and it
 * blocks the main thread, so nothing behind it can finish rendering while the
 * question is open.
 *
 * <p>It is a single instance mounted in the app shell rather than a component
 * each view embeds. Twenty-one call sites ask questions like this, and giving
 * each of them a dialog in its own template would be twenty-one chances for
 * them to drift apart.
 *
 * <p>Usage mirrors what it replaces, so a call site changes by one word:
 *
 * <pre>
 *   if (!confirm(t("peers.confirm_delete"))) return;          // before
 *   if (!await confirmDialog(t("peers.confirm_delete"))) return;  // after
 * </pre>
 */
const state = reactive({
  open: false,
  message: "",
  title: "",
  confirmLabel: "",
  danger: true,
  resolve: null,
});

/**
 * Asks the question and resolves to what the user chose.
 *
 * @param message  the question, already translated
 * @param opts     {danger} false for a question that destroys nothing (the
 *                 confirm button is then accent-coloured rather than red),
 *                 {title} to override the heading, {confirmLabel} to name the
 *                 action instead of a bare "OK"
 * @returns {Promise<boolean>}
 */
export function confirmDialog(message, opts = {}) {
  // A second question while one is open would strand the first promise
  // unresolved, and its caller would wait forever. Answer it as cancelled.
  if (state.resolve) state.resolve(false);

  state.message = message;
  state.title = opts.title || t("confirm.title");
  state.confirmLabel = opts.confirmLabel || t("confirm.ok");
  state.danger = opts.danger !== false;
  state.open = true;
  return new Promise((resolve) => {
    state.resolve = resolve;
  });
}

function settle(answer) {
  const resolve = state.resolve;
  state.open = false;
  state.resolve = null;
  if (resolve) resolve(answer);
}

export const ConfirmDialog = defineComponent({
  name: "ConfirmDialog",
  data() {
    return { s: state };
  },
  methods: {
    t,
    cancel() {
      settle(false);
    },
    ok() {
      settle(true);
    },
  },
  // Cancel is the autofocused button, not the confirm. The dialog exists
  // because the action is hard to undo, so a stray Return must not carry it
  // out — the same reasoning as the ACL matrix refusing to auto-save.
  template: `
    <div v-if="s.open" class="modal-backdrop" @click.self="cancel" @keydown.esc="cancel">
      <div class="modal modal-sm" role="alertdialog" aria-modal="true"
           :aria-label="s.title">
        <div class="modal-header">
          <h2>{{ s.title }}</h2>
        </div>
        <div class="modal-body">
          <p style="margin: 0; color: var(--fg1)">{{ s.message }}</p>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-secondary" ref="cancelBtn" @click="cancel">
            {{ t('confirm.cancel') }}
          </button>
          <button type="button" :class="['btn', s.danger ? 'btn-danger' : 'btn-primary']" @click="ok">
            {{ s.confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  `,
  watch: {
    "s.open"(open) {
      if (!open) return;
      this.$nextTick(() => {
        const b = this.$refs.cancelBtn;
        if (b && b.focus) b.focus();
      });
    },
  },
});

export default ConfirmDialog;
