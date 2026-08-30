package de.chriscohnen.islandr;

import de.chriscohnen.islandr.acl.PortGroupDto;
import de.chriscohnen.islandr.acl.ReservationDto;
import de.chriscohnen.islandr.acl.ResourceDto;
import de.chriscohnen.islandr.acl.RoleDto;
import de.chriscohnen.islandr.acl.SiteDto;
import de.chriscohnen.islandr.audit.AuditDto;
import de.chriscohnen.islandr.auth.AuthResource;
import de.chriscohnen.islandr.dashboard.DashboardDto;
import de.chriscohnen.islandr.discovery.DiscoveryDto;
import de.chriscohnen.islandr.firewall.FirewallDto;
import de.chriscohnen.islandr.hosthealth.HostHealthDto;
import de.chriscohnen.islandr.identity.OidcProviderDto;
import de.chriscohnen.islandr.peer.MyPeerResource;
import de.chriscohnen.islandr.peer.PeerDto;
import de.chriscohnen.islandr.settings.SettingsDto;
import de.chriscohnen.islandr.user.UserDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Forces native-image to keep reflection metadata for all JSON-mapped DTO
 * records. Without this, inner records declared on JAX-RS resources
 * (AuthResource.MeResponse, MyPeerResource.CreateMineRequest, …) and the
 * nested classes inside the *Dto containers (DashboardDto.Topology, …) get
 * stripped because the build-time analysis doesn't see Jackson reaching them.
 *
 * <p>Adding a top-level container to {@code targets} automatically pulls in
 * its nested records — that is why only the umbrella classes appear here,
 * not every individual record.
 *
 * <p>JVM-mode is unaffected (no reflection trimming there).
 */
// Drei Flags zusammen sind die robuste Variante fuer JSON-DTO records im
// Native-Image:
//   serialization = true  -> Quarkus erzeugt einen native-image-tauglichen
//                            Reflection-Eintrag explizit fuer Jackson-Serializer
//   methods = true        -> die record-Component-Accessoren (principal(),
//                            userId(), ...) bleiben fuer Reflection sichtbar
//   fields  = true        -> die hinterliegenden Felder bleiben sichtbar,
//                            falls Jackson per Field-Access geht
// Ohne diese Kombination war /auth/me ein leeres {} im Native-Image, obwohl
// die Klasse selbst registriert war.
@RegisterForReflection(serialization = true, methods = true, fields = true, targets = {
        AuthResource.LoginRequest.class,
        AuthResource.MeResponse.class,
        AuthResource.PublicProvider.class,

        UserDto.Response.class,
        UserDto.CreateRequest.class,
        UserDto.AdminFlagRequest.class,

        PeerDto.Response.class,
        PeerDto.CreateRequest.class,
        PeerDto.CreateResponse.class,
        PeerDto.EnabledRequest.class,
        PeerDto.UpdateRequest.class,
        PeerDto.NextIpResponse.class,

        MyPeerResource.CreateMineRequest.class,
        MyPeerResource.RotateKeyRequest.class,

        SettingsDto.Response.class,
        SettingsDto.UpdateRequest.class,

        OidcProviderDto.Response.class,
        OidcProviderDto.UpdateRequest.class,

        SiteDto.Response.class,
        SiteDto.UpsertRequest.class,

        ResourceDto.Response.class,
        ResourceDto.PortResponse.class,
        ResourceDto.UpsertRequest.class,
        ResourceDto.PortRequest.class,
        ResourceDto.BulkDeleteRequest.class,
        ResourceDto.BulkDeleteResult.class,
        // Issue #72 — nested records must each be listed explicitly; a
        // top-level container does not pull them in (see this file's own
        // targets array, which enumerates every one).
        ResourceDto.ReservationHolder.class,
        ReservationDto.Response.class,
        ReservationDto.CreateRequest.class,
        ReservationDto.HolderResponse.class,
        ReservationDto.AtCapacityResponse.class,

        PortGroupDto.Response.class,
        PortGroupDto.UpsertRequest.class,
        PortGroupDto.ApplyRequest.class,
        PortGroupDto.ApplyResponse.class,

        RoleDto.Response.class,
        RoleDto.UpsertRequest.class,
        RoleDto.MembershipRequest.class,
        RoleDto.MemberResponse.class,
        RoleDto.GrantCell.class,
        RoleDto.GrantUpdate.class,
        RoleDto.MatrixApplyRequest.class,

        DashboardDto.Response.class,
        DashboardDto.FirewallStatus.class,
        DashboardDto.Topology.class,
        DashboardDto.TopologySite.class,
        DashboardDto.TopologyResource.class,
        DashboardDto.TopologyLivePeer.class,
        DashboardDto.PeerStats.class,
        DashboardDto.UserStats.class,
        DashboardDto.RoleStats.class,
        DashboardDto.ResourceStats.class,
        DashboardDto.SetupStatus.class,
        DashboardDto.AuditEntry.class,
        DashboardDto.PeerEntry.class,

        HostHealthDto.Snapshot.class,

        FirewallDto.Response.class,

        AuditDto.Response.class,

        DiscoveryDto.ScanStarted.class,
        DiscoveryDto.HostView.class,
        DiscoveryDto.ScanStatus.class,
        DiscoveryDto.ImportHost.class,
        DiscoveryDto.ImportRequest.class,
        DiscoveryDto.ImportResult.class,
})
public final class NativeReflectionConfig {
    private NativeReflectionConfig() {}
}
