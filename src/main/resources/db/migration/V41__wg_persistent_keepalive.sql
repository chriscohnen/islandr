-- Configurable PersistentKeepalive (issue #28). Until now every client .conf
-- carried a hardcoded `PersistentKeepalive = 25`. Make it configurable with a
-- global default plus an optional per-peer override, mirroring the MTU setting.
--
-- Semantics: the value IS the switch — 0 = disabled (no keepalive line), 1..65535
-- = interval in seconds. Default 25 preserves the behaviour shipped since 0.10.0.
ALTER TABLE settings ADD COLUMN wg_persistent_keepalive INTEGER NOT NULL DEFAULT 25;

-- Optional per-peer override. NULL = defer to settings.wg_persistent_keepalive;
-- 0 = keepalive explicitly off for this peer; N = explicit interval.
ALTER TABLE peers ADD COLUMN persistent_keepalive INTEGER;
