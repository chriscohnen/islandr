// Night scene behind the sign-in card (dark theme only): a sky that turns once
// every 12 minutes, five islands on the horizon, a light on each, and the
// tunnels from the hub arching over the water. The same scene runs in the
// marketing site's hero, so the product and the site read as one place.
//
// Everything here is drawn once per size. The turning is a CSS animation on
// the sky's wrapper, so the compositor rotates a finished bitmap and no
// requestAnimationFrame loop runs while someone is typing a password.

const NS = "http://www.w3.org/2000/svg";

// Share of the page below the horizon.
const SEA = 0.2;

// Deterministic, so the sky and the coastline are the same on every visit and
// a resize does not reshuffle them.
const rng = (s) => () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);

const ISLANDS = [
  { id: "hub", nx: 0.17, nw: 0.15, nh: 30, hub: true },
  { id: "a", nx: 0.035, nw: 0.07, nh: 11 },
  { id: "b", nx: 0.34, nw: 0.05, nh: 8 },
  { id: "c", nx: 0.72, nw: 0.09, nh: 17 },
  { id: "d", nx: 0.89, nw: 0.13, nh: 23 },
];

function fit(canvas, w, h, scale) {
  canvas.width = Math.round(w * scale);
  canvas.height = Math.round(h * scale);
  const x = canvas.getContext("2d");
  x.setTransform(scale, 0, 0, scale, 0, 0);
  return x;
}

/**
 * Stars on a disc centred on a pole above the top-right edge. On screen the
 * turn then reads as slow, flat arcs, the way stars cross a window over an
 * evening, rather than as a spinning wheel.
 */
function drawSky(wrap, canvas, w, h) {
  const P = { x: w * 0.82, y: -h * 0.35 };
  const R = Math.max(...[[0, 0], [w, 0], [0, h], [w, h]].map(([a, b]) => Math.hypot(a - P.x, b - P.y))) + 4;
  Object.assign(wrap.style, {
    left: (P.x - R) + "px", top: (P.y - R) + "px", width: 2 * R + "px", height: 2 * R + "px",
  });
  // Cap the backing store: a phone would otherwise allocate a poster-sized
  // canvas for one-pixel stars.
  const cap = w < 700 ? 2048 : 3072;
  const x = fit(canvas, 2 * R, 2 * R, Math.min(window.devicePixelRatio || 1, cap / (2 * R)));
  x.clearRect(0, 0, 2 * R, 2 * R);
  const r = rng(1013904223);
  const inDisk = (a, b) => Math.hypot(a - R, b - R) < R;

  // Field stars. Most are faint, as they are away from a city.
  const n = Math.round((Math.PI * R * R) / 5200);
  for (let i = 0; i < n; i++) {
    const px = r() * 2 * R, py = r() * 2 * R, m = r();
    if (!inDisk(px, py)) continue;
    const a = m > 0.93 ? 0.85 : m > 0.75 ? 0.45 + r() * 0.25 : 0.12 + r() * 0.28;
    const size = m > 0.93 ? 1.8 : m > 0.75 ? 1.3 : 1;
    const tint = r();
    const col = tint < 0.12 ? "215,228,255" : tint < 0.2 ? "240,236,225" : "190,228,245";
    x.fillStyle = "rgba(" + col + "," + a.toFixed(3) + ")";
    x.fillRect(Math.round(px), Math.round(py), size, size);
    if (m > 0.97) {
      const g = x.createRadialGradient(px + 1, py + 1, 0, px + 1, py + 1, 6);
      g.addColorStop(0, "rgba(" + col + ",.28)");
      g.addColorStop(1, "rgba(" + col + ",0)");
      x.fillStyle = g;
      x.fillRect(px - 6, py - 6, 14, 14);
    }
  }

  // The milky way as a band of denser, fainter dots rather than a wash.
  const cs = Math.cos(-0.55), sn = Math.sin(-0.55);
  const bc = { x: R - R * 0.35, y: R + R * 0.45 };
  for (let i = 0; i < n * 2.2; i++) {
    const along = (r() - 0.5) * 2 * R * 1.6;
    const across = (r() + r() + r() - 1.5) * R * 0.1;
    const px = bc.x + along * cs - across * sn, py = bc.y + along * sn + across * cs;
    if (!inDisk(px, py)) continue;
    x.fillStyle = "rgba(170,210,235," + (0.08 + r() * 0.22).toFixed(3) + ")";
    x.fillRect(Math.round(px), Math.round(py), 1, 1);
  }

  // Twinklers ride on the wrapper, so they turn with the sky.
  wrap.querySelectorAll(".login-twinkle").forEach((el) => el.remove());
  const near = Math.max(0, -P.y);
  for (let i = 0; i < 46; i++) {
    const rr = near + r() * (R - near), th = Math.PI * (0.35 + r() * 1.1);
    const el = document.createElement("span");
    el.className = "login-twinkle";
    el.style.left = (R + rr * Math.cos(th)) + "px";
    el.style.top = (R + rr * Math.sin(th)) + "px";
    el.style.setProperty("--tw", (2.4 + r() * 4).toFixed(2) + "s");
    el.style.animationDelay = (-r() * 6).toFixed(2) + "s";
    wrap.appendChild(el);
  }
}

