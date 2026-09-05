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
