package com.cartmate.cartmate.boundedContext.productService.search.in;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostReindexUseCase;
import com.cartmate.cartmate.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매글 검색 색인 관리. /api/admin/** 는 보안 설정에서 ADMIN만 접근 가능.
@RestController
@RequestMapping("/api/admin/sales-posts/search-index")
@RequiredArgsConstructor
public class AdminSalesPostSearchIndexController {

    private final SalesPostReindexUseCase reindexUseCase;

    @PostMapping("/reindex")
    @Operation(operationId = "reindexSalesPostSearch", summary = "판매글 검색 색인 다시 만들기")
    public RsData<String> reindex() {
        return new RsData<>("200-1", "판매글 검색 색인을 다시 만들었습니다.", reindexUseCase.reindex());
    }
}
