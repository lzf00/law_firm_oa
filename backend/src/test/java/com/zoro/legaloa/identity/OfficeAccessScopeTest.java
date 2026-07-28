package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficeAccessScopeTest {
    private final UUID shanghai = UUID.randomUUID();
    private final UUID beijing = UUID.randomUUID();

    @Test
    void memberCanOnlyAccessAssignedOffice() {
        OfficeAccessScope scope = new OfficeAccessScope(
                false, shanghai, Set.of(shanghai), Set.of()
        );

        assertThat(scope.canAccess(shanghai)).isTrue();
        assertThat(scope.canAccess(beijing)).isFalse();
        assertThat(scope.canManage(shanghai)).isFalse();
    }

    @Test
    void officeManagerCanManageOnlyManagedOffice() {
        OfficeAccessScope scope = new OfficeAccessScope(
                false, shanghai, Set.of(shanghai, beijing), Set.of(shanghai)
        );

        assertThat(scope.canManage(shanghai)).isTrue();
        assertThat(scope.canManage(beijing)).isFalse();
    }

    @Test
    void globalRoleCanAccessAndManageEveryConfiguredOffice() {
        OfficeAccessScope scope = new OfficeAccessScope(
                true, shanghai, Set.of(shanghai), Set.of()
        );

        assertThat(scope.canAccess(beijing)).isTrue();
        assertThat(scope.canManage(beijing)).isTrue();
    }

    @Test
    void emptyMembershipProducesSafeNonEmptySqlParameter() {
        OfficeAccessScope scope = new OfficeAccessScope(false, null, Set.of(), Set.of());

        assertThat(scope.sqlOfficeIds()).containsExactly(new UUID(0L, 0L));
    }
}
