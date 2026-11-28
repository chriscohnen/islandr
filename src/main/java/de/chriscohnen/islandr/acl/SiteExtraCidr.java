package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.UUID;

/**
 * A further address range of an external site (site-multi-cidr). The first range stays
 * {@code Site.cidr}; these follow it in {@link #position} order. Read through
 * {@link Site#allCidrs()}, never directly.
 */
@Entity
@Table(name = "site_extra_cidrs")
public class SiteExtraCidr extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "site_id", nullable = false, length = 36)
    public String siteId;

    @Column(name = "cidr", nullable = false, length = 50)
    public String cidr;

    @Column(name = "position", nullable = false)
    public int position;

    public static SiteExtraCidr createNew(String siteId, String cidr, int position) {
        SiteExtraCidr e = new SiteExtraCidr();
        e.id = UUID.randomUUID().toString();
        e.siteId = siteId;
        e.cidr = cidr;
        e.position = position;
        return e;
    }

    public static List<String> cidrsOf(String siteId) {
        return SiteExtraCidr.<SiteExtraCidr>list("siteId = ?1 order by position", siteId)
                .stream().map(e -> e.cidr).toList();
    }
}
