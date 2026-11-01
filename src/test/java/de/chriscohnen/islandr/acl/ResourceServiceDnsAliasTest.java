package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.dns.DnsQueryHandler;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** resource-dns-aliases: several DNS names on one resource. */
@QuarkusTest
class ResourceServiceDnsAliasTest {

    private static final String ZONE = "islandr.internal";

    @Inject ResourceService resources;
    @Inject DnsQueryHandler handler;

    @BeforeEach
    @AfterEach
    @Transactional
    void resetResolver() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsResolveAllResourcesAndPeers = false;
    }

    private Site site(String suffix, String cidr) {
        Site s = Site.createNew("AliasSite-" + suffix, cidr, null);
        s.persist();
        return s;
    }

    private static ResourceDto.UpsertRequest req(String name, String ip, String dns, boolean flat, List<String> aliases) {
        return new ResourceDto.UpsertRequest(name, ip, null, "computer", dns, flat, null, aliases);
    }

    @Test
    @Transactional
    void aliasesAreStoredLowercased_withoutDuplicatesOrTheMainName() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(x, "10.91.0.0/24");
        Resource r = resources.create(site.id, req("VM-" + x, "10.91.0.5", "main-" + x, false,
                List.of("App-" + x, "app-" + x, "main-" + x, " ")));
        assertThat(r.dnsAliases).containsExactly("app-" + x);
    }

    @Test
    @Transactional
    void aliasConflictingWithAnotherResourcesMainNameOrAlias_isRejected() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(x, "10.92.0.0/24");
        resources.create(site.id, req("A-" + x, "10.92.0.5", "a-" + x, false, List.of("shared-" + x)));

        assertThatThrownBy(() -> resources.create(site.id,
                req("B-" + x, "10.92.0.6", "b-" + x, false, List.of("shared-" + x))))
                .isInstanceOf(WebApplicationException.class);
        assertThatThrownBy(() -> resources.create(site.id,
                req("C-" + x, "10.92.0.7", "c-" + x, false, List.of("a-" + x))))
                .isInstanceOf(WebApplicationException.class);
        assertThatThrownBy(() -> resources.create(site.id,
                req("D-" + x, "10.92.0.8", "shared-" + x, false, null)))
                .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @Transactional
    void update_keepsOwnAliases_andReplacesTheSet() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Site site = site(x, "10.93.0.0/24");
        Resource r = resources.create(site.id, req("A-" + x, "10.93.0.5", "a-" + x, false, List.of("one-" + x, "two-" + x)));

        resources.update(r.id, req("A-" + x, "10.93.0.5", "a-" + x, false, List.of("two-" + x, "three-" + x)));

        assertThat(Resource.<Resource>findById(r.id).dnsAliases).containsExactlyInAnyOrder("two-" + x, "three-" + x);
    }

    @Test
    @Transactional
    void resolver_answersAnAlias_withTheAliasAsFqdn_andSameIp() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        Site site = site(x, "10.94.0.0/24");
        String slug = DnsQueryHandlerAccess.slug(site);
        resources.create(site.id, req("VM-" + x, "10.94.0.5", "main-" + x, false, List.of("wiki-" + x)));

        var answer = handler.resolveForAdminPreview("wiki-" + x + "." + slug + "." + ZONE);

        assertThat(answer).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        var a = (DnsQueryHandler.Resolution.Answer) answer;
        assertThat(a.ip()).isEqualTo("10.94.0.5");
        assertThat(a.fqdn()).isEqualTo("wiki-" + x + "." + slug + "." + ZONE);
    }

    @Test
    @Transactional
    void resolver_answersAFlatAlias_directlyUnderTheZone() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        Site site = site(x, "10.95.0.0/24");
        resources.create(site.id, req("VM-" + x, "10.95.0.5", "main-" + x, true, List.of("blog-" + x)));

        var a = (DnsQueryHandler.Resolution.Answer) handler.resolveForAdminPreview("blog-" + x + "." + ZONE);

        assertThat(a.ip()).isEqualTo("10.95.0.5");
    }
}
