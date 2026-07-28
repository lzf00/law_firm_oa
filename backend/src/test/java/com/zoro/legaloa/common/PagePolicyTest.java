package com.zoro.legaloa.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PagePolicyTest {
    @Test
    void appliesBoundedDefaults() {
        var page = PagePolicy.bounded(
                null, null, null,
                Map.of("updatedDesc", "updated_at DESC"), "updatedDesc"
        );
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(30);
        assertThat(page.offset()).isZero();
        assertThat(page.orderBy()).isEqualTo("updated_at DESC");
    }

    @Test
    void calculatesOffset() {
        var page = PagePolicy.bounded(
                4, 25, "nameAsc",
                Map.of("nameAsc", "name ASC"), "nameAsc"
        );
        assertThat(page.offset()).isEqualTo(75);
    }

    @Test
    void rejectsOversizedPage() {
        assertThatThrownBy(() -> PagePolicy.bounded(
                1, 101, null, Map.of("id", "id"), "id"
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnapprovedSortExpression() {
        assertThatThrownBy(() -> PagePolicy.bounded(
                1, 30, "dropTable", Map.of("id", "id"), "id"
        )).isInstanceOf(BusinessException.class);
    }
}
