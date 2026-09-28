package com.claimsai.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {

    private Paging() {
    }

    /** Newest first; id as tie-breaker so pages never overlap or skip rows created in the same instant. */
    public static Pageable newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