/** Height along an island as a sum of soft humps, seeded per island. */
function islandProfile(isl, w, s) {
  const r = rng(isl.id.charCodeAt(0) * 7919);
  const humps = Array.from({ length: 3 }, () => ({ c: 0.25 + r() * 0.5, k: 0.12 + r() * 0.18, a: 0.5 + r() * 0.5 }));
  const x0 = (isl.nx - isl.nw / 2) * w, x1 = (isl.nx + isl.nw / 2) * w;
  const pts = [];
  for (let i = 0; i <= 60; i++) {
    const t = i / 60;
    let v = 0;
    for (const hm of humps) v += hm.a * Math.exp(-((t - hm.c) ** 2) / (2 * hm.k * hm.k));
    v = Math.min(1, v / 1.2) * Math.min(1, Math.min(t, 1 - t) * 6);
    pts.push([x0 + (x1 - x0) * t, v * isl.nh * s]);
  }
  let peak = pts[0];
  for (const p of pts) if (p[1] > peak[1]) peak = p;
  return { pts, peak };
}

/** Haze, water, horizon and island silhouettes. Returns where the lights sit. */
function drawSea(canvas, w, h, horizonY) {
  const x = fit(canvas, w, h, window.devicePixelRatio || 1);
  x.clearRect(0, 0, w, h);
  const s = Math.max(0.6, Math.min(1.4, h / 900));

  // Stars thin out toward the horizon, which picks up a faint haze.
  const haze = x.createLinearGradient(0, horizonY - h * 0.32, 0, horizonY);
  haze.addColorStop(0, "rgba(10,12,17,0)");
  haze.addColorStop(0.7, "rgba(11,15,22,.75)");
  haze.addColorStop(1, "rgba(22,32,44,1)");
  x.fillStyle = haze;
  x.fillRect(0, horizonY - h * 0.32, w, h * 0.32);

  // Water: darker than the sky, with long faint swell lines.
  const sea = x.createLinearGradient(0, horizonY, 0, h);
  sea.addColorStop(0, "#0f161f");
  sea.addColorStop(0.25, "#0a0e14");
  sea.addColorStop(1, "#06080c");
  x.fillStyle = sea;
  x.fillRect(0, horizonY, w, h - horizonY);
  const r = rng(42);
  for (let i = 0; i < 70; i++) {
    const t = r();
    const y = horizonY + 3 + (h - horizonY) * t * t;
    const len = 20 + r() * 120 * (0.4 + t);
    const px = r() * w;
    x.fillStyle = "rgba(120,170,200," + (0.025 + 0.05 * (1 - t)).toFixed(3) + ")";
    x.fillRect(px, Math.round(y), len, 1);
  }
  x.fillStyle = "rgba(140,190,215,.10)";
  x.fillRect(0, horizonY, w, 1);

  const lights = [];
  for (const isl of ISLANDS) {
    const { pts, peak } = islandProfile(isl, w, s);
    x.beginPath();
    x.moveTo(pts[0][0], horizonY + 1);
    for (const [px, ph] of pts) x.lineTo(px, horizonY - ph);
    x.lineTo(pts[pts.length - 1][0], horizonY + 1);
    x.closePath();
    x.fillStyle = "#05070a";
    x.fill();
    x.strokeStyle = "rgba(140,200,225,.10)";
    x.lineWidth = 1;
    x.stroke();
    // A faint mirror of the silhouette in the water.
    x.save();
    x.globalAlpha = 0.35;
    x.beginPath();
    x.moveTo(pts[0][0], horizonY + 1);
    for (const [px, ph] of pts) x.lineTo(px, horizonY + 1 + ph * 0.6);
    x.lineTo(pts[pts.length - 1][0], horizonY + 1);
    x.closePath();
    x.fillStyle = "#040507";
    x.fill();
    x.restore();
    lights.push({ hub: !!isl.hub, x: peak[0], y: horizonY - peak[1] - 3 });
  }
  return lights;
}

/** Island lights, their reflections, and the tunnels from the hub. */
function layoutLights(svg, box, lights, w, h, horizonY) {
  svg.setAttribute("viewBox", "0 0 " + w + " " + h);
  svg.replaceChildren();
  box.replaceChildren();
  const hub = lights.find((l) => l.hub);
  lights.forEach((l, i) => {
    const place = (el, top) => { el.style.left = l.x + "px"; el.style.top = top + "px"; box.appendChild(el); };
    if (l.hub) {
      const glow = document.createElement("span");
      glow.className = "login-light-glow";
      place(glow, l.y);
    }
    const dot = document.createElement("span");
    dot.className = "login-light" + (l.hub ? " login-light--hub" : "");
    place(dot, l.y);
    const refl = document.createElement("span");
    refl.className = "login-reflection";
    refl.style.height = (l.hub ? 70 : 38) * Math.max(0.7, h / 900) + "px";
    refl.style.animationDelay = (-i * 0.7) + "s";
    place(refl, horizonY + 3);
    if (!l.hub) {
      // A low arc over the water, like the lines of the mark.
      const lift = Math.min(160, Math.abs(l.x - hub.x) * 0.18) + 18;
      const path = document.createElementNS(NS, "path");
      path.setAttribute("d", "M" + hub.x + " " + hub.y + " Q" + ((hub.x + l.x) / 2) + " "
        + (Math.min(hub.y, l.y) - lift) + " " + l.x + " " + l.y);
      path.style.animationDelay = (-i * 1.3) + "s";
      svg.appendChild(path);
    }
  });
}

/**
 * Draws the whole scene at the given size.
 *
 * @returns the horizon's y, so a shooting star can stop there instead of
 *          streaking across the water.
 */
export function drawNightScene({ skyWrap, sky, sea, routes, lights }, w, h) {
  const horizonY = Math.round(h * (1 - SEA));
  drawSky(skyWrap, sky, w, horizonY);
  layoutLights(routes, lights, drawSea(sea, w, h, horizonY), w, h, horizonY);
  return horizonY;
}
