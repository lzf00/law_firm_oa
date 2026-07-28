package com.zoro.legaloa.document;

import java.io.IOException;
import java.io.InputStream;

public interface AntivirusScanner {
    ScanResult scan(InputStream content, ScanMetadata metadata) throws IOException;

    String provider();

    default boolean isHealthy() {
        return true;
    }

    record ScanMetadata(String filename, String contentType, long sizeBytes) {}

    record ScanResult(Status status, String signature, String detail) {
        public static ScanResult clean() {
            return new ScanResult(Status.CLEAN, null, null);
        }

        public static ScanResult infected(String signature) {
            return new ScanResult(Status.INFECTED, signature, "Malware signature detected");
        }

        public static ScanResult failed(String detail) {
            return new ScanResult(Status.FAILED, null, detail);
        }
    }

    enum Status {
        CLEAN,
        INFECTED,
        FAILED
    }
}
