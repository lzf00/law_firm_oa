package com.zoro.legaloa.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminPermissionPolicyTest {
    private static final Set<String> REQUIRED = Set.of(
            "ADMIN_CONSOLE_VIEW", "USER_ACCESS_MANAGE", "ROLE_PERMISSION_MANAGE",
            "AUDIT_VIEW"
    );

    @Test
    void acceptsAdministratorPermissionsWhenEveryControlPlanePermissionRemains() {
        var permissions = new java.util.HashSet<>(REQUIRED);
        permissions.add("FINANCE_VIEW");

        assertThat(AdminPermissionPolicy.canReplacePermissions(
                "ADMIN", permissions
        )).isTrue();
    }

    @Test
    void rejectsAdministratorPermissionsWhenRoleManagementWouldBeRemoved() {
        var permissions = new java.util.HashSet<>(REQUIRED);
        permissions.remove("ROLE_PERMISSION_MANAGE");

        assertThat(AdminPermissionPolicy.canReplacePermissions(
                "ADMIN", permissions
        )).isFalse();
    }

    @Test
    void allowsNonAdministratorRoleToHaveNoPermissions() {
        assertThat(AdminPermissionPolicy.canReplacePermissions(
                "LAWYER", Set.of()
        )).isTrue();
    }
}
