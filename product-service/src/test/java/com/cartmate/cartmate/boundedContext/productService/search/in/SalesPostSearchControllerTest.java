package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchHits;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchSort;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

// 보안 필터와 open-in-view가 꺼진 실제 설정으로 주소를 호출한다. 검색 색인 응답만 목으로 정한다
class SalesPostSearchControllerTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;

    @Autowired MockMvc mvc;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;
    @Autowired SalesPostProductImageRepository salesPostProductImageRepository;

    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        given(salesPostSearchClient.search(any(), any())).willReturn(new SalesPostSearchHits(List.of(), 0));
    }

    @Test
    void 비로그인으로도_검색할_수_있다() throws Exception {
        mvc.perform(get("/api/sales-posts/search").param("q", "이어폰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200-1"))
                .andExpect(jsonPath("$.msg").value("판매글 검색에 성공했습니다."));
    }

    @Test
    void 공백_검색어는_검색어_없음으로_본다() throws Exception {
        mvc.perform(get("/api/sales-posts/search").param("q", "   ")).andExpect(status().isOk());

        ArgumentCaptor<SalesPostSearchCondition> condition = ArgumentCaptor.forClass(SalesPostSearchCondition.class);
        verify(salesPostSearchClient).search(condition.capture(), any(Pageable.class));
        assertThat(condition.getValue().keyword()).isNull();
        assertThat(condition.getValue().sort()).isEqualTo(SalesPostSearchSort.LATEST);
    }

    @Test
    void 최소_가격이_최대_가격보다_크면_400() throws Exception {
        mvc.perform(get("/api/sales-posts/search").param("minPrice", "50000").param("maxPrice", "10000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 상품과_이미지까지_응답에_채운다() throws Exception {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "무선 이어폰", "내용", SalesPostSaleStatus.ON_SALE,
                0L, SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        SalesPostProduct product = salesPostProductRepository.save(new SalesPostProduct(9_400_001L, SELLER_ID, post, 1L,
                "이어폰", "설명", 30_000L, SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        salesPostProductImageRepository.save(new SalesPostProductImage(9_400_101L, SELLER_ID, product, "posts/earphone.jpg",
                1, SalesPostProductStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now()));   // 사진은 ACTIVE만 불러온다
        given(salesPostSearchClient.search(any(), any())).willReturn(new SalesPostSearchHits(List.of(post.getId()), 1));

        mvc.perform(get("/api/sales-posts/search").param("q", "이어폰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(post.getId()))
                .andExpect(jsonPath("$.data.content[0].productList[0].name").value("이어폰"))
                .andExpect(jsonPath("$.data.content[0].productList[0].productImageList[0].imageSeq").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
