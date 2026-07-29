package com.zoro.legaloa.archive;

public final class ArchiveLifecyclePolicy {
    private ArchiveLifecyclePolicy() {}

    public static boolean canEdit(String status) {
        return "OPEN".equals(status);
    }

    public static boolean canClose(String status, int itemCount, int unavailableItemCount) {
        return canEdit(status) && itemCount > 0 && unavailableItemCount == 0;
    }
}
