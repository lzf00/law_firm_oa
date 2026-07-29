package com.zoro.legaloa.matter;

public final class ContractLifecyclePolicy {
    private ContractLifecyclePolicy() {}

    public static boolean canEdit(String contractStatus) {
        return "DRAFT".equals(contractStatus) || "REJECTED".equals(contractStatus);
    }

    public static boolean canCreateVersion(String contractStatus) {
        return canEdit(contractStatus);
    }

    public static boolean canFinalize(String contractStatus, String versionStatus) {
        return "APPROVED".equals(contractStatus)
                && ("DRAFT".equals(versionStatus) || "REVIEWED".equals(versionStatus));
    }

    public static boolean canArchiveSignature(
            String contractStatus,
            String versionStatus,
            String signatureStatus
    ) {
        return "APPROVED".equals(contractStatus)
                && "FINAL".equals(versionStatus)
                && ("PENDING".equals(signatureStatus) || "UNSIGNED".equals(signatureStatus));
    }
}
