package io.fireflysoftware.common.config.core.authority;

import java.util.Collection;
import java.util.List;

public final class InstitutionAuthorityLockOrder {
    private InstitutionAuthorityLockOrder() {}
    public static List<String> statusIds(Collection<String> ids) {
        if(ids==null) throw AuthorityWire.bad();ids.forEach(AuthorityWire::uuid);
        // Canonical hexadecimal strings have the unsigned byte order used by PostgreSQL UUIDs.
        return ids.stream().distinct().sorted().toList();
    }
}
