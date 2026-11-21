package com.cartmate.cartmate.global.exception;

import com.cartmate.cartmate.global.rsData.RsData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모든 오류 응답이 RsData 모양(resultCode · msg)으로 나가는지.
 * 요청 단계 오류(잘못된 JSON · 타입 불일치 · 필수 파라미터 누락 · 지원하지 않는 메서드)와 처리하지 못한 오류(500)도 포함한다.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("업무 오류(ServiceException)는 지금처럼 그 결과 코드와 문구로 나간다")
    void serviceException() throws Exception {
        mockMvc.perform(get("/test/service"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value("409-TEST_CONFLICT"))
                .andExpect(jsonPath("$.msg").value("테스트 충돌"));
    }

    @Test
    @DisplayName("입력값 검증 실패(@Valid)는 지금처럼 필드별 문구를 모아 400-COMMON_INVALID_INPUT으로 나간다")
    void invalidInput() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.msg").value("name : 이름은 필수입니다."));
    }

    @Test
    @DisplayName("요청 본문 JSON이 깨지면 RsData 모양의 400으로 나간다")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-COMMON_BAD_REQUEST"))
                .andExpect(jsonPath("$.msg").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("쿼리 파라미터 타입이 맞지 않으면 RsData 모양의 400으로 나간다")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/test/enum").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-COMMON_BAD_REQUEST"))
                .andExpect(jsonPath("$.msg").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("필수 쿼리 파라미터가 없으면 RsData 모양의 400으로 나간다")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-COMMON_BAD_REQUEST"));
    }

    @Test
    @DisplayName("지원하지 않는 메서드로 부르면 RsData 모양의 405로 나간다")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(post("/test/enum"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.resultCode").value("405-COMMON_METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.msg").value("지원하지 않는 요청 방식입니다."));
    }

    @Test
    @DisplayName("처리하지 못한 오류는 내부 내용을 싣지 않고 500-COMMON_INTERNAL_ERROR로 나간다")
    void unexpectedError() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.resultCode").value("500-COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.msg").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("권한 예외는 감싸지 않고 다시 던져 Spring Security가 처리하게 한다")
    void accessDeniedIsRethrown() {
        assertThatThrownBy(() -> mockMvc.perform(get("/test/denied")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    enum TestStatus { READY }

    record TestBody(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/service")
        RsData<Void> service() {
            throw new ServiceException("409-TEST_CONFLICT", "테스트 충돌");
        }

        @PostMapping("/test/body")
        RsData<Void> body(@Valid @RequestBody TestBody body) {
            return new RsData<>("200-1", "ok");
        }

        @GetMapping("/test/enum")
        RsData<Void> enumParam(@RequestParam("status") TestStatus status) {
            return new RsData<>("200-1", "ok");
        }

        @GetMapping("/test/required")
        RsData<Void> required(@RequestParam("id") Long id) {
            return new RsData<>("200-1", "ok");
        }

        @GetMapping("/test/boom")
        RsData<Void> boom() {
            throw new IllegalStateException("내부 상태 오류 (응답에 나가면 안 됨)");
        }

        @GetMapping("/test/denied")
        RsData<Void> denied() {
            throw new AccessDeniedException("권한 없음");
        }
    }
}
