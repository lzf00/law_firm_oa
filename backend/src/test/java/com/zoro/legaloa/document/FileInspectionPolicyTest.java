package com.zoro.legaloa.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class FileInspectionPolicyTest {
    @Test
    void normalizesPathControlsAndUnicodeFilename() {
        assertThat(FileInspectionPolicy.normalizeFilename("../客户\u202E/ 合同  终稿.pdf "))
                .isEqualTo("合同 终稿.pdf");
    }

    @Test
    void acceptsPdfOnlyWhenExtensionAndSignatureMatch() throws Exception {
        byte[] content = "%PDF-1.7\nsafe".getBytes(StandardCharsets.US_ASCII);
        var result = FileInspectionPolicy.inspect(
                "engagement.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content)
        );
        assertThat(result.detectedContentType()).isEqualTo("application/pdf");

        assertThatThrownBy(() -> FileInspectionPolicy.inspect(
                "engagement.exe", "application/pdf", content.length,
                new ByteArrayInputStream(content)
        ))
                .isInstanceOf(FileInspectionPolicy.InspectionException.class)
                .extracting(exception -> ((FileInspectionPolicy.InspectionException) exception).code())
                .isEqualTo("FILE_EXTENSION_MISMATCH");
    }

    @Test
    void rejectsMacroEnabledContentInsideDocxPackage() throws Exception {
        byte[] archive = zip(
                new Entry("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8)),
                new Entry("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8)),
                new Entry("word/vbaProject.bin", new byte[] {1, 2, 3})
        );
        assertThatThrownBy(() -> FileInspectionPolicy.inspect(
                "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                archive.length,
                new ByteArrayInputStream(archive)
        ))
                .isInstanceOf(FileInspectionPolicy.InspectionException.class)
                .extracting(exception -> ((FileInspectionPolicy.InspectionException) exception).code())
                .isEqualTo("MACRO_NOT_ALLOWED");
    }

    @Test
    void rejectsArchiveExpansionBombs() throws Exception {
        byte[] repeated = new byte[2 * 1024 * 1024];
        byte[] archive = zip(new Entry("large.txt", repeated));
        assertThatThrownBy(() -> FileInspectionPolicy.inspect(
                "evidence.zip", "application/zip", archive.length,
                new ByteArrayInputStream(archive)
        ))
                .isInstanceOf(FileInspectionPolicy.InspectionException.class)
                .extracting(exception -> ((FileInspectionPolicy.InspectionException) exception).code())
                .isEqualTo("ARCHIVE_LIMIT_EXCEEDED");
    }

    private static byte[] zip(Entry... entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private record Entry(String name, byte[] content) {}
}
