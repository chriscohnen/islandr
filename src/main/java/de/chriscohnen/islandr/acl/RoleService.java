package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.user.User;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class RoleService {

    @PersistenceContext EntityManager em;

    // -- Role CRUD ------------------------------------------------------------

    public List<Role> listAll() {
        return Role.<Role>listAll(Sort.by("name"));
    }

    public Role get(String id) {
        Role r = Role.findById(id);
        if (r == null) throw new NotFoundException("role not found: " + id);
        return r;
    }

    @Transactional
    public Role create(RoleDto.UpsertRequest req) {
        if (Role.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a role named '" + req.name() + "' already exists")
                            .build());
        }
        Role r = Role.createNew(req.name(), req.description());
        r.persist();
        return r;
    }

    @Transactional
    public Role update(String id, RoleDto.UpsertRequest req) {
        Role r = get(id);
        if (!r.name.equals(req.name()) && Role.count("name", req.name()) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("a role named '" + req.name() + "' already exists")
                            .build());
        }
        r.name = req.name();
        r.description = req.description();
        return r;
    }

    @Transactional
    public void delete(String id) {
        Role r = get(id);
        // ON DELETE CASCADE removes user_roles + role_resource_grants rows.
        r.delete();
    }

    // -- Membership (User × Role) --------------------------------------------

    public List<String> memberUserIds(String roleId) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(
                        "SELECT user_id FROM user_roles WHERE role_id = ?1")
                .setParameter(1, roleId)
                .getResultList();
        return ids;
    }

    public List<String> rolesOfUser(String userId) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(
                        "SELECT role_id FROM user_roles WHERE user_id = ?1")
                .setParameter(1, userId)
                .getResultList();
        return ids;
    }

    /** Count map (roleId → memberCount) for the list view. */
    public Map<String, Long> memberCounts() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT role_id, COUNT(*) FROM user_roles GROUP BY role_id")
                .getResultList();
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], ((Number) r[1]).longValue());
        return out;
    }

    /**
     * Replace the full membership set. Returns (added, removed) so the caller
     * can audit one row per change rather than one giant "membership updated"
     * blob — important so "who got SSH access on 2026-04-14" stays answerable.
     */
    public record MembershipDiff(Set<String> added, Set<String> removed) {}

    @Transactional
    public MembershipDiff setMembers(String roleId, Collection<String> wantedUserIds) {
        get(roleId);  // 404 if role gone
        Set<String> want = new LinkedHashSet<>(wantedUserIds == null ? List.of() : wantedUserIds);
        // Verify every userId exists — otherwise we'd silently lose the row
        // to FK violations on insert, or even worse, succeed and leave the
        // role with phantom members if the FK isn't enforced.
        if (!want.isEmpty()) {
            long exists = User.count("id in ?1", want);
            if (exists != want.size()) {
                throw new NotFoundException("one or more user IDs do not exist");
            }
        }
        Set<String> have = new HashSet<>(memberUserIds(roleId));
        Set<String> add = new LinkedHashSet<>(want);
        add.removeAll(have);
        Set<String> remove = new LinkedHashSet<>(have);
        remove.removeAll(want);
        for (String u : add) {
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, u).setParameter(2, roleId).executeUpdate();
        }
        for (String u : remove) {
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = ?1 AND role_id = ?2")
                    .setParameter(1, u).setParameter(2, roleId).executeUpdate();
        }
        return new MembershipDiff(add, remove);
    }

    // -- Grants (Role × Resource) --------------------------------------------

    /**
     * Full matrix snapshot for the UI: every grant with its allPorts flag and
     * the (possibly empty) port-id list. Empty if no grants exist at all.
     */
    public List<RoleDto.GrantCell> matrix() {
        List<RoleResourceGrant> grants = RoleResourceGrant.<RoleResourceGrant>listAll();
        if (grants.isEmpty()) return List.of();
        // Fetch all (grant_id → port_id) tuples once, group in memory.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT grant_id, port_id FROM role_resource_grant_ports")
                .getResultList();
        Map<String, List<String>> portsByGrant = new HashMap<>();
        for (Object[] r : rows) {
            portsByGrant.computeIfAbsent((String) r[0], k -> new ArrayList<>())
                    .add((String) r[1]);
        }
        List<RoleDto.GrantCell> out = new ArrayList<>(grants.size());
        for (RoleResourceGrant g : grants) {
            List<String> portIds = g.allPorts ? List.of()
                    : portsByGrant.getOrDefault(g.id, List.of());
            out.add(new RoleDto.GrantCell(g.roleId, g.resourceId, g.allPorts, portIds));
        }
        return out;
    }

    public record GrantDiff(String roleId, String resourceId, String change,
                            Boolean fromAllPorts, Boolean toAllPorts,
                            List<String> fromPortIds, List<String> toPortIds) {}

    /**
     * Apply a batch of grant changes (the matrix "Anwenden" button). Each
     * input element either creates a grant, updates an existing one, or — if
     * allPorts=false AND portIds is empty — removes the grant entirely (the
     * "∅" tri-state). Returns one diff per actually-changed cell so the caller
     * can audit them one row per cell.
     */
    @Transactional
    public List<GrantDiff> applyMatrix(List<RoleDto.GrantUpdate> updates) {
        List<GrantDiff> diffs = new ArrayList<>();
        for (RoleDto.GrantUpdate u : updates) {
            // Validate refs early — otherwise a bad ID partway through leaves
            // us with a half-applied batch + an opaque FK error.
            if (Role.findById(u.roleId()) == null) {
                throw new NotFoundException("role not found: " + u.roleId());
            }
            Resource res = Resource.findById(u.resourceId());
            if (res == null) {
                throw new NotFoundException("resource not found: " + u.resourceId());
            }
            List<String> wantPortIds = u.allPorts() ? List.of()
                    : (u.portIds() == null ? List.of() : u.portIds());
            if (!u.allPorts() && !wantPortIds.isEmpty()) {
                ResourceService.validatePortsBelongToResource(res.id, wantPortIds);
            }

            RoleResourceGrant g = RoleResourceGrant.findByRoleResource(u.roleId(), u.resourceId());
            boolean wantsNoGrant = !u.allPorts() && wantPortIds.isEmpty();

            if (g == null && wantsNoGrant) {
                continue;  // ∅ -> ∅, nothing to do
            }
            if (g == null) {
                // Create
                g = RoleResourceGrant.createNew(u.roleId(), u.resourceId(), u.allPorts());
                g.persist();
                if (!u.allPorts()) insertPorts(g.id, wantPortIds);
                diffs.add(new GrantDiff(u.roleId(), u.resourceId(), "create",
                        null, u.allPorts(), null, wantPortIds));
                continue;
            }
            // Existing grant.
            List<String> currentPortIds = currentPortIds(g.id);
            if (wantsNoGrant) {
                // Remove
                deletePortsFor(g.id);
                g.delete();
                diffs.add(new GrantDiff(u.roleId(), u.resourceId(), "delete",
                        g.allPorts, null, currentPortIds, null));
                continue;
            }
            boolean changed = false;
            if (g.allPorts != u.allPorts()) {
                g.allPorts = u.allPorts();
                changed = true;
            }
            if (!u.allPorts()) {
                Set<String> want = new LinkedHashSet<>(wantPortIds);
                Set<String> have = new LinkedHashSet<>(currentPortIds);
                if (!want.equals(have)) {
                    deletePortsFor(g.id);
                    insertPorts(g.id, wantPortIds);
                    changed = true;
                }
            } else {
                // Switching to allPorts wipes any limited-port rows.
                if (!currentPortIds.isEmpty()) {
                    deletePortsFor(g.id);
                    changed = true;
                }
            }
            if (changed) {
                diffs.add(new GrantDiff(u.roleId(), u.resourceId(), "update",
                        !u.allPorts() ? null : null,  // verbosity helps when reading audit
                        u.allPorts(), currentPortIds, wantPortIds));
            }
        }
        return diffs;
    }

    private void insertPorts(String grantId, List<String> portIds) {
        for (String pid : portIds) {
            em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                    .setParameter(1, grantId).setParameter(2, pid).executeUpdate();
        }
    }

    private List<String> currentPortIds(String grantId) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(
                        "SELECT port_id FROM role_resource_grant_ports WHERE grant_id = ?1")
                .setParameter(1, grantId)
                .getResultList();
        return ids;
    }

    private void deletePortsFor(String grantId) {
        em.createNativeQuery("DELETE FROM role_resource_grant_ports WHERE grant_id = ?1")
                .setParameter(1, grantId).executeUpdate();
    }

    /** roleId → number of resources granted. */
    public Map<String, Long> grantCounts() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT role_id, COUNT(*) FROM role_resource_grants GROUP BY role_id")
                .getResultList();
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : rows) out.put((String) r[0], ((Number) r[1]).longValue());
        return out;
    }
}
