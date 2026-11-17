package com.cartmate.cartmate.boundedContext.productService.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.IntSupplier;

/**
 * 판매글 → 상품 내부 요청 전용 포트.
 *
 * 판매글과 상품은 한 서버(product-service)에 있지만, 나중에 서버를 나눌 수 있게 HTTP로 통신한다(SalesPostApiClient).
 * 내부 요청이 바깥 요청과 같은 포트로 들어오면 같은 Tomcat 스레드 풀을 쓴다. 그러면 판매글 상세 요청이 스레드를
 * 모두 쓰고 각자 내부 요청의 응답을 기다릴 때, 내부 요청을 처리할 스레드가 없어 교착상태가 된다.
 * 그래서 같은 Tomcat에 커넥터를 하나 더 붙여, 내부 요청은 그 커넥터의 스레드 풀로 처리한다.
 *
 * - 추가 커넥터도 기존 커넥터와 같은 애플리케이션(DispatcherServlet)으로 요청을 넘긴다. 컨트롤러는 그대로다.
 * - 이 포트로는 판매글이 부르는 상품 API(/api/products, /api/products/**)만 받는다. 바깥 API를 우회해 부르지 못하게.
 * - 이 포트는 Pod 안(localhost)에서만 쓴다. 쿠버네티스 Service에 열지 않는다.
 * - 서버를 나누면 product.global.internalUrl을 상품 서버 주소로 바꾸고, 이 설정은 지운다.
 */
@Configuration
public class ProductInternalServerConfig {

    static final String ALLOWED_PATH = "/api/products";

    private final Connector connector;

    public ProductInternalServerConfig(
            @Value("${product.internal-server.port}") int port,
            // 내부 요청 스레드 수. 내부 요청은 다른 요청을 기다리지 않고 끝나므로, 모자라면 대기열에서 순서를 기다릴 뿐 막히지 않는다
            @Value("${product.internal-server.max-threads}") int maxThreads) {
        this.connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        this.connector.setPort(port);
        this.connector.setProperty("maxThreads", String.valueOf(maxThreads));
    }

    // 기본 커넥터(server.port)가 붙은 같은 Tomcat Service에 커넥터를 추가한다
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalServerConnector() {
        return factory -> factory.addAdditionalConnectors(connector);
    }

    // 내부 포트로 들어온 요청 중 상품 API가 아닌 것은 404. 보안 필터보다 먼저 거른다
    @Bean
    FilterRegistrationBean<InternalPortPathFilter> internalPortPathFilter() {
        FilterRegistrationBean<InternalPortPathFilter> registration =
                new FilterRegistrationBean<>(new InternalPortPathFilter(connector::getLocalPort));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static class InternalPortPathFilter extends OncePerRequestFilter {

        // 내부 커넥터가 실제로 연 포트. 포트 0(테스트의 임의 포트)도 맞게 비교하려고 설정값이 아니라 이 값을 쓴다. 아직 안 열렸으면 -1
        private final IntSupplier internalPort;

        InternalPortPathFilter(IntSupplier internalPort) {
            this.internalPort = internalPort;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            int internalPort = this.internalPort.getAsInt();
            // 경로는 Tomcat이 디코딩 · 정규화(../ 제거)한 servletPath로 본다. 원문 URI(getRequestURI)는 "/api/products/../admin"처럼 우회될 수 있다
            if (internalPort > 0 && request.getLocalPort() == internalPort && !isAllowed(request.getServletPath())) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(request, response);
        }

        static boolean isAllowed(String path) {
            return path.equals(ALLOWED_PATH) || path.startsWith(ALLOWED_PATH + "/");
        }
    }
}
