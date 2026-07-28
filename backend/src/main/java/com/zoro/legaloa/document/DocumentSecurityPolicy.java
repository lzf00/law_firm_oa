package com.zoro.legaloa.document;

final class DocumentSecurityPolicy {
    static final int WINDOW_SECONDS = 300;
    static final int WARNING_THRESHOLD = 10;
    static final int BLOCK_THRESHOLD = 30;

    private DocumentSecurityPolicy() {}

    static Decision evaluate(int recentAllowedDownloads) {
        if (recentAllowedDownloads >= BLOCK_THRESHOLD) {
            return Decision.BLOCK;
        }
        if (recentAllowedDownloads + 1 == WARNING_THRESHOLD) {
            return Decision.ALLOW_AND_WARN;
        }
        return Decision.ALLOW;
    }

    enum Decision {
        ALLOW,
        ALLOW_AND_WARN,
        BLOCK
    }
}
