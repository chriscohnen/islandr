package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Withdrawing a user's network access, in one place.
 *
 * <p>Disabling a user used to block only their portal/OIDC login while their
 * already-configured WireGuard peers kept working — surprising for anyone
 * locking an account, since "disabled" should mean no network either. The
 * cascade that fixed it lived inline in {@code UserResource}, which made it a
 * rule of the admin REST layer rather than of the domain: three call sites
 * ended up disabling a user's peers, and they had already drifted — two
 * recomputed the ruleset afterwards, the manual one did not.
 *
 * <p>A copied access rule is what issue #83 cost in 0.22.0, where a second copy
 * of the grant SQL had silently stopped seeing the automatic "Everyone" role.
 * This class exists so the next caller — the external API's deprovisioning
 * endpoint — extends a rule instead of reproducing it.
 */
@ApplicationScoped
public class UserAccessService {

    @Inject PeerService peers;
    @Inject RulesetService rulesets;

    /**
     * Disables every peer of {@code userId} that is still up and returns how
     * many were actually switched off.
     *
     * <p>Only live peers count: re-running over a user whose devices are
     * already down reports zero and changes nothing, so a caller can audit on
     * the return value without logging the same withdrawal on every tick.
     *
     * <p>Deliberately one-way. Re-enabling a user does <b>not</b> bring their
     * peers back: some may have been off for unrelated reasons long before the
     * account was locked, and deciding that a device may reconnect stays an
     * explicit, per-peer admin action.
     *
     * <p>The ruleset is recomputed when something changed. Removing the peer
     * from the interface already stops it connecting, so a stale forward rule
     * grants nothing on its own — but leaving the generated ruleset describing
     * peers that are gone is drift, and drift is what makes the next defect
     * hard to see.
     */
    @Transactional
    public int withdrawPeerAccess(String userId) {
        List<Peer> live = Peer.list("userId = ?1 and enabled = true", userId);
        if (live.isEmpty()) return 0;
        for (Peer p : live) peers.setEnabled(p.id, false);
        rulesets.recomputeFromHook();
        return live.size();
    }
}
