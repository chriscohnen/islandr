package de.chriscohnen.islandr.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class SettingsDto {

    public record Response(
            String wgSubnet,
            String wgServerPublicKey,
            String wgServerEndpoint,
            String wgClientAllowedIps,
            String wgClientDns,
            String privateKeyRetention,
            boolean gravatarEnabled,
            boolean oidcAutoProvision,
            boolean firewallDryRun,
            boolean selfServicePeerCreation,
            Instant updatedAt,
            String updatedBy,
            boolean setupComplete,
            String version
    ) {
        public static Response from(Settings s, String version) {
            return new Response(
                    s.wgSubnet, s.wgServerPublicKey, s.wgServerEndpoint,
                    s.wgClientAllowedIps, s.wgClientDns, s.privateKeyRetention,
                    s.gravatarEnabled, s.oidcAutoProvision, s.firewallDryRun,
                    s.selfServicePeerCreation,
                    s.updatedAt, s.updatedBy,
                    !s.wgServerPublicKey.startsWith("PLACEHOLDER"),
                    version);
        }
    }

    public record UpdateRequest(
            @NotBlank @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$",
                    message = "must be IPv4 CIDR (e.g. 10.8.0.0/24)")
            String wgSubnet,

            @NotBlank String wgServerPublicKey,
            @NotBlank String wgServerEndpoint,
            @NotBlank String wgClientAllowedIps,

            // optional — empty / null both mean "no DNS line in client conf"
            String wgClientDns,

            @NotBlank
            @Pattern(regexp = "^(never|plaintext)$",
                    message = "must be one of: never, plaintext")
            String privateKeyRetention,

            // optional — defaults to false (privacy-first; sends md5(email) to gravatar.com)
            boolean gravatarEnabled,

            // optional — defaults to true; set false to require manual user creation before OIDC login
            boolean oidcAutoProvision,

            // optional — when true, WG and nftables adapters run in mock mode at runtime
            boolean firewallDryRun,

            // optional — when false, POST /api/v1/peers/mine returns 403
            boolean selfServicePeerCreation
    ) {}

    private SettingsDto() {}
}
