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

      newPeer: { name: "", assignedIp: "" },
      // Peer kind: "client" (single device) or "site" (gateway exposing a
      // downstream network — see PRD §7).
      peerType: "client",
      deviceType: "laptop",
      siteAllowedCidrs: "",
      // Key-import mode for the create form.
      importMode: "generate", // "generate" | "public-only" | "both"
      importPublicKey: "",
      importPrivateKey: "",
      creatingPeer: false,
      peerError: null,
      secret: null, // { peer, privateKey, conf, qrPngBase64 }
      secretIsReshow: false,
      copyState: "idle",
    };
  },
  methods: {
    async openCreatePeer(userId) {
      this.modalMode = "create";
      this.modalUserId = userId;
      this.newPeer = { name: "", assignedIp: "" };
      this.peerType = "client";
      this.deviceType = "laptop";
      this.siteAllowedCidrs = "";
      this.importMode = "generate";
      this.importPublicKey = "";
      this.importPrivateKey = "";
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
    },

    async submitCreatePeer() {
      if (!this.newPeer.name || !this.newPeer.assignedIp) return;
      if (this.peerType !== 'site' && !this.modalUserId) return;

      const payload = {
        name: this.newPeer.name,
        assignedIp: this.newPeer.assignedIp,
        type: this.peerType,
        deviceType: this.peerType === "client" ? this.deviceType : null,
      };
      if (this.peerType === "site") {
        if (!this.siteAllowedCidrs.trim()) {
          this.peerError = "Bei Site-Peer muss mindestens ein CIDR angegeben werden.";
          return;
        }
        payload.siteAllowedCidrs = this.siteAllowedCidrs.trim();
      }
      if (this.importMode === "public-only") {
        if (!this.importPublicKey.trim()) {
          this.peerError = "Public Key fehlt.";
          return;
        }
        payload.publicKey = this.importPublicKey.trim();
      } else if (this.importMode === "both") {
        if (!this.importPublicKey.trim() || !this.importPrivateKey.trim()) {
          this.peerError = "Public Key und Private Key müssen beide angegeben werden.";
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
        this.peerError = t("peers.error_load", { error: e.message });
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
      this.newPeer = { name: peer.name, assignedIp: peer.assignedIp };
      this.peerType = peer.type || "client";
      this.deviceType = peer.deviceType || "laptop";
      this.siteAllowedCidrs = peer.siteAllowedCidrs || "";
      this.peerError = null;
      this.secret = null;
      this.secretIsReshow = false;
    },

    async submitEditPeer() {
      if (!this.newPeer.name || !this.newPeer.assignedIp || !this.editPeerId) return;

      const payload = {
        name: this.newPeer.name,
        assignedIp: this.newPeer.assignedIp,
        deviceType: this.peerType === "client" ? (this.deviceType || null) : null,
      };
      if (this.peerType === "site") {
        if (!this.siteAllowedCidrs.trim()) {
          this.peerError = "Bei Site-Peer muss mindestens ein CIDR angegeben werden.";
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
      const shouldReshow = ipChanged || cidrsChanged;

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
            Für: <strong style="color: var(--fg1); font-family: var(--font-sans)">{{ modalUserName }}</strong>
          </div>

          <fieldset class="key-mode" style="margin-bottom: var(--space-4)">
            <legend>{{ t('peer.type_label') }}</legend>
            <label class="key-mode-option">
              <input type="radio" v-model="peerType" value="client" />
              <div>
                <div class="key-mode-title">Client</div>
                <div class="key-mode-hint">Einzelnes Gerät eines Benutzers (Laptop, Smartphone). Routet nur Traffic für sich selbst.</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="peerType" value="site" />
              <div>
                <div class="key-mode-title">Site</div>
                <div class="key-mode-hint">Gateway in ein dahinter liegendes Netz (Filiale, Heim-Lab). Macht weitere CIDRs hinter sich erreichbar.</div>
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
                {v:'other',   l:'Sonstiges'},
              ]" :key="opt.v" class="peer-device-type-option" :class="{ active: deviceType === opt.v }">
                <input type="radio" :value="opt.v" v-model="deviceType" style="position:absolute;opacity:0;pointer-events:none" />
                <Icon :name="opt.v === 'other' ? 'peers' : opt.v" :size="18" />
                <span>{{ opt.l }}</span>
              </label>
            </div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="peerName">{{ peerType === 'site' ? 'Standort-/Gateway-Name' : t('peer.field_name') }}</label>
            <input id="peerName" class="input" v-model="newPeer.name" required :placeholder="peerType === 'site' ? 'z.B. hamburg-office' : 'z.B. macbook'" />
            <div class="field-hint">Frei wählbar. Wird im Audit-Log angezeigt.</div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="peerIp">{{ t('peer.field_ip') }}</label>
            <input id="peerIp" class="input mono" v-model="newPeer.assignedIp" required placeholder="10.8.0.20" />
            <div class="field-hint">Muss im konfigurierten WireGuard-Subnetz liegen.</div>
          </div>

          <div v-if="peerType === 'site'" class="field" style="margin-bottom: var(--space-5)">
            <label for="siteCidrs">{{ t('peer.field_cidrs') }}</label>
            <textarea id="siteCidrs" class="textarea mono" rows="2" v-model="siteAllowedCidrs"
                      placeholder="192.168.50.0/24, 10.20.0.0/16" required></textarea>
            <div class="field-hint">Komma-getrennte IPv4-CIDRs. Dürfen sich nicht mit dem WG-Subnetz oder mit anderen Site-Peers überschneiden.</div>
          </div>

          <fieldset class="key-mode">
            <legend>{{ t('peer.key_section') }}</legend>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="generate" />
              <div>
                <div class="key-mode-title">{{ t('peer.key_generate') }}</div>
                <div class="key-mode-hint">Standard. Schnellste Variante. Der private Schlüssel verlässt den Server beim Anlegen.</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="public-only" />
              <div>
                <div class="key-mode-title">{{ t('peer.key_import') }} <span class="badge badge-success" style="margin-left: 6px">sicherste Variante</span></div>
                <div class="key-mode-hint">Der Client erzeugt den Keypair lokal — der private Schlüssel erreicht den Server nie. Der User trägt seinen privaten Schlüssel selbst in die .conf ein.</div>
              </div>
            </label>
            <label class="key-mode-option">
              <input type="radio" v-model="importMode" value="both" />
              <div>
                <div class="key-mode-title">Public + Private Key importieren</div>
                <div class="key-mode-hint">Für die Migration von PiVPN o.ä. Der Server validiert das Schlüsselpaar via <code>wg pubkey</code>.</div>
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
            <div class="field-hint">Wird je nach Retention-Modus gespeichert oder nur zur QR-/.conf-Generierung verwendet.</div>
          </div>
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
            Für: <strong style="color: var(--fg1); font-family: var(--font-sans)">{{ modalUserName }}</strong>
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
                {v:'other',   l:'Sonstiges'},
              ]" :key="opt.v" class="peer-device-type-option" :class="{ active: deviceType === opt.v }">
                <input type="radio" :value="opt.v" v-model="deviceType" style="position:absolute;opacity:0;pointer-events:none" />
                <Icon :name="opt.v === 'other' ? 'peers' : opt.v" :size="18" />
                <span>{{ opt.l }}</span>
              </label>
            </div>
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="editName">{{ peerType === 'site' ? 'Standort-/Gateway-Name' : t('peer.field_name') }}</label>
            <input id="editName" class="input" v-model="newPeer.name" required />
          </div>

          <div class="field" style="margin-bottom: var(--space-4)">
            <label for="editIp">{{ t('peer.field_ip') }}</label>
            <input id="editIp" class="input mono" v-model="newPeer.assignedIp" required />
            <div class="field-hint">Bei Änderung muss der Client die .conf neu importieren.</div>
          </div>

          <div v-if="peerType === 'site'" class="field" style="margin-bottom: var(--space-4)">
            <label for="editCidrs">{{ t('peer.field_cidrs') }}</label>
            <textarea id="editCidrs" class="textarea mono" rows="2" v-model="siteAllowedCidrs" required></textarea>
            <div class="field-hint">Komma-getrennte IPv4-CIDRs. Dürfen sich nicht mit dem WG-Subnetz oder mit anderen Site-Peers überschneiden.</div>
          </div>

          <div class="field">
            <label>{{ t('peer.field_pubkey') }} <span class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0">(nicht editierbar)</span></label>
            <input class="input mono" :value="editPeerPublicKey" readonly />
            <div class="field-hint">Schlüssel-Rotation: alten Peer löschen und neu anlegen.</div>
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
        <div v-if="!secretIsReshow && secret.privateKey && retention !== 'plaintext'" class="callout callout-warning">
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
        <div v-else-if="!secretIsReshow && !secret.privateKey" class="callout callout-info">
          <div>
            Peer angelegt mit importiertem Public Key. Der private Schlüssel ist nur dem Client bekannt — die .conf unten enthält keine <code>PrivateKey</code>-Zeile.
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
            Beim Anlegen war Retention-Modus <strong>never</strong> aktiv (oder es wurde nur der Public Key importiert). Die .conf enthält alle Server-Parameter, aber keinen <code>PrivateKey</code> — entweder manuell ergänzen oder neuen Peer anlegen.
          </div>
        </div>

        <div class="secret-block" :class="{ 'secret-block-no-qr': !secret.qrPngBase64 }">
          <div v-if="secret.qrPngBase64" class="qr">
            <img :src="secret.qrPngBase64" alt="WireGuard QR-Code" />
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
