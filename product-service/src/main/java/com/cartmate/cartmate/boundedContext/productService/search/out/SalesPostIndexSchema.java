package com.cartmate.cartmate.boundedContext.productService.search.out;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 판매글 검색 색인의 이름 규칙과 설정·매핑 파일. */
public final class SalesPostIndexSchema {

    /** 검색과 평소 쓰기가 바라보는 별칭. */
    public static final String ALIAS = "sales-posts";
    /** 재색인 중인 새 색인을 가리키는 별칭. 재색인 동안에는 여기에도 같이 쓴다. */
    public static final String REINDEXING_ALIAS = "sales-posts-reindexing";

    private static final String SETTINGS_FILE = "/search/sales-posts-index.json";
    // 같은 초에 두 번 만들어도 겹치지 않게 밀리초까지 붙인다
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private SalesPostIndexSchema() {
    }

    static String newIndexName() {
        return ALIAS + "-" + LocalDateTime.now(SEOUL).format(SUFFIX);
    }

    static InputStream settingsAndMappings() {
        return SalesPostIndexSchema.class.getResourceAsStream(SETTINGS_FILE);
    }
}
