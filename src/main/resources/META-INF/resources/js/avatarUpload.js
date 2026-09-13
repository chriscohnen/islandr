import { reactive } from "vue";
import { t } from "/js/i18n.js";

// Bumped per user id after a successful upload or removal, and read by
// Avatar.js as a cache-busting query — so every avatar of that user on the
// page updates at once, not just the one that was clicked.
export const avatarVersion = reactive({});

const MAX_EDGE = 256;          // what we store; the server refuses over 512
const ACCEPT = "image/png,image/jpeg,image/webp,image/gif";

/**
 * Opens a file picker, downscales the chosen image in the browser and PUTs the
 * result. The downscale is client-side on purpose: the hub runs as a GraalVM
 * native image, and decoding images server-side would pull AWT into it for one
 * cosmetic feature. The server does not take the result on trust — it reads the
 * file header and refuses anything too large (see AvatarImage).
 *
 * @returns {Promise<{ok: boolean, error?: string}>}
 */
export async function chooseAndUploadAvatar(userId) {
  const file = await pickFile();
  if (!file) return { ok: false };
  try {
    const blob = await downscale(file);
    const res = await fetch("/api/v1/users/" + userId + "/avatar", {
      method: "PUT",
      headers: { "content-type": blob.type },
      body: blob,
    });
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      return { ok: false, error: body ? body.slice(0, 200) : "HTTP " + res.status };
    }
    avatarVersion[userId] = Date.now();
    return { ok: true };
  } catch (e) {
    return { ok: false, error: t("avatar.error_read", { error: e.message }) };
  }
}

export async function removeAvatar(userId) {
  const res = await fetch("/api/v1/users/" + userId + "/avatar", { method: "DELETE" });
  if (!res.ok) return { ok: false, error: "HTTP " + res.status };
  avatarVersion[userId] = Date.now();
  return { ok: true };
}

function pickFile() {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ACCEPT;
    input.style.display = "none";
    input.addEventListener("change", () => {
      const f = input.files && input.files[0] ? input.files[0] : null;
      input.remove();
      resolve(f);
    });
    // A cancelled picker fires no event in every browser, so the element is
    // left to be garbage-collected rather than waited on.
    document.body.appendChild(input);
    input.click();
  });
}

/**
 * Square centre-crop to at most 256×256, encoded as JPEG. Transparency is
 * flattened onto the surface colour rather than kept: an avatar is always
 * drawn as a filled circle, and JPEG keeps the stored bytes small enough that
 * the size ceiling is never the thing an operator runs into.
 */
function downscale(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      const edge = Math.min(img.naturalWidth, img.naturalHeight);
      if (!edge) { reject(new Error("image has no dimensions")); return; }
      const target = Math.min(MAX_EDGE, edge);
      const canvas = document.createElement("canvas");
      canvas.width = target;
      canvas.height = target;
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, target, target);
      ctx.drawImage(img,
        (img.naturalWidth - edge) / 2, (img.naturalHeight - edge) / 2, edge, edge,
        0, 0, target, target);
      canvas.toBlob((b) => {
        if (b) resolve(b); else reject(new Error("could not encode image"));
      }, "image/jpeg", 0.85);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("could not read that file as an image"));
    };
    img.src = url;
  });
}
