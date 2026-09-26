package de.chriscohnen.islandr.acl;

/**
 * The one list of valid {@link Resource#type} values, shared by
 * {@link ResourceDto} and {@link de.chriscohnen.islandr.discovery.DiscoveryDto}
 * so the two {@code @Pattern} regexes cannot drift apart the way they had
 * before this class existed — one copy per record, kept in sync by hand.
 *
 * <p>This is the app-side gate now, not the database's: since
 * {@code db.migration.V80__resource_type_open_set}, {@code resources.type}
 * carries no CHECK constraint, so adding a type here (plus an icon in
 * {@code Icons.js}, a label in {@code i18n.js}, and the selector lists in the
 * frontend views) needs no migration.
 *
 * <p>{@code @Pattern.regexp} requires a compile-time constant, which a
 * {@code public static final String} literal satisfies — string
 * concatenation of such constants stays a compile-time constant too, so
 * {@link ResourceDto} and {@code DiscoveryDto} can each wrap {@link #PATTERN}
 * in their own anchors and optionality without duplicating the list itself.
 */
public final class ResourceTypes {

    private ResourceTypes() {}

    public static final String PATTERN =
            "computer|router|accesspoint|printer|nas|camera|iot|virt-host|rackserver|kvm|management|other";

    public static final String LIST_FOR_MESSAGE =
            "computer, router, accesspoint, printer, nas, camera, iot, virt-host, rackserver, kvm, management, other";
}
