-- Optional per-peer MTU override. NULL = use global setting (settings.wg_mtu + wg_include_mtu_in_conf).
ALTER TABLE peers ADD COLUMN mtu INTEGER;
