package com.zoro.legaloa.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class PagePolicy {
    private PagePolicy() {}

    public static PageSpec bounded(
            Integer requestedPage,
            Integer requestedSize,
            String requestedSort,
            Map<String, String> allowedSorts,
            String defaultSort
    ) {
        int page = requestedPage == null ? 1 : requestedPage;
        int size = requestedSize == null ? 30 : requestedSize;
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(
                    "PAGE_BOUNDS_INVALID", "页码必须大于零且每页不得超过 100 条",
                    HttpStatus.BAD_REQUEST
            );
        }
        String sortKey = requestedSort == null || requestedSort.isBlank()
                ? defaultSort : requestedSort;
        String orderBy = allowedSorts.get(sortKey);
        if (orderBy == null) {
            throw new BusinessException(
                    "SORT_NOT_ALLOWED", "排序字段不在允许列表中",
                    HttpStatus.BAD_REQUEST
            );
        }
        return new PageSpec(page, size, (page - 1) * size, sortKey, orderBy);
    }

    public record PageSpec(int page, int size, int offset, String sortKey, String orderBy) {}
}
