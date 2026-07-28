package com.zoro.legaloa.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record OfficeAccessScope(
        boolean globalAccess,
        UUID primaryOfficeId,
        Set<UUID> memberOfficeIds,
        Set<UUID> managedOfficeIds
) {
    private static final UUID NO_OFFICE = new UUID(0L, 0L);

    public OfficeAccessScope {
        memberOfficeIds = Set.copyOf(memberOfficeIds == null ? Set.of() : memberOfficeIds);
        managedOfficeIds = Set.copyOf(managedOfficeIds == null ? Set.of() : managedOfficeIds);
    }

    public boolean canAccess(UUID officeId) {
        return officeId != null && (globalAccess || memberOfficeIds.contains(officeId));
    }

    public boolean canManage(UUID officeId) {
        return officeId != null && (globalAccess || managedOfficeIds.contains(officeId));
    }

    public List<UUID> sqlOfficeIds() {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(memberOfficeIds);
        if (primaryOfficeId != null) {
            ids.add(primaryOfficeId);
        }
        return ids.isEmpty() ? List.of(NO_OFFICE) : List.copyOf(ids);
    }

    public List<UUID> sqlManagedOfficeIds() {
        return managedOfficeIds.isEmpty()
                ? List.of(NO_OFFICE) : List.copyOf(managedOfficeIds);
    }
}
