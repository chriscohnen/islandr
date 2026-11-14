package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.apikey.ApiKeyScope;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.firewall.BootTableState;
import de.chriscohnen.islandr.firewall.FirewallState;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.proxy.EnforcementStatus;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.tls.TlsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;

import java.time.Duration;
import java.time.Instant;

/**
 * Health gauges for existing monitoring (#71), in the Prometheus text format
 * written by hand — a metrics library costs native-image memory this install
 * does not have to spare. Only install-wide values: no per-peer and no
 * per-person series, so the endpoint cannot double as staff monitoring.
 *
 * <p>These are the failures that stay silent otherwise: a ruleset that is not
 * being applied (R-199), a boot table that is still dropping forwarded traffic
 * (R-193/R-194) and a certificate about to expire (R-153).
 */
@Path("/api/external/v1/metrics")
public class MonitoringExternalResource {

    @Inject SettingsService settingsSvc;
    @Inject TlsService tlsSvc;
    @Inject EnforcementStatus enforcement;
    @Inject BootTableState bootTable;

    @GET
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    @Transactional
    public String metrics(@Context ContainerRequestContext ctx) {
        Auth.requireScope(ctx, ApiKeyScope.MONITORING_READ);
        return render(Instant.now());
    }

    String render(Instant now) {
        StringBuilder out = new StringBuilder();
        FirewallState fw = FirewallState.get();

        gauge(out, "islandr_enforcement_ok",
                "1 when the last firewall apply succeeded and the enforcement plane is reachable, else 0.",
                FirewallState.OK.equals(fw.lastStatus) && enforcement.state() != EnforcementStatus.State.UNAVAILABLE ? 1 : 0);
        if (fw.lastOkAt != null) {
            gauge(out, "islandr_ruleset_last_apply_age_seconds",
                    "Seconds since the last successful firewall apply.",
                    Math.max(0, Duration.between(fw.lastOkAt, now).getSeconds()));
        }
        BootTableState.Handover h = bootTable.handover();
        gauge(out, "islandr_boot_table_active",
                "1 while the fail-closed boot table is still installed next to or instead of the ruleset.",
                h == BootTableState.Handover.ACTIVE_DRY_RUN || h == BootTableState.Handover.FAILED ? 1 : 0);

        Settings s = settingsSvc.get();
        Instant expires = tlsSvc.certificateExpiresAt(s.tlsCertPem);
        if (expires != null) {
            gauge(out, "islandr_tls_certificate_expiry_days",
                    "Days until the installed TLS certificate expires (negative when it already has).",
                    Math.floorDiv(Duration.between(now, expires).getSeconds(), 86400));
        }
        gauge(out, "islandr_peers_connected",
                "Number of peers currently connected.",
                Peer.count("connectedSince is not null"));
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n')
           .append("# TYPE ").append(name).append(" gauge\n")
           .append(name).append(' ').append(value).append('\n');
    }
}
