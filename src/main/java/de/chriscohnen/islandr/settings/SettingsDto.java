package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.validation.ValidCidr;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class SettingsDto {

    public record Response(
            String wgSubnet,
            String wgSubnet6,
            String wgServerPublicKey,
            String wgServerEndpoint,
            String wgClientAllowedIps,
            String wgClientDns,
            String privateKeyRetention,
            boolean gravatarEnabled,
            boolean oidcAutoProvision,
            boolean firewallDryRun,
            boolean selfServicePeerCreation,
            Integer wgMtu,
            boolean wgIncludeMtuInConf,
            String nominatimUrl,
            Double hubLat,
            Double hubLon,
            String hubLocationLabel,
            Instant updatedAt,
            String updatedBy,
            boolean setupComplete,
            String version,
            boolean encryptionKeyConfigured,
            boolean googleWsConfigured,
            String googleWsImpersonationEmail
    ) {
        public static Response from(Settings s, String version, boolean encryptionKeyConfigured) {
            return new Response(
                    s.wgSubnet, s.wgSubnet6,
                    s.wgServerPublicKey, s.wgServerEndpoint,
                    s.wgClientAllowedIps, s.wgClientDns, s.privateKeyRetention,
                    s.gravatarEnabled, s.oidcAutoProvision, s.firewallDryRun,
                    s.selfServicePeerCreation, s.wgMtu, s.wgIncludeMtuInConf,
                    s.nominatimUrl,
                    s.hubLat, s.hubLon, s.hubLocationLabel,
                    s.updatedAt, s.updatedBy,
                    !s.wgServerPublicKey.startsWith("PLACEHOLDER"),
                    version,
                    encryptionKeyConfigured,
                    s.googleWsServiceAccountJson != null && !s.googleWsServiceAccountJson.isBlank(),
                    s.googleWsImpersonationEmail);
        }
    }

    public record GoogleWorkspaceRequest(
            String serviceAccountJson,
            String impersonationEmail
    ) {}

    public record UpdateRequest(
            @NotBlank @ValidCidr
            String wgSubnet,

            // optional — null/blank = IPv4-only deployment
            @ValidCidr
            String wgSubnet6,

            @NotBlank String wgServerPublicKey,
            @NotBlank String wgServerEndpoint,
            @NotBlank String wgClientAllowedIps,

            // optional — empty / null both mean "no DNS line in client conf"
            String wgClientDns,

            @NotBlank
            @Pattern(regexp = "^(never|plaintext|encrypted)$",
                    message = "must be one of: never, plaintext, encrypted")
            String privateKeyRetention,

            // optional — defaults to false (privacy-first; sends md5(email) to gravatar.com)
            boolean gravatarEnabled,

            // optional — defaults to true; set false to require manual user creation before OIDC login
            boolean oidcAutoProvision,

            // optional — when true, WG and nftables adapters run in mock mode at runtime
            boolean firewallDryRun,

            // optional — when false, POST /api/v1/peers/mine returns 403
            boolean selfServicePeerCreation,

            // optional — null means not set; stored from probe auto-save or manual entry
            Integer wgMtu,

            // optional — when true, MTU = <wgMtu> is written into client .conf files
            boolean wgIncludeMtuInConf,

            // optional — base URL of a Nominatim instance; null/blank = geocoding disabled
            String nominatimUrl,

            // optional — hub location for topology map
            Double hubLat,
            Double hubLon,
            String hubLocationLabel
    ) {}

    private SettingsDto() {}
}
