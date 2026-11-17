package com.cartmate.cartmate.boundedContext.productService.config;

import com.cartmate.cartmate.boundedContext.productService.config.ProductInternalServerConfig.InternalPortPathFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// 내부 전용 포트로 들어온 요청은 상품 API(/api/products, /api/products/**)만 통과시킨다
class InternalPortPathFilterTest {

    private static final int MAIN_PORT = 8982;
    private static final int INTERNAL_PORT = 9982;

    @Test
    @DisplayName("내부 포트: 상품 API는 통과한다")
    void internalPort_productApi_passes() throws Exception {
        assertThat(filter(INTERNAL_PORT, INTERNAL_PORT, "/api/products/1").getStatus()).isEqualTo(200);
        assertThat(filter(INTERNAL_PORT, INTERNAL_PORT, "/api/products").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("내부 포트: 상품 API가 아니면 404 (이름만 비슷한 경로 포함)")
    void internalPort_otherApi_404() throws Exception {
        assertThat(filter(INTERNAL_PORT, INTERNAL_PORT, "/api/sales-posts/detail/1").getStatus()).isEqualTo(404);
        assertThat(filter(INTERNAL_PORT, INTERNAL_PORT, "/api/products-admin").getStatus()).isEqualTo(404);
        assertThat(filter(INTERNAL_PORT, INTERNAL_PORT, "/actuator/health").getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("바깥 포트: 경로와 상관없이 통과한다")
    void mainPort_anyApi_passes() throws Exception {
        assertThat(filter(INTERNAL_PORT, MAIN_PORT, "/api/sales-posts/detail/1").getStatus()).isEqualTo(200);
        assertThat(filter(INTERNAL_PORT, MAIN_PORT, "/api/products/1").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("내부 포트가 아직 열리지 않았으면(-1) 거르지 않는다")
    void internalPortNotOpened_passes() throws Exception {
        assertThat(filter(-1, MAIN_PORT, "/api/sales-posts/detail/1").getStatus()).isEqualTo(200);
    }

    // internalPort: 내부 커넥터가 연 포트, requestPort: 요청이 들어온 포트. 필터를 통과하면 응답 상태는 기본값 200
    private MockHttpServletResponse filter(int internalPort, int requestPort, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setLocalPort(requestPort);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new InternalPortPathFilter(() -> internalPort).doFilter(request, response, new MockFilterChain());
        return response;
    }
}
