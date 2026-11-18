package com.cartmate.cartmate.boundedContext.productService.search.in;

import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostSearchUseCase;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchSort;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 판매글 검색. 로그인 없이 쓸 수 있다
@RestController
@RequestMapping("/api/sales-posts")
@RequiredArgsConstructor
public class SalesPostSearchController {

    private static final int MAX_SIZE = 100;

    private final SalesPostSearchUseCase searchUseCase;

    @GetMapping("/search")
    @Operation(operationId = "searchSalesPosts", summary = "판매글 검색")
    public RsData<PageResponse<SelectSalesPostResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Long> categoryIds,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) SalesPostSearchSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var condition = new SalesPostSearchCondition(q, categoryIds, minPrice, maxPrice, sort);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        return new RsData<>("200-1", "판매글 검색에 성공했습니다.", searchUseCase.search(condition, pageable));
    }
}
