package com.zoro.legaloa.document;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FileInspectionPolicy {
    private static final int MAX_ARCHIVE_ENTRIES = 2_000;
    private static final long MAX_ARCHIVE_UNCOMPRESSED_BYTES = 500L * 1024 * 1024;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 200L * 1024 * 1024;
    private static final long MAX_COMPRESSION_RATIO = 100;
    private static final Set<String> EXECUTABLE_ARCHIVE_EXTENSIONS = Set.of(
            ".exe", ".dll", ".com", ".msi", ".jar", ".js", ".vbs", ".cmd",
            ".bat", ".ps1", ".sh", ".app", ".scr"
    );
    private static final Map<String, Set<String>> EXTENSIONS_BY_CONTENT_TYPE = Map.ofEntries(
            Map.entry("application/pdf", Set.of(".pdf")),
            Map.entry("application/msword", Set.of(".doc")),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of(".docx")),
            Map.entry("application/vnd.ms-excel", Set.of(".xls")),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of(".xlsx")),
            Map.entry("image/jpeg", Set.of(".jpg", ".jpeg")),
            Map.entry("image/png", Set.of(".png")),
            Map.entry("text/plain", Set.of(".txt")),
            Map.entry("application/zip", Set.of(".zip"))
    );

    private FileInspectionPolicy() {}

    public static String normalizeFilename(String filename) {
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC)
                .replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("^[. ]+|[. ]+$", "");
        if (normalized.isBlank() || normalized.length() > 240) {
            throw new InspectionException("FILENAME_INVALID", "The filename is empty or too long");
        }
        return normalized;
    }

    public static InspectionResult inspect(
            String filename,
            String declaredContentType,
            long compressedSize,
            InputStream content
    ) throws IOException {
        String normalizedFilename = normalizeFilename(filename);
        String contentType = declaredContentType.toLowerCase(Locale.ROOT).trim();
        String extension = extension(normalizedFilename);
        if (!EXTENSIONS_BY_CONTENT_TYPE.getOrDefault(contentType, Set.of()).contains(extension)) {
            throw new InspectionException(
                    "FILE_EXTENSION_MISMATCH",
                    "The filename extension does not match the declared content type"
            );
        }

        var buffered = new BufferedInputStream(content);
        buffered.mark(32);
        byte[] header = buffered.readNBytes(16);
        buffered.reset();
        String detected = detect(header, contentType);
        if (detected == null) {
            throw new InspectionException(
                    "FILE_SIGNATURE_MISMATCH",
                    "The file signature does not match the declared content type"
            );
        }
        if (isZipBased(contentType)) {
            inspectArchive(buffered, compressedSize, contentType);
        }
        return new InspectionResult(normalizedFilename, detected);
    }

    private static String detect(byte[] header, String declared) {
        if ("application/pdf".equals(declared) && startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if ("image/png".equals(declared) && startsWith(header, new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        })) {
            return "image/png";
        }
        if ("image/jpeg".equals(declared) && header.length >= 3
                && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (isZipBased(declared) && header.length >= 4
                && header[0] == 'P' && header[1] == 'K'
                && (header[2] == 3 || header[2] == 5 || header[2] == 7)
                && (header[3] == 4 || header[3] == 6 || header[3] == 8)) {
            return declared;
        }
        if (Set.of("application/msword", "application/vnd.ms-excel").contains(declared)
                && startsWith(header, new byte[] {
                        (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                        (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
                })) {
            return declared;
        }
        if ("text/plain".equals(declared)) {
            for (byte value : header) {
                if (value == 0) return null;
            }
            return "text/plain";
        }
        return null;
    }

    private static void inspectArchive(
            InputStream content,
            long compressedSize,
            String contentType
    ) throws IOException {
        int entries = 0;
        long totalBytes = 0;
        boolean hasContentTypes = false;
        boolean hasWordRoot = false;
        boolean hasExcelRoot = false;
        try (var zip = new ZipInputStream(content)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ARCHIVE_ENTRIES) {
                    throw new InspectionException("ARCHIVE_LIMIT_EXCEEDED", "The archive contains too many entries");
                }
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.startsWith("/") || name.contains("../")) {
                    throw new InspectionException("ARCHIVE_PATH_INVALID", "The archive contains an unsafe path");
                }
                if (name.endsWith("vbaproject.bin") || name.endsWith(".docm") || name.endsWith(".xlsm")) {
                    throw new InspectionException("MACRO_NOT_ALLOWED", "Macro-enabled Office content is not allowed");
                }
                if (EXECUTABLE_ARCHIVE_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                    throw new InspectionException("ARCHIVE_EXECUTABLE_NOT_ALLOWED", "Executable archive content is not allowed");
                }
                hasContentTypes |= "[content_types].xml".equals(name);
                hasWordRoot |= name.startsWith("word/");
                hasExcelRoot |= name.startsWith("xl/");
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES
                            || totalBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES
                            || (compressedSize > 0 && totalBytes > compressedSize * MAX_COMPRESSION_RATIO)) {
                        throw new InspectionException("ARCHIVE_LIMIT_EXCEEDED", "The archive exceeds safe expansion limits");
                    }
                }
            }
        }
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                && (!hasContentTypes || !hasWordRoot)) {
            throw new InspectionException("FILE_SIGNATURE_MISMATCH", "The file is not a valid DOCX package");
        }
        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(contentType)
                && (!hasContentTypes || !hasExcelRoot)) {
            throw new InspectionException("FILE_SIGNATURE_MISMATCH", "The file is not a valid XLSX package");
        }
    }

    private static boolean isZipBased(String contentType) {
        return "application/zip".equals(contentType)
                || contentType.startsWith("application/vnd.openxmlformats-officedocument");
    }

    private static String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) return false;
        }
        return true;
    }

    public record InspectionResult(String normalizedFilename, String detectedContentType) {}

    public static final class InspectionException extends RuntimeException {
        private final String code;

        public InspectionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
