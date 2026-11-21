package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSnapshot;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.code.ProductErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.ProductStatusChangeRequest;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * 판매글 → 상품 HTTP 호출 구현 (product.client=http).
 *
 * <p>지금은 판매글과 상품이 같은 서버라 기본값은 직접 호출(LocalSalesPostProductClient)이다. 이 구현은 서버 분리 때 쓴다.
 * <ul>
 *   <li>서버 분리 시: product.client를 http로 바꾸고(k8s/product-service.yaml의 env에 PRODUCT_CLIENT: http),
 *       PRODUCT_URL을 상품 서버 주소로 바꾼다. 이 클래스가 부르는 상품 API(등록 · 수정 · 복구 · 수정 끝 · 상태 변경)는
 *       지금 로그인 없이 열려 있어(/api/products/**) 분리 전에 내부 호출 키(/internal/** 방식) 등으로 막아야 한다</li>
 *   <li>같은 서버에서 http로 돌리면 내부 전용 포트(ProductInternalServerConfig, 9982)로 부른다. 교착상태를 막는 포트라 같은 설정으로 같이 켜진다</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "product.client", havingValue = "http")
public class SalesPostApiClient implements SalesPostProductClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;

    public SalesPostApiClient(@Value("${product.global.internalUrl}") String internalUrl) {
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .baseUrl(internalUrl + "/api/products")
                .build();
    }

    private static ClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    // product가 저장한 상품(사진 · 옵션 포함)을 돌려받는다. post는 이걸로 마무리 트랜잭션에서 복제본을 만든다
    @Override
    public List<SyncProduct> registerProducts(CreateProductRequest request, List<MultipartFile> images) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("request", request, MediaType.APPLICATION_JSON);   // file은 빠진 채로 JSON

        if (images != null) {
            images.forEach(file -> body.part("images", file.getResource()));
        }

        List<SyncProduct> saved = exchange(
                restClient.post()
                        .uri("")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body.build()),
                new ParameterizedTypeReference<>() {},
                ProductErrorCode.PRODUCT_CREATE_INVALID,
                ProductErrorCode.PRODUCT_CREATE_FAILED);
        return saved == null ? List.of() : saved;
    }

    // 판매글 단위로 상품 판매 상태 변경 (판매자 내림·재개, 관리자 숨김·해제)
    @Override
    public void changeProductStatus(Long postId, SalesPostProductStatus status) {
        exchange(
                restClient.patch()
                        .uri("/{postId}/status", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ProductStatusChangeRequest(status)),
                new ParameterizedTypeReference<InternalResponse<Object>>() {},
                ProductErrorCode.PRODUCT_UPDATE_INVALID,
                ProductErrorCode.PRODUCT_UPDATE_FAILED);
    }

    // product가 수정한 상품(사진 · 옵션 포함)을 돌려받는다. post는 이걸로 수정 마무리 트랜잭션에서 복제본을 갱신한다
    @Override
    public List<SyncProduct> updateProducts(UpdateSalesPostRequest request, List<MultipartFile> images) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("request", request, MediaType.APPLICATION_JSON);   // file은 빠진 채로 JSON

        if (images != null) {
            images.forEach(file -> body.part("images", file.getResource()));
        }

        List<SyncProduct> updated = exchange(
                restClient.patch()
                        .uri("")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body.build()),
                new ParameterizedTypeReference<>() {},
                ProductErrorCode.PRODUCT_UPDATE_INVALID,
                ProductErrorCode.PRODUCT_UPDATE_FAILED);
        return updated == null ? List.of() : updated;
    }

    // product를 부르고 data를 돌려준다.
    // - 4xx: product가 요청을 거절하고 자기 쪽을 되돌린 것. product가 보낸 오류 코드 · 메시지를 그대로 담아
    //   ProductRequestRejectedException으로 던진다(판매자가 "재고를 선점 수량보다 적게" 같은 진짜 이유를 보게).
    //   본문을 읽지 못하면 rejected 코드로 대신한다
    // - 5xx: product 내부 오류. 내부 메시지를 판매자에게 보이지 않도록 failed 코드로 바꾼다
    // - 연결 실패 · 타임아웃: PRODUCT_CONNECT_FAILED (product가 처리했는지 알 수 없음)
    private <T> T exchange(RestClient.RequestHeadersSpec<?> spec, ParameterizedTypeReference<InternalResponse<T>> type,
                           ProductErrorCode rejected, ProductErrorCode failed) {
        try {
            return spec.exchange((request, response) -> {
                if (response.getStatusCode().is2xxSuccessful()) {
                    InternalResponse<T> body = response.bodyTo(type);
                    return body == null ? null : body.data();
                }
                if (response.getStatusCode().is4xxClientError()) {
                    InternalResponse<T> body = readErrorBody(response, type);
                    if (body == null || body.resultCode() == null || body.msg() == null) {
                        throw new ProductRequestRejectedException(rejected.resultCode(), rejected.getMsg());
                    }
                    throw new ProductRequestRejectedException(body.resultCode(), body.msg());
                }
                throw new ServiceException(failed);
            });
        } catch (ResourceAccessException e) {   // 타임아웃, 연결 실패
            throw new ServiceException(ProductErrorCode.PRODUCT_CONNECT_FAILED);
        }
    }

    // 오류 응답이 RsData 모양이 아닐 수 있다(보안 필터 · 게이트웨이 오류 페이지 등). 읽지 못하면 null
    private static <T> InternalResponse<T> readErrorBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                                         ParameterizedTypeReference<InternalResponse<T>> type) {
        try {
            return response.bodyTo(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * product가 4xx로 요청을 거절함. product는 자기 트랜잭션을 되돌린 뒤라 "확실한 실패"로 다룰 수 있다
     * (판매글 수정은 이 예외면 복구 없이 상태만 되돌린다). 코드 · 메시지는 product가 보낸 그대로다.
     */
    public static class ProductRequestRejectedException extends ServiceException {
        public ProductRequestRejectedException(String resultCode, String msg) {
            super(resultCode, msg);
        }
    }

    @Override
    public List<SelectProductResponse> getProduct(Long postId) {
        try {
            // RsData는 응답을 만드는 용도라 JSON에서 다시 만들 수 없다(final 필드, 생성자 표시 없음). 읽을 때는 같은 모양의 record로 읽는다
            InternalResponse<List<SelectProductResponse>> response = restClient.get()
                    .uri("/{postId}", postId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ServiceException(ProductErrorCode.PRODUCT_SELECT_INVALID);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ServiceException(ProductErrorCode.PRODUCT_SELECT_FAILED);
                    })
                    .body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response.data();
        } catch (ResourceAccessException e) {   // 타임아웃, 연결 실패
            throw new ServiceException(ProductErrorCode.PRODUCT_CONNECT_FAILED);
        }
    }

    // 판매글 수정 끝: 수정 중(UPDATING)인 상품을 스냅샷의 수정 전 상태로 되돌리게 한다.
    // 실패하면(어떤 이유든) 수정 실패로 보고 post가 스냅샷 복구를 한다 → 복구도 상태를 되돌린다
    @Override
    public void completeProductUpdate(Long postId, SalesPostSnapshot snapshot) {
        try {
            restClient.put()
                    .uri("/{postId}/update-complete", postId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SalesPostProductMapper.toCompleteRequest(snapshot))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ServiceException(ProductErrorCode.PRODUCT_UPDATE_FAILED);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {   // 타임아웃, 연결 실패
            throw new ServiceException(ProductErrorCode.PRODUCT_CONNECT_FAILED);
        }
    }

    // product가 되돌린 상품을 돌려받는다. post는 이걸로 복구 성공 트랜잭션에서 복제본도 되돌린다
    @Override
    public List<SyncProduct> restoreProducts(Long postId, SalesPostSnapshot snapshot) {
        var request = SalesPostProductMapper.toRestoreRequest(postId, snapshot);   // 스냅샷이 없으면 IllegalStateException
        try {
            InternalResponse<List<SyncProduct>> response = restClient.put()
                    .uri("/{postId}/restore", postId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ServiceException(ProductErrorCode.PRODUCT_RESTORE_INVALID);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ServiceException(ProductErrorCode.PRODUCT_RESTORE_FAILED);
                    })
                    .body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response.data();
        } catch (ResourceAccessException e) {   // 타임아웃, 연결 실패
            throw new ServiceException(ProductErrorCode.PRODUCT_CONNECT_FAILED);
        }
    }
    // 스냅샷 → 복구 · 수정 끝 요청 변환은 직접 호출 구현과 같이 쓰려고 SalesPostProductMapper로 옮겼다
}
