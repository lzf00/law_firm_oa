package com.zoro.legaloa.admin;

import java.util.Set;

final class AdminPermissionPolicy {
    private static final Set<String> REQUIRED_ADMIN_PERMISSIONS = Set.of(
            "ADMIN_CONSOLE_VIEW", "USER_ACCESS_MANAGE", "ROLE_PERMISSION_MANAGE",
            "AUDIT_VIEW"
    );

    private AdminPermissionPolicy() {}

    static boolean canReplacePermissions(String roleCode, Set<String> permissionCodes) {
        return !"ADMIN".equals(roleCode)
                || permissionCodes.containsAll(REQUIRED_ADMIN_PERMISSIONS);
    }
}
