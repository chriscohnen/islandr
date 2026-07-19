// Shared mixin for the Peer-Create / Edit / Reshow modal.
// Used by UsersView (create per user) and PeersView (create + edit, picks a
// user via dropdown).
//
// The host view must expose:
//   - props: { retention: String }       — drives banner copy
//   - data:  modalUserName (computed)    — display label inside the modal
//   - method onPeerCreated()             — called after a successful create so
//                                          the host can refresh its list
//   - method onPeerUpdated() (optional)  — called after a successful update
//
// The peer-create modal has three key-import modes (generate / public-only /
// both). The edit modal does NOT show the key import block — type and key are
// not editable, only name/IP/site-CIDRs. The public key is shown read-only for
// reference. To rotate a key, delete the peer and recreate.
import { t } from "/js/i18n.js";

export const peerModalMixin = {
  data() {
    return {
      modalMode: null, // null | "create" | "edit" | "secret"
      modalUserId: null,
      // Peer being edited (only set in "edit" mode) — used to compare against
      // the form values so we can decide whether to nudge the secret modal
      // afterwards (IP / CIDR change means the client needs a new .conf).
      editPeerId: null,
      editOriginalIp: null,
      editOriginalCidrs: null,
      editPeerPublicKey: null,
      editMtu: null,
      editKeepalive: null,
      editIncludeDns: true,

      newPeer: { name: "", assignedIp: "", assignedIpv6: "" },
      // Peer kind: "client" (single device) or "site" (gateway exposing a
      // downstream network — see PRD §7).
      peerType: "client",
      deviceType: "laptop",
      siteAllowedCidrs: "",
      // Key-import mode for the create form.
      importMode: "generate", // "generate" | "public-only" | "both"
      importPublicKey: "",
      importPrivateKey: "",
      // PSK (create form)
      generatePresharedKey: false,
      // PSK (edit form): null = no change, "rotate" = regenerate, "remove" = clear
      pskAction: null,
      editHasPsk: false,
      pskSyncing: false,
      pskSyncResult: null,  // null | "found" | "not_found" | "error"
      creatingPeer: false,
      peerError: null,
      secret: null, // { peer, privateKey, conf, qrPngBase64, presharedKey }
      secretIsReshow: false,
      // Per-peer .conf options, editable directly from the reveal/reshow dialog
      // (mirrors the edit modal's mtu/keepalive/includeDns fields) so an admin
      // can tweak and regenerate the .conf/QR without leaving this view.
      secretEditMtu: null,
      secretEditKeepalive: null,
      secretEditIncludeDns: true,
      secretApplying: false,
      secretApplyError: null,
      copyState: "idle",
    };
  },
  watch: {
    // Reseed the reveal-dialog option fields whenever a new secret is shown —
    // covers create, reshow, and the edit-flow's auto-reopened secret alike,
    // from one place instead of every call site that sets `secret`.
    secret(v) {
      if (!v || !v.peer) return;
      this.secretEditMtu = v.peer.mtu || null;
      this.secretEditKeepalive = v.peer.persistentKeepalive ?? null;
      this.secretEditIncludeDns = v.peer.includeDns !== false;
      this.secretApplyError = null;
    },
  },
  methods: {
    async openCreatePeer(userId) {
      this.modalMode = "create";
      this.modalUserId = userId;
      this.newPeer = { name: "", assignedIp: "", assignedIpv6: "" };
      this.peerType = "client";
      this.deviceType = "laptop";
      this.siteAllowedCidrs = "";
      this.importMode = "generate";
      this.importPublicKey = "";
      this.importPrivateKey = "";
      this.generatePresharedKey = false;
      this.peerError = null;
      this.secret = null;
      this.secretIsReshow = false;
      // Pre-fill assignedIp with the next free address in the configured
      // subnet. Best-effort: on error (e.g. subnet exhausted, settings broken)
      // leave the field empty so the admin can still type one in.
      try {
        const res = await fetch("/api/v1/peers/next-ip");
        if (res.ok) {
          const body = await res.json();
          // Guard: the user could have closed the modal again before the
          // request returned. Only fill if we're still on the same create.
          if (this.modalMode === "create" && this.modalUserId === userId) {
            this.newPeer.assignedIp = body.assignedIp || "";
          }
        }
      } catch {
        // ignore — admin types it manually
      }
      // Pre-fill assignedIpv6 with next free IPv6 address (best-effort, only if wgSubnet6 is set)
      try {
        const res6 = await fetch("/api/v1/peers/next-ip6");
        if (res6.ok) {
          const body6 = await res6.json();
          if (this.modalMode === "create" && this.modalUserId === userId) {
            this.newPeer.assignedIpv6 = body6.assignedIpv6 || "";
          }
        }
        // 412 = wgSubnet6 not configured → leave field empty, no error
      } catch {
        // ignore
      }
    },

    async submitCreatePeer() {
      if (!this.newPeer.name || !this.newPeer.assignedIp) return;
      if (this.peerType !== 'site' && !this.modalUserId) return;

      const payload = {
        name: this.newPeer.name,
        assignedIp: this.newPeer.assignedIp,
        assignedIpv6: this.newPeer.assignedIpv6 && this.newPeer.assignedIpv6.trim() ? this.newPeer.assignedIpv6.trim() : null,
        type: this.peerType,
        deviceType: this.peerType === "client" ? this.deviceType : null,
        generatePresharedKey: this.generatePresharedKey,
      };
      if (this.peerType === "site") {
        if (!this.siteAllowedCidrs.trim()) {
          this.peerError = t("peer.err_site_cidr");
          return;
        }
        payload.siteAllowedCidrs = this.siteAllowedCidrs.trim();
      }
      if (this.importMode === "public-only") {
        if (!this.importPublicKey.trim()) {
          this.peerError = t("peer.err_pubkey_missing");
          return;
        }
        payload.publicKey = this.importPublicKey.trim();
      } else if (this.importMode === "both") {
        if (!this.importPublicKey.trim() || !this.importPrivateKey.trim()) {
          this.peerError = t("peer.err_both_keys");
          return;
        }
        payload.publicKey = this.importPublicKey.trim();
        payload.privateKey = this.importPrivateKey.trim();
      }

      this.creatingPeer = true;
      this.peerError = null;
      try {
        const url = this.peerType === "site"
            ? "/api/v1/peers"
            : "/api/v1/users/" + this.modalUserId + "/peers";
        const res = await fetch(url, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        this.secret = await res.json();
        this.secretIsReshow = false;
        this.modalMode = "secret";
        // Tell the host view to reload its list. The view also gets an emit
        // for cases where it wants to do something extra.
        if (typeof this.onPeerCreated === "function") {
          this.onPeerCreated({ userId: this.modalUserId, peer: this.secret.peer });
        }
        this.$emit("peer-created", { userId: this.modalUserId, peer: this.secret.peer });
      } catch (e) {
        this.peerError = t("peers.error_create", { error: e.message });
      } finally {
        this.creatingPeer = false;
      }
    },

    openEditPeer(peer) {
      this.modalMode = "edit";
      this.modalUserId = peer.userId;
      this.editPeerId = peer.id;
      this.editOriginalIp = peer.assignedIp;
      this.editOriginalCidrs = peer.siteAllowedCidrs || null;
      this.editPeerPublicKey = peer.publicKey;
      this.newPeer = { name: peer.name, assignedIp: peer.assignedIp, assignedIpv6: peer.assignedIpv6 || "" };
      this.peerType = peer.type || "client";
      this.deviceType = peer.deviceType || "laptop";
      this.siteAllowedCidrs = peer.siteAllowedCidrs || "";
      this.editHasPsk = !!peer.hasPresharedKey;
      this.editMtu = peer.mtu || null;
      // `?? null` (not `|| null`): 0 is a valid "keepalive off for this peer" value.
      this.editKeepalive = peer.persistentKeepalive ?? null;
      this.editIncludeDns = peer.includeDns !== false;
      this.pskAction = null;
      this.pskSyncing = false;
      this.pskSyncResult = null;
      this.peerError = null;
      this.secret = null;
      this.secretIsReshow = false;
    },

    async submitEditPeer() {
      if (!this.newPeer.name || !this.newPeer.assignedIp || !this.editPeerId) return;

      const payload = {
        name: this.newPeer.name,
        assignedIp: this.newPeer.assignedIp,
        assignedIpv6: this.newPeer.assignedIpv6 && this.newPeer.assignedIpv6.trim() ? this.newPeer.assignedIpv6.trim() : null,
        deviceType: this.peerType === "client" ? (this.deviceType || null) : null,
        presharedKeyAction: this.pskAction || null,
        mtu: this.editMtu || null,
        // Keep an explicit 0 (= keepalive off); only an empty field means "defer to global".
        persistentKeepalive: (this.editKeepalive === "" || this.editKeepalive === null
          || this.editKeepalive === undefined || Number.isNaN(this.editKeepalive))
          ? null : this.editKeepalive,
        includeDns: this.editIncludeDns,
      };
      if (this.peerType === "site") {
        if (!this.siteAllowedCidrs.trim()) {
          this.peerError = t("peer.err_site_cidr");
          return;
        }
        payload.siteAllowedCidrs = this.siteAllowedCidrs.trim();
      }

      // Did anything wire-relevant change? If so we'll auto-open the secret
      // view after persist so the admin can hand the updated .conf to the user.
      const ipChanged = this.newPeer.assignedIp !== this.editOriginalIp;
      const normalisedCidrs = this.peerType === "site"
          ? this.siteAllowedCidrs.trim().replace(/\s*,\s*/g, ", ")
          : null;
      const cidrsChanged = (this.editOriginalCidrs || null) !== (normalisedCidrs || null);
      const pskChanged = this.pskAction === "rotate" || this.pskAction === "remove";
      const shouldReshow = ipChanged || cidrsChanged || pskChanged;

      this.creatingPeer = true;
      this.peerError = null;
      try {
        const res = await fetch("/api/v1/peers/" + this.editPeerId, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const updated = await res.json();

        if (typeof this.onPeerUpdated === "function") {
          this.onPeerUpdated({ peer: updated.peer });
        }
        this.$emit("peer-updated", { peer: updated.peer });

        if (shouldReshow) {
          // Nudge the secret modal so the admin sees the new .conf for the
          // client to re-import. Treated as a reshow (no "key only visible
          // now" warning — the keypair didn't change).
          this.secret = updated;
          this.secretIsReshow = true;
          this.modalMode = "secret";
        } else {
          this.closeModal();
        }
      } catch (e) {
        this.peerError = t("peers.error_delete", { error: e.message });
      } finally {
        this.creatingPeer = false;
      }
    },

    async openReshow(userId, peerId) {
      try {
        const res = await fetch("/api/v1/peers/" + peerId + "/conf");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.modalUserId = userId;
        this.secret = await res.json();
        this.secretIsReshow = true;
        this.modalMode = "secret";
      } catch (e) {
        alert(t("myaccess.error_conf", { error: e.message }));
      }
    },

    async syncPskFromWg() {
      this.pskSyncing = true;
      this.pskSyncResult = null;
      try {
        const res = await fetch(`/api/v1/peers/${this.editPeerId}/psk/sync-from-wg`, { method: 'POST' });
        if (res.ok) {
          const body = await res.json();
          this.pskSyncResult = body.imported ? 'found' : 'not_found';
          if (body.imported) this.editHasPsk = true;
        } else {
          this.pskSyncResult = 'error';
        }
      } catch {
        this.pskSyncResult = 'error';
      } finally {
        this.pskSyncing = false;
      }
    },

    closeModal() {
      this.modalMode = null;
      this.secret = null;
      this.peerError = null;
      this.copyState = "idle";
      this.editPeerId = null;
      this.editOriginalIp = null;
      this.editOriginalCidrs = null;
      this.editPeerPublicKey = null;
    },

    async copyConf() {
      if (!this.secret?.conf) return;
      try {
        await navigator.clipboard.writeText(this.secret.conf);
        this.copyState = "copied";
        setTimeout(() => (this.copyState = "idle"), 1500);
      } catch {
        this.copyState = "idle";
      }
    },

    downloadConf() {
      if (!this.secret?.conf) return;
      const blob = new Blob([this.secret.conf], { type: "text/plain" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = (this.secret.peer?.name || "peer").replace(/[^a-z0-9-_]/gi, "_") + ".conf";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    },

    // Applies the .conf-only options (MTU / keepalive / DNS) directly from the
    // reveal dialog and regenerates conf + QR in place — no need to leave this
    // view and go through the separate edit modal. Reuses the peer's current
    // name/IP/CIDRs/deviceType unchanged, so this never touches anything
    // enforcement-relevant (PeerService only re-applies wg state on an IP/CIDR/
    // PSK change, none of which happen here).
    async applySecretOptions() {
      if (!this.secret?.peer?.id) return;
      this.secretApplying = true;
      this.secretApplyError = null;
      try {
        const p = this.secret.peer;
        const payload = {
          name: p.name,
          assignedIp: p.assignedIp,
          assignedIpv6: p.assignedIpv6 || null,
          siteAllowedCidrs: p.siteAllowedCidrs || "",
          deviceType: p.deviceType || null,
          presharedKeyAction: null,
          mtu: this.secretEditMtu || null,
          // Keep an explicit 0 (= keepalive off); only an empty field means "defer to global".
          persistentKeepalive: (this.secretEditKeepalive === "" || this.secretEditKeepalive === null
            || this.secretEditKeepalive === undefined || Number.isNaN(this.secretEditKeepalive))
            ? null : this.secretEditKeepalive,
          includeDns: this.secretEditIncludeDns,
        };
        const res = await fetch("/api/v1/peers/" + p.id, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const updated = await res.json();
        this.secret = updated;
        this.secretIsReshow = true;
        if (typeof this.onPeerUpdated === "function") {
          this.onPeerUpdated({ peer: updated.peer });
        }
        this.$emit("peer-updated", { peer: updated.peer });
      } catch (e) {
        this.secretApplyError = t("peer.secret_apply_error", { error: e.message });
      } finally {
        this.secretApplying = false;
      }
    },
  },
};

// Standalone modal template fragment — included in each view's template via
// string interpolation. Both views drop it in at the same indent.
export const peerModalTemplate = `
  <div v-if="modalMode" class="modal-backdrop" @click.self="closeModal">

    <div v-if="modalMode === 'create'" class="modal">
      <div class="modal-header">
        <h2>{{ t('peer.create_title') }}</h2>
        <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
      </div>
      <form @submit.prevent="submitCreatePeer">
        <div class="modal-body">
          <div v-if="peerError" class="error-banner">{{ peerError }}</div>
          <div v-if="modalUserName && peerType !== 'site'" class="muted" style="margin-bottom: var(--space-3)">
            {{ t('peer.for') }} <strong style="color: var(--fg1); font-family: var(--font-sans)">{{ modalUserName }}</strong>
          </div>

          <fieldset class="key-mode" style="margin-bottom: var(--space-4)">
            <legend>{{ t('peer.type_label') }}</legend>
            <label class="key-mode-option" :style="users.length === 0 ? 'opacity:0.5; cursor:not-allowed' : ''">
              <input type="radio" v-model="peerType" value="client" :disabled="users.length === 0" />
              <div>
                <div class="key-mode-title">Client</div>
                <div class="key-mode-hint">{{ t('peer.type_client_hint') }}</div>
                <div v-if="users.length === 0" class="key-mode-hint" style="color: var(--accent)">{{ t('peer.no_users_hint') }}</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="peerType" value="site" />
              <div>
                <div class="key-mode-title">Site</div>
                <div class="key-mode-hint">{{ t('peer.type_site_hint') }}</div>
              </div>
            </label>
          </fieldset>

          <div v-if="peerType === 'client'" class="field" style="margin-bottom: var(--space-4)">
            <label>{{ t('peer.device_label') }}</label>
            <div class="peer-device-type-grid">
              <label v-for="opt in [
                {v:'laptop',  l:'Laptop'},
                {v:'desktop', l:'Desktop'},
                {v:'mobile',  l:'Smartphone'},
                {v:'tablet',  l:'Tablet'},
                {v:'server',  l:'Server'},
                {v:'other',   l:t('peer.device_other')},
              ]" :key="opt.v" class="peer-device-type-option" :class="{ active: deviceType === opt.v }">
                <input type="radio" :value="opt.v" v-model="deviceType" style="position:absolute;opacity:0;pointer-events:none" />
                <Icon :name="opt.v === 'other' ? 'peers' : opt.v" :size="18" />
                <span>{{ opt.l }}</span>
              </label>
            </div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="peerName">{{ peerType === 'site' ? t('peer.field_name_site') : t('peer.field_name') }}</label>
            <input id="peerName" class="input" v-model="newPeer.name" required :placeholder="peerType === 'site' ? t('peer.field_name_ph_site') : t('peer.field_name_ph')" />
            <div class="field-hint">{{ t('peer.field_name_hint') }}</div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="peerIp">{{ t('peer.field_ip') }}</label>
            <input id="peerIp" class="input mono" v-model="newPeer.assignedIp" required placeholder="10.8.0.20" />
            <div class="field-hint">{{ t('peer.field_ip_hint') }}</div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="peerIpv6">{{ t('peer.field_ipv6') }}</label>
            <input id="peerIpv6" class="input mono" v-model="newPeer.assignedIpv6" placeholder="fd11::3" />
            <div class="field-hint">{{ t('peer.field_ipv6_hint') }}</div>
          </div>

          <div v-if="peerType === 'site'" class="field" style="margin-bottom: var(--space-5)">
            <label for="siteCidrs">{{ t('peer.field_cidrs') }}</label>
            <textarea id="siteCidrs" class="textarea mono" rows="2" v-model="siteAllowedCidrs"
                      placeholder="192.168.50.0/24, 10.20.0.0/16" required></textarea>
            <div class="field-hint">{{ t('peer.field_cidrs_hint') }}</div>
          </div>

          <fieldset class="key-mode">
            <legend>{{ t('peer.key_section') }}</legend>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="generate" />
              <div>
                <div class="key-mode-title">{{ t('peer.key_generate') }}</div>
                <div class="key-mode-hint">{{ t('peer.key_generate_hint') }}</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="public-only" />
              <div>
                <div class="key-mode-title">{{ t('peer.key_import') }} <span class="badge badge-success" style="margin-left: 6px">{{ t('peer.key_import_badge') }}</span></div>
                <div class="key-mode-hint">{{ t('peer.key_import_hint') }}</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="both" />
              <div>
                <div class="key-mode-title">{{ t('peer.key_both') }}</div>
                <div class="key-mode-hint">{{ t('peer.key_both_hint') }} <code>wg pubkey</code>.</div>
              </div>
            </label>
          </fieldset>

          <div v-if="importMode !== 'generate'" class="field" style="margin-top: var(--space-4)">
            <label for="importPub">{{ t('peer.field_pubkey') }}</label>
            <input id="importPub" class="input mono" v-model="importPublicKey" required :placeholder="t('peer.field_pubkey_ph')" />
          </div>
          <div v-if="importMode === 'both'" class="field" style="margin-top: var(--space-3)">
            <label for="importPriv">Private Key</label>
            <input id="importPriv" class="input mono" v-model="importPrivateKey" required :placeholder="t('peer.field_pubkey_ph')" />
            <div class="field-hint">{{ t('peer.field_privkey_hint') }}</div>
          </div>

          <label class="key-mode-option" style="margin-top: var(--space-4); cursor: pointer">
            <input type="checkbox" v-model="generatePresharedKey" style="flex-shrink:0" />
            <div>
              <div class="key-mode-title">{{ t('peer.psk_enable') }} <span class="badge" style="margin-left: 6px; background: var(--surface-2); color: var(--fg2); font-size: var(--text-xs)">optional</span></div>
              <div class="key-mode-hint">{{ t('peer.psk_enable_hint') }}</div>
            </div>
          </label>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('peer.btn_cancel') }}</button>
          <button type="submit" class="btn btn-primary" :disabled="creatingPeer">
            {{ creatingPeer ? t('peer.btn_creating') : t('peer.btn_create') }}
          </button>
        </div>
      </form>
    </div>

    <div v-else-if="modalMode === 'edit'" class="modal">
      <div class="modal-header">
        <h2>{{ t('peer.edit_title') }}</h2>
        <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
      </div>
      <form @submit.prevent="submitEditPeer">
        <div class="modal-body">
          <div v-if="peerError" class="error-banner">{{ peerError }}</div>

          <div v-if="modalUserName && peerType !== 'site'" class="muted" style="margin-bottom: var(--space-3)">
            {{ t('peer.for') }} <strong style="color: var(--fg1); font-family: var(--font-sans)">{{ modalUserName }}</strong>
            <span class="tag" style="margin-left: var(--space-2)">Client</span>
          </div>

          <div v-if="peerType === 'client'" class="field" style="margin-bottom: var(--space-4)">
            <label>{{ t('peer.device_label') }}</label>
            <div class="peer-device-type-grid">
              <label v-for="opt in [
                {v:'laptop',  l:'Laptop'},
                {v:'desktop', l:'Desktop'},
                {v:'mobile',  l:'Smartphone'},
                {v:'tablet',  l:'Tablet'},
                {v:'server',  l:'Server'},
                {v:'other',   l:t('peer.device_other')},
              ]" :key="opt.v" class="peer-device-type-option" :class="{ active: deviceType === opt.v }">
                <input type="radio" :value="opt.v" v-model="deviceType" style="position:absolute;opacity:0;pointer-events:none" />
                <Icon :name="opt.v === 'other' ? 'peers' : opt.v" :size="18" />
                <span>{{ opt.l }}</span>
              </label>
            </div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="editName">{{ peerType === 'site' ? t('peer.field_name_site') : t('peer.field_name') }}</label>
            <input id="editName" class="input" v-model="newPeer.name" required />
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="editIp">{{ t('peer.field_ip') }}</label>
            <input id="editIp" class="input mono" v-model="newPeer.assignedIp" required />
            <div class="field-hint">{{ t('peer.field_ip_hint_edit') }}</div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="editIpv6">{{ t('peer.field_ipv6') }}</label>
            <input id="editIpv6" class="input mono" v-model="newPeer.assignedIpv6" placeholder="fd11::3" />
            <div class="field-hint">{{ t('peer.field_ipv6_hint_edit') }}</div>
          </div>

          <div v-if="peerType === 'site'" class="field" style="margin-bottom: var(--space-4)">
            <label for="editCidrs">{{ t('peer.field_cidrs') }}</label>
            <textarea id="editCidrs" class="textarea mono" rows="2" v-model="siteAllowedCidrs" required></textarea>
            <div class="field-hint">{{ t('peer.field_cidrs_hint') }}</div>
          </div>

          <div class="field">
            <label>{{ t('peer.field_pubkey') }} <span class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0">{{ t('peer.field_pubkey_ro') }}</span></label>
            <input class="input mono" :value="editPeerPublicKey" readonly />
            <div class="field-hint">{{ t('peer.field_pubkey_hint') }}</div>
          </div>

          <div class="field" style="margin-top: var(--space-4)">
            <label>Preshared Key</label>
            <div v-if="editHasPsk" style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap">
              <span class="badge badge-success" style="flex-shrink:0">{{ t('peer.psk_active') }}</span>
              <button type="button" class="btn btn-ghost btn-sm"
                      :class="{ 'btn-secondary': pskAction === 'rotate' }"
                      @click="pskAction = pskAction === 'rotate' ? null : 'rotate'">{{ t('peer.psk_regenerate') }}</button>
              <button type="button" class="btn btn-ghost btn-sm"
                      :class="{ 'btn-danger': pskAction === 'remove' }"
                      @click="pskAction = pskAction === 'remove' ? null : 'remove'">{{ t('peer.psk_remove') }}</button>
            </div>
            <div v-else style="display:flex; align-items:center; gap: var(--space-3); flex-wrap:wrap">
              <span class="muted" style="font-size: var(--text-sm)">{{ t('peer.psk_not_set') }}</span>
              <button type="button" class="btn btn-ghost btn-sm"
                      :class="{ 'btn-secondary': pskAction === 'rotate' }"
                      @click="pskAction = pskAction === 'rotate' ? null : 'rotate'">{{ t('peer.psk_regenerate') }}</button>
              <button type="button" class="btn btn-ghost btn-sm"
                      :disabled="pskSyncing"
                      @click="syncPskFromWg">{{ pskSyncing ? '…' : t('peer.psk_read_wg') }}</button>
              <span v-if="pskSyncResult === 'found'" class="badge badge-success">{{ t('peer.psk_imported') }}</span>
              <span v-if="pskSyncResult === 'not_found'" class="muted" style="font-size:var(--text-sm)">{{ t('peer.psk_none_in_wg') }}</span>
              <span v-if="pskSyncResult === 'error'" class="badge badge-danger">{{ t('peer.psk_error') }}</span>
            </div>
            <div v-if="pskAction" class="callout callout-warning" style="margin-top: var(--space-2)">
              <div v-if="pskAction === 'rotate'">{{ t('peer.psk_rotate_warn') }}</div>
              <div v-else>{{ t('peer.psk_remove_warn') }}</div>
            </div>
            <div class="field-hint">{{ t('peer.psk_hint') }}</div>
          </div>

          <div class="field" style="margin-top: var(--space-4)">
            <label>{{ t('peer.field_mtu') }}</label>
            <input type="number" class="input mono" v-model.number="editMtu"
                   min="576" max="65535" :placeholder="t('peer.field_mtu_ph')"
                   style="width: 200px" />
            <div class="field-hint">{{ t('peer.field_mtu_hint') }}</div>
          </div>

          <div class="field" style="margin-top: var(--space-4)">
            <label>{{ t('peer.field_keepalive') }}</label>
            <input type="number" class="input mono" v-model.number="editKeepalive"
                   min="0" max="65535" :placeholder="t('peer.field_keepalive_ph')"
                   style="width: 200px" />
            <div class="field-hint">{{ t('peer.field_keepalive_hint') }}</div>
          </div>

          <div class="field" style="margin-top: var(--space-4)">
            <label style="display:inline-flex; align-items:center; gap:var(--space-2); cursor:pointer; user-select:none; font-family:var(--font-sans); font-size:var(--text-sm); color:var(--fg1); font-weight:500; text-transform:none; letter-spacing:0">
              <input type="checkbox" v-model="editIncludeDns" style="width:16px; height:16px; accent-color:var(--accent); margin:0" />
              <span>{{ t('peer.field_include_dns') }}</span>
            </label>
            <div class="field-hint">{{ t('peer.field_include_dns_hint') }}</div>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('peer.btn_cancel') }}</button>
          <button type="submit" class="btn btn-primary" :disabled="creatingPeer">
            {{ creatingPeer ? t('peer.btn_saving') : t('peer.btn_save') }}
          </button>
        </div>
      </form>
    </div>

    <div v-else-if="modalMode === 'secret' && secret" class="modal modal-xl">
      <div class="modal-header">
        <h2>{{ secretIsReshow ? t('peer.secret_title_re') : t('peer.secret_title_new') }} — {{ secret.peer?.name }}</h2>
        <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
      </div>
      <div class="modal-body">
        <div v-if="!secretIsReshow && secret.privateKey && retention === 'never'" class="callout callout-warning">
          <div>
            <strong>{{ t('peer.warn_once') }}</strong>
            {{ t('peer.warn_once_desc') }}
          </div>
        </div>
        <div v-else-if="!secretIsReshow && secret.privateKey && retention === 'plaintext'" class="callout callout-info">
          <div>
            {{ t('peer.warn_plaintext') }}
          </div>
        </div>
        <div v-else-if="!secretIsReshow && secret.privateKey && retention === 'encrypted'" class="callout callout-info">
          <div>
            {{ t('peer.warn_encrypted') }}
          </div>
        </div>
        <div v-else-if="!secretIsReshow && !secret.privateKey" class="callout callout-info">
          <div>
            {{ t('peer.secret_imported_a') }}<code>PrivateKey</code>{{ t('peer.secret_imported_b') }}
          </div>
        </div>
        <div v-else-if="secretIsReshow && secret.privateKey" class="callout callout-info">
          <div>
            {{ t('peer.warn_reshow') }}
          </div>
        </div>
        <div v-else class="callout callout-warning">
          <div>
            <strong>{{ t('peer.warn_no_key') }}</strong>
            {{ t('peer.secret_no_key_a') }}<strong>never</strong>{{ t('peer.secret_no_key_b') }}<code>PrivateKey</code>{{ t('peer.secret_no_key_c') }}
          </div>
        </div>

        <div class="secret-block" :class="{ 'secret-block-no-qr': !secret.qrPngBase64 }">
          <div v-if="secret.qrPngBase64" class="qr">
            <img :src="secret.qrPngBase64" :alt="t('peer.qr_alt')" />
          </div>
          <div>
            <div class="field" style="margin-bottom: var(--space-3)">
              <label>{{ t('peer.field_ip_key') }}</label>
              <div class="mono" style="font-size: var(--text-sm)">{{ secret.peer?.assignedIp }}</div>
              <div class="mono" style="font-size: var(--text-xs); color: var(--fg3); word-break: break-all">
                {{ secret.peer?.publicKey }}
              </div>
            </div>
            <pre class="conf-block">{{ secret.conf }}</pre>

            <div style="margin-top: var(--space-4); padding-top: var(--space-4); border-top: 1px solid var(--border)">
              <div style="display: flex; flex-wrap: wrap; gap: var(--space-4); align-items: flex-end">
                <div class="field" style="margin: 0">
                  <label>{{ t('peer.field_mtu') }}</label>
                  <input type="number" class="input mono" v-model.number="secretEditMtu"
                         min="576" max="65535" :placeholder="t('peer.field_mtu_ph')"
                         style="width: 160px" />
                </div>
                <div class="field" style="margin: 0">
                  <label>{{ t('peer.field_keepalive') }}</label>
                  <input type="number" class="input mono" v-model.number="secretEditKeepalive"
                         min="0" max="65535" :placeholder="t('peer.field_keepalive_ph')"
                         style="width: 160px" />
                </div>
                <label style="display:inline-flex; align-items:center; gap:var(--space-2); cursor:pointer; user-select:none; font-family:var(--font-sans); font-size:var(--text-sm); color:var(--fg1); font-weight:500; text-transform:none; letter-spacing:0; padding-bottom: 9px">
                  <input type="checkbox" v-model="secretEditIncludeDns" style="width:16px; height:16px; accent-color:var(--accent); margin:0" />
                  <span>{{ t('peer.field_include_dns') }}</span>
                </label>
                <button type="button" class="btn btn-secondary btn-sm" :disabled="secretApplying" @click="applySecretOptions">
                  {{ secretApplying ? t('peer.secret_applying') : t('peer.secret_apply_btn') }}
                </button>
              </div>
              <div class="field-hint" style="margin-top: var(--space-2)">{{ t('peer.secret_options_hint') }}</div>
              <div v-if="secretApplyError" class="callout callout-warning" style="margin-top: var(--space-2)">{{ secretApplyError }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-ghost" @click="copyConf">
          {{ copyState === "copied" ? t('peer.btn_copied') : t('peer.btn_copy') }}
        </button>
        <button class="btn btn-secondary" @click="downloadConf">{{ t('peer.btn_download') }}</button>
        <button class="btn btn-primary" @click="closeModal">{{ t('peer.btn_done') }}</button>
      </div>
    </div>
  </div>
`;
