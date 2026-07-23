# FAQ

## How do I see Islandr's log output?

**Native binary (systemd):**

```bash
sudo journalctl -u islandr --no-pager
```

Drop `--no-pager` to page through it interactively, or add `-f` to follow new
entries live, or `-n 30` to see just the most recent lines. See
[docs/install.md](install.md) step 7 ("Verify") and "Upgrading" for the same
command used during install/upgrade checks.

**Docker Compose:**

```bash
docker compose logs -f islandr
```

## How do I check which peers are configured on the WireGuard interface?

```bash
sudo wg show wg0
```

Lists every peer currently known to the kernel — public key, endpoint, allowed IPs,
latest handshake, and transfer stats. Compare this against the peers shown in the
Admin Console to confirm islandr and the live `wg0` interface agree.

## How do I check whether a peer's preshared key is set correctly?

```bash
sudo wg show wg0 preshared-keys
```

Lists each peer's public key next to its preshared key (or `(none)` if unset). Useful
after editing or removing a peer's PSK in the Admin Console, to confirm the change
actually reached the kernel and not just the database.

## How do I temporarily open access for every peer, e.g. to isolate a firewall problem?

```bash
sudo nft delete table inet islandr
```

Removes islandr's entire nftables table — the `forward` chain that enforces ACL
grants between peers and resources disappears with it, so every peer can reach
every other peer/resource until the table is restored. WireGuard tunnels
themselves (`wg0`, peer keys, allowed-ips) are untouched — this only affects
enforcement, not connectivity to the hub.

Use this to tell "is nftables the actual problem?" apart from "is this a
routing/DNS/application issue?" during troubleshooting — if the symptom
disappears with the table gone, the ACL ruleset is implicated; if it's still
broken, look elsewhere.

**Restore enforcement** as soon as you're done — either:

- Settings → Firewall → **Resync — reapply** button in the Admin Console, or
- `curl -X POST http://127.0.0.1:8080/api/v1/firewall/resync` (admin session
  required), or
- `sudo systemctl restart islandr` — the boot self-heal reapplies the ruleset
  on every start.

Any peer/ACL mutation through the Admin Console also recomputes and reapplies
the full ruleset on its own, so the table won't stay missing indefinitely even
if you forget — but don't rely on that while actively diagnosing something,
since a wide-open window is exactly what you're trying to keep short.
