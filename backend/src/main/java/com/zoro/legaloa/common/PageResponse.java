package com.zoro.legaloa.common;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(List.copyOf(items), page, size, total, totalPages);
    }
}
