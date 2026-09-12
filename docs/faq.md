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

## The service dies with `code=killed, signal=KILL` and no error in the log

That is the kernel, not Islandr. A SIGKILL leaves no stack trace, which is why
`journalctl -u islandr` shows nothing but systemd's own restart lines. On a
small VPS the cause is almost always the OOM killer:

```bash
free -m
sudo dmesg -T | grep -iE "oom|killed process"
sudo journalctl -k -b | grep -iE "oom|killed process"   # if dmesg is restricted
```

Look for `Out of memory: Killed process ... (islandr)`. The native binary needs
about 128 MB resident and 256 MB with headroom; below roughly 192 MB available
it is killed part-way through startup, typically after half a second of CPU
time. Add swap:

```bash
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo systemctl start islandr
```

`setup-hub.sh` refuses to install below this threshold and prints the same
commands.

## The service runs and `curl` works on the host, but the browser cannot reach it

Two causes, in this order.

**Check the bind address first:**

```bash
ss -ltnp | grep islandr
```

`127.0.0.1:8080` means loopback only. A local `curl` succeeds, everything else
— including a browser coming in over the WireGuard tunnel — gets no connection.
That is correct when a reverse proxy on the same host front-ends Islandr, and
wrong in every other case. To reach the Admin Console over the tunnel, bind to
the WireGuard address instead:

```bash
WG_IP=$(ip -4 -o addr show wg0 | awk '{print $4}' | cut -d/ -f1)
sudo sed -i "s|^QUARKUS_HTTP_HOST=.*|QUARKUS_HTTP_HOST=$WG_IP|" /etc/default/islandr
sudo systemctl restart islandr
```

`0.0.0.0` listens on every interface, the public one included — only do that if
you want the console reachable from the internet, and it is what the built-in
TLS/ACME setup expects.

**Then check the host firewall.** `ufw` never blocks loopback, so a working
local `curl` says nothing about it:

```bash
sudo ufw status verbose
sudo ufw allow in on wg0 to any port 8080 proto tcp   # tunnel only
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

## Why are my peers gone after restarting `wg-quick`, and where does islandr store them?

Islandr configures peers with `wg set` and never writes
`/etc/wireguard/wg0.conf` — the file stays yours, and the service is not
privileged to touch it ([ADR-0030](adr/0030-wireguard-config-file-ownership.md)).
Peers it manages therefore live in its database and in kernel state, not in that
file, so `wg-quick` brings the interface back with only what the file contains:

```bash
sudo wg show wg0 peers        # before
sudo systemctl restart wg-quick@wg0
sudo wg show wg0 peers        # only the peers written in wg0.conf
```

Islandr repairs this by itself. It re-applies every enabled peer at startup, and
the activity poller compares the live peer list against the database on each
30-second tick and pushes back whatever is missing — so the peers return within
about half a minute without restarting the service. If firewall writes are
paused (Settings → Firewall, the default on a fresh install), nothing is written
back, which is intended: an adopted hub stays untouched until you activate
enforcement.

To make the peers independent of islandr — before uninstalling, for instance —
use **Peers → Export to wg0.conf** and append the downloaded `[Peer]` blocks to
your server config. The export deliberately contains no `[Interface]` section.

## A peer connects that islandr does not know. Why does it still reach the hub?

Because islandr's ruleset filters **forwarded** traffic. Its `forward` chain is
`policy drop` with an accept rule per granted peer/resource pair, so an
unimported peer reaches no resource behind the hub — but traffic *to* the hub
(admin console, SSH, the DNS resolver) passes through `input`, which islandr does
not filter.

Such peers come from `wg0.conf` entries that were never imported. The Dashboard
reports how many exist; **Peers → Import from wg0** brings them under islandr's
ACLs. Until then they are outside the access model, and their addresses are
excluded from islandr's own address allocation so the two cannot collide.

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
