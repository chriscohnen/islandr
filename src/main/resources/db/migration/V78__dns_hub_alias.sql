-- An extra name the resolver answers for the hub itself, alongside the fixed
-- hub.<zone> record. Optional and empty by default.
--
-- Why a second name at all: an installation with its own domain usually reaches
-- the console under that domain, and that name is resolved by whatever DNS the
-- client normally uses. When that resolver is unreachable, the console becomes
-- unreachable by name at exactly the moment someone needs it. Answering the
-- same name inside the tunnel removes that dependency.
ALTER TABLE settings ADD COLUMN dns_hub_alias VARCHAR(253);
