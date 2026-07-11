import { defineComponent, h } from "vue";

// Lucide-style outline icons. 24-unit viewBox, currentColor stroke, 1.6px stroke
// width, round caps and joins. Sized via the `size` prop (default 18px) and
// re-coloured via currentColor on .nav-item .icon — never hard-code stroke
// colours here.
//
// Pfade sind direkt aus den Lucide-Quellen ueberommen, gleiches Set wie im
// Bastion-Design-Handoff. Wenn ein Icon fehlt: lucide.dev nachschlagen,
// 24-unit viewBox, paths kopieren.

export const PATHS = {
  // Layout-grid: 4 Quadrate
  dashboard: [
    '<rect width="7" height="7" x="3" y="3" rx="1"/>',
    '<rect width="7" height="7" x="14" y="3" rx="1"/>',
    '<rect width="7" height="7" x="14" y="14" rx="1"/>',
    '<rect width="7" height="7" x="3" y="14" rx="1"/>',
  ],

  // monitor-smartphone — der Peer-Begriff bei uns sind Endgeraete.
  peers: [
    '<path d="M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2"/>',
    '<path d="M10 19v-3.96 3.36"/>',
    '<path d="M7 19h5"/>',
    '<rect width="6" height="10" x="16" y="12" rx="2"/>',
  ],

  // network — Hub mit Kindern (Hierarchie)
  networks: [
    '<rect x="16" y="16" width="6" height="6" rx="1"/>',
    '<rect x="2" y="16" width="6" height="6" rx="1"/>',
    '<rect x="9" y="2" width="6" height="6" rx="1"/>',
    '<path d="M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"/>',
    '<path d="M12 12V8"/>',
  ],

  // server-cog — generischer Resourcen-Sammelpunkt (Liste aller Hosts)
  resources: [
    '<path d="M5 10H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-1"/>',
    '<path d="M5 14H4a2 2 0 0 0-2 2v4a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-4a2 2 0 0 0-2-2h-1"/>',
    '<path d="M6 6h.01"/>',
    '<path d="M6 18h.01"/>',
    '<circle cx="12" cy="12" r="3"/>',
    '<path d="M12 8.5V10"/>',
    '<path d="M12 14v1.5"/>',
    '<path d="M16.5 10.5 15 11.5"/>',
    '<path d="M9 12.5 7.5 13.5"/>',
    '<path d="m7.5 10.5 1.5 1"/>',
    '<path d="m15 12.5 1.5 1"/>',
  ],

  // ---- Peer-Gerätetypen ----

  // laptop
  laptop: [
    '<path d="M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16"/>',
  ],

  // desktop — Monitor mit Standfuss
  desktop: [
    '<rect width="20" height="14" x="2" y="3" rx="2"/>',
    '<line x1="8" x2="16" y1="21" y2="21"/>',
    '<line x1="12" x2="12" y1="17" y2="21"/>',
  ],

  // mobile — Smartphone
  mobile: [
    '<rect width="14" height="20" x="5" y="2" rx="2"/>',
    '<path d="M12 18h.01"/>',
  ],

  // tablet
  tablet: [
    '<rect width="16" height="20" x="4" y="2" rx="2"/>',
    '<path d="M12 18h.01"/>',
  ],

  // server — Rack-Server
  server: [
    '<rect width="20" height="8" x="2" y="2" rx="2"/>',
    '<rect width="20" height="8" x="2" y="14" rx="2"/>',
    '<line x1="6" x2="6.01" y1="6" y2="6"/>',
    '<line x1="6" x2="6.01" y1="18" y2="18"/>',
  ],

  // rotate-ccw — Lucide MIT (key rotation)
  rotate: [
    '<polyline points="1 4 1 10 7 10"/>',
    '<path d="M3.51 15a9 9 0 1 0 .49-3.27"/>',
  ],

  // trash — Lucide MIT
  trash: [
    '<polyline points="3 6 5 6 21 6"/>',
    '<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>',
    '<path d="M10 11v6"/>',
    '<path d="M14 11v6"/>',
    '<path d="M9 6V4h6v2"/>',
  ],

  // shield — Lucide MIT (admin/privilege actions)
  shield: [
    '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  ],

  // shield-off — Lucide MIT (revoke admin)
  'shield-off': [
    '<path d="M19.69 14a6.9 6.9 0 0 0 .31-2V5l-8-3-3.16 1.18"/>',
    '<path d="M4.73 4.73L4 5v7c0 6 8 10 8 10a20.3 20.3 0 0 0 5.62-4.38"/>',
    '<line x1="1" x2="23" y1="1" y2="23"/>',
  ],

  // qr-code — Lucide MIT
  'qr-code': [
    '<rect width="5" height="5" x="3" y="3" rx="1"/>',
    '<rect width="5" height="5" x="16" y="3" rx="1"/>',
    '<rect width="5" height="5" x="3" y="16" rx="1"/>',
    '<path d="M21 16h-3a2 2 0 0 0-2 2v3"/>',
    '<line x1="21" x2="21" y1="21" y2="21"/>',
    '<path d="M12 7v3a2 2 0 0 1-2 2H7"/>',
    '<line x1="3" x2="3" y1="12" y2="12"/>',
    '<line x1="12" x2="12" y1="3" y2="3"/>',
  ],

  // pause-circle — Lucide MIT (disable)
  'pause-circle': [
    '<circle cx="12" cy="12" r="10"/>',
    '<line x1="10" x2="10" y1="15" y2="9"/>',
    '<line x1="14" x2="14" y1="15" y2="9"/>',
  ],

  // play-circle — Lucide MIT (enable)
  'play-circle': [
    '<circle cx="12" cy="12" r="10"/>',
    '<polygon points="10 8 16 12 10 16 10 8"/>',
  ],

  // external-link — Lucide MIT (view as user)
  'external-link': [
    '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
    '<polyline points="15 3 21 3 21 9"/>',
    '<line x1="10" x2="21" y1="14" y2="3"/>',
  ],

  // edit (pencil) — Lucide MIT
  edit: [
    '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>',
    '<path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>',
  ],

  // monitor — Lucide MIT
  monitor: [
    '<rect width="20" height="14" x="2" y="3" rx="2"/>',
    '<line x1="8" x2="16" y1="21" y2="21"/>',
    '<line x1="12" x2="12" y1="17" y2="21"/>',
  ],

  // download — Lucide MIT
  download: [
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>',
    '<polyline points="7 10 12 15 17 10"/>',
    '<line x1="12" x2="12" y1="15" y2="3"/>',
  ],

  // ---- Resource-Typen fuer Topology + Resourcen-Liste ----

  // monitor — Workstation/Server
  computer: [
    '<rect width="20" height="14" x="2" y="3" rx="2"/>',
    '<line x1="8" x2="16" y1="21" y2="21"/>',
    '<line x1="12" x2="12" y1="17" y2="21"/>',
  ],

  // printer — Netzwerk-Drucker
  printer: [
    '<path d="M6 9V2h12v7"/>',
    '<path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/>',
    '<rect width="12" height="8" x="6" y="14"/>',
  ],

  // hard-drive — NAS / Storage
  nas: [
    '<line x1="22" x2="2" y1="12" y2="12"/>',
    '<path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
    '<line x1="6" x2="6.01" y1="16" y2="16"/>',
    '<line x1="10" x2="10.01" y1="16" y2="16"/>',
  ],

  // router — Router/Layer-3-Switch (FRITZ!Box, UCG, OPNsense, Mikrotik …)
  router: [
    '<rect width="20" height="8" x="2" y="14" rx="2"/>',
    '<path d="M6.01 18H6"/>',
    '<path d="M10.01 18H10"/>',
    '<path d="M15 10v4"/>',
    '<path d="M17.84 7.17a4 4 0 0 0-5.66 0"/>',
    '<path d="M20.66 4.34a8 8 0 0 0-11.31 0"/>',
  ],

  // video — Kamera (RTSP/ONVIF/Web-UI)
  camera: [
    '<path d="m22 8-6 4 6 4V8Z"/>',
    '<rect width="14" height="12" x="2" y="6" rx="2" ry="2"/>',
  ],

  // home — Smarthome / IoT-Bridges (Hue, HomeAssistant, Smart-Plugs)
  iot: [
    '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    '<polyline points="9 22 9 12 15 12 15 22"/>',
  ],

  // server — Virtualisierungs-Host (Proxmox, ESXi, TrueNAS-Scale)
  "virt-host": [
    '<rect width="20" height="8" x="2" y="2" rx="2" ry="2"/>',
    '<rect width="20" height="8" x="2" y="14" rx="2" ry="2"/>',
    '<line x1="6" x2="6.01" y1="6" y2="6"/>',
    '<line x1="6" x2="6.01" y1="18" y2="18"/>',
  ],

  // rack cabinet — dedizierter Rackserver (z.B. Dell PowerEdge), abgesetzt vom
  // virt-host-Look. Vier Höheneinheiten mit Status-LED + Slot-Linie.
  rackserver: [
    '<rect width="16" height="20" x="4" y="2" rx="2"/>',
    '<path d="M8 6h.01"/>',
    '<path d="M8 10h.01"/>',
    '<path d="M8 14h.01"/>',
    '<path d="M8 18h.01"/>',
    '<path d="M12 6h4"/>',
    '<path d="M12 10h4"/>',
    '<path d="M12 14h4"/>',
    '<path d="M12 18h4"/>',
  ],

  // monitor-smartphone — NanoKVM / KVM-over-IP (Remote-Konsole): Konsole am
  // Gerät, wie ein Endgerät erreichbar. Out-of-Band, sicherheitskritisch.
  kvm: [
    '<path d="M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2"/>',
    '<path d="M10 19v-3.96 3.36"/>',
    '<path d="M7 19h5"/>',
    '<rect width="6" height="10" x="16" y="12" rx="2"/>',
  ],

  // cpu — Out-of-Band-Management (IPMI, iDRAC, iLO). Sicherheitskritisch.
  management: [
    '<rect x="4" y="4" width="16" height="16" rx="2"/>',
    '<rect x="9" y="9" width="6" height="6"/>',
    '<path d="M15 2v2"/>',
    '<path d="M15 20v2"/>',
    '<path d="M2 15h2"/>',
    '<path d="M2 9h2"/>',
    '<path d="M20 15h2"/>',
    '<path d="M20 9h2"/>',
    '<path d="M9 2v2"/>',
    '<path d="M9 20v2"/>',
  ],

  // box — Fallback fuer alles andere
  other: [
    '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/>',
    '<path d="m3.3 7 8.7 5 8.7-5"/>',
    '<path d="M12 22V12"/>',
  ],

  // square-stack — gestapelte Quadrate
  portGroups: [
    '<path d="M4 10c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h4c1.1 0 2 .9 2 2"/>',
    '<path d="M14 4c-1.1 0-2-.9-2-2"/>',
    '<rect width="12" height="12" x="8" y="8" rx="2"/>',
  ],

  // user-cog — Lucide MIT (Rollen / Berechtigungen)
  roles: [
    '<circle cx="18" cy="15" r="3"/>',
    '<path d="M18 12v-1.5"/>',
    '<path d="M18 21v-1.5"/>',
    '<path d="m15.4 16.5-.87-.5"/>',
    '<path d="m21.47 13.5-.87-.5"/>',
    '<path d="m15.4 13.5-.87.5"/>',
    '<path d="m21.47 16.5-.87.5"/>',
    '<path d="M13 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>',
    '<circle cx="9" cy="7" r="4"/>',
  ],

  // shield-check — Rollen & ACL
  acl: [
    '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    '<path d="m9 12 2 2 4-4"/>',
  ],

  // users — Benutzer
  users: [
    '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/>',
    '<circle cx="9" cy="7" r="4"/>',
    '<path d="M22 21v-2a4 4 0 0 0-3-3.87"/>',
    '<path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  ],

  // id-card — Identity (Provider-Setup)
  identity: [
    '<path d="M16 10h2"/>',
    '<path d="M16 14h2"/>',
    '<path d="M6.17 15a3 3 0 0 1 5.66 0"/>',
    '<circle cx="9" cy="11" r="2"/>',
    '<rect x="2" y="5" width="20" height="14" rx="2"/>',
  ],

  // flame — Firewall
  firewall: [
    '<path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/>',
  ],

  // clipboard-list — Audit-Log
  audit: [
    '<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/>',
    '<path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>',
    '<path d="M12 11h4"/>',
    '<path d="M12 16h4"/>',
    '<path d="M8 11h.01"/>',
    '<path d="M8 16h.01"/>',
  ],

  // external-link — öffnet in neuem Tab
  "external-link": [
    '<path d="M15 3h6v6"/>',
    '<path d="M10 14 21 3"/>',
    '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
  ],

  // sun — Tagesmodus
  sun: [
    '<circle cx="12" cy="12" r="4"/>',
    '<path d="M12 2v2"/>',
    '<path d="M12 20v2"/>',
    '<path d="m4.93 4.93 1.41 1.41"/>',
    '<path d="m17.66 17.66 1.41 1.41"/>',
    '<path d="M2 12h2"/>',
    '<path d="M20 12h2"/>',
    '<path d="m6.34 17.66-1.41 1.41"/>',
    '<path d="m19.07 4.93-1.41 1.41"/>',
  ],

  // moon — Nachtmodus
  moon: [
    '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  ],

  // settings — Zahnrad
  settings: [
    '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/>',
    '<circle cx="12" cy="12" r="3"/>',
  ],
};

export const Icon = defineComponent({
  name: "Icon",
  props: {
    name: { type: String, required: true },
    size: { type: Number, default: 18 },
  },
  setup(props) {
    return () => {
      const paths = PATHS[props.name];
      if (!paths) {
        // Sichtbarer Fallback — leeres Quadrat, nicht unsichtbar.
        // Verhindert Layout-Shift wenn jemand einen Tippfehler im name macht.
        return h("span", {
          class: "icon icon-missing",
          style: { width: props.size + "px", height: props.size + "px" },
          title: "icon missing: " + props.name,
        });
      }
      return h("svg", {
        class: "icon",
        xmlns: "http://www.w3.org/2000/svg",
        width: props.size,
        height: props.size,
        viewBox: "0 0 24 24",
        fill: "none",
        stroke: "currentColor",
        "stroke-width": "1.6",
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
        innerHTML: paths.join(""),
      });
    };
  },
});
