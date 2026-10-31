package io.fireflysoftware.common.config.core.authority;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityLockOrderTest {
    @Test void canonicalUuidOrderMatchesPostgresUnsignedBytesInsteadOfJavaSignedLongs() {
        var ids=List.of("ffffffff-ffff-ffff-ffff-ffffffffffff","80000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000001","7fffffff-ffff-ffff-ffff-ffffffffffff");
        assertEquals(List.of("00000000-0000-0000-0000-000000000001","7fffffff-ffff-ffff-ffff-ffffffffffff","80000000-0000-0000-0000-000000000001","ffffffff-ffff-ffff-ffff-ffffffffffff"),InstitutionAuthorityLockOrder.statusIds(ids));
        assertEquals(1,InstitutionAuthorityLockOrder.statusIds(List.of(ids.getFirst(),ids.getFirst())).size());
        assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityLockOrder.statusIds(List.of("00000000-0000-0000-0000-000000000000")));
    }
}
