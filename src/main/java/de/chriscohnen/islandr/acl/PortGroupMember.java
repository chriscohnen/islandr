package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * One {@code (port, transport, protocol, label)} tuple inside a
 * {@link PortGroup}. Shape mirrors {@link ResourcePort} so we can just copy
 * the values when applying a group.
 */
@Entity
@Table(name = "port_group_members")
public class PortGroupMember extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "port_group_id", nullable = false, length = 36)
    public String portGroupId;

    @Min(1) @Max(65535)
    @Column(name = "port", nullable = false)
    public int port;

    @NotBlank
    @Pattern(regexp = "^(tcp|udp)$", message = "transport must be 'tcp' or 'udp'")
    @Column(name = "transport", nullable = false, length = 8)
    public String transport;

    @NotBlank
    @Column(name = "protocol", nullable = false, length = 32)
    public String protocol;

    @Column(name = "label", length = 255)
    public String label;

    public static PortGroupMember createNew(String portGroupId, int port, String transport,
                                            String protocol, String label) {
        PortGroupMember m = new PortGroupMember();
        m.id = UUID.randomUUID().toString();
        m.portGroupId = portGroupId;
        m.port = port;
        m.transport = transport;
        m.protocol = protocol;
        m.label = label;
        return m;
    }
}
