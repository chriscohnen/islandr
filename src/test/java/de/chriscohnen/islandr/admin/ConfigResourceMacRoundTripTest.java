package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource.mac survives export -> import (issue #76) — the same round-trip
 *  discipline every other resource field already gets (see
 *  ConfigImportRoundTripTest's own class-doc for why the read-back must
 *  happen in a fresh transaction, not off the import's own return value). */
@QuarkusTest
class ConfigResourceMacRoundTripTest {

    @Inject ConfigService configService;

    @Test
    void mac_survivesExportAndImport() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ip = "10.98.0.5";

        QuarkusTransaction.requiringNew().run(() -> {
            Site site = Site.createNew("MacRoundTrip-" + suffix, "10.98.0.0/24", null);
            site.persist();
            Resource r = Resource.createNew(site.id, "Pi-" + suffix, ip, null, "computer");
            r.mac = "b8:27:eb:00:11:22";
            r.persist();
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        configService.importConfig(export);

        Resource imported = QuarkusTransaction.requiringNew()
                .call(() -> Resource.<Resource>find("ip", ip).firstResult());
        assertThat(imported).isNotNull();
        assertThat(imported.mac).isEqualTo("b8:27:eb:00:11:22");
    }
}
