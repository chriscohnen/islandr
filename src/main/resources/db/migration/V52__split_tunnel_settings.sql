-- V52: explicit full/split tunnel setting (#33, ADR-0017, F-22).
--
-- tunnel_mode: FULL | SPLIT — the binary choice from F-22. Defaults to SPLIT,
-- matching today's de-facto behavior (wgClientAllowedIps has always been a
-- site-CIDR-shaped list, never a true full-tunnel default).
--
-- allowed_ips_mode: AUTO | MANUAL — whether AllowedIPs is computed
-- (AllowedIpsCalculator) or the admin types it directly into
-- wg_client_allowed_ips. Defaults to MANUAL for every install (new and
-- existing) so this migration itself never opts an admin into AUTO mode and
-- never touches an already-downloaded .conf file. It does NOT mean a peer's
-- config is unaffected if regenerated after the upgrade: on installs with
-- sites behind enabled gateway peers, PeerService.renderConf used to
-- auto-append every such site's CIDR to wgClientAllowedIps, and this feature
-- removes that auto-append (AllowedIpsCalculator.compute returns
-- wgClientAllowedIps verbatim in MANUAL mode). A freshly regenerated .conf
-- post-upgrade will be missing those site routes — that's the intended
-- effect of this feature, not a bug in this default.
--
-- split_supernet: admin-declared CIDR sized to cover current and future site
-- networks (e.g. 10.0.0.0/8), used only when tunnel_mode=SPLIT and
-- allowed_ips_mode=AUTO. Null until an admin sets it.
ALTER TABLE settings ADD COLUMN tunnel_mode VARCHAR(10) NOT NULL DEFAULT 'SPLIT';
ALTER TABLE settings ADD COLUMN allowed_ips_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE settings ADD COLUMN split_supernet VARCHAR(50);
