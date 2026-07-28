package com.zoro.legaloa.document;

import java.io.InputStream;

public interface OcrProvider {
    OcrResult extract(InputStream content, String contentType, String filename);
    String provider();

    record OcrResult(String text, String language, int pageCount) {}
}
