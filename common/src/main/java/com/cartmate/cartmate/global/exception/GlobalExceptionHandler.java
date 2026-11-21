package com.cartmate.cartmate.global.exception;

import com.cartmate.cartmate.global.rsData.RsData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

// 모든 오류 응답을 RsData 모양(resultCode · msg)으로 맞춘다. FE는 msg를 그대로 화면에 띄운다.
// Spring MVC가 요청 단계에서 던지는 표준 오류(잘못된 JSON · 파라미터 타입 불일치 · 필수 파라미터 누락 ·
// 지원하지 않는 메서드 등)는 ResponseEntityExceptionHandler가 받아 상태 코드는 그대로 두고 모양만 바꾼다
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<RsData<Void>> handle(ServiceException e) {
        RsData<Void> rsData = e.getRsData();
        return ResponseEntity.status(rsData.getStatusCode()).body(rsData);
    }

    // 처리하지 못한 오류는 500으로 감싸고 원래 내용은 로그로만 남긴다(응답에 내부 정보를 싣지 않는다).
    // 인증 · 권한 예외는 Spring Security가 처리하도록 다시 던진다
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RsData<Void>> handle(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        log.error("처리하지 못한 오류", e);
        return ResponseEntity.status(500)
                .body(new RsData<>("500-COMMON_INTERNAL_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    // 입력값 검증(@Valid) 실패: 필드별 문구를 모아 보여 준다
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(" / "));

        return ResponseEntity.status(400).body(new RsData<>("400-COMMON_INVALID_INPUT", msg));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        int code = status.value();
        return ResponseEntity.status(status).headers(headers).body(new RsData<>(resultCodeOf(code), messageOf(code)));
    }

    private static String resultCodeOf(int status) {
        return switch (status) {
            case 400 -> "400-COMMON_BAD_REQUEST";
            case 404 -> "404-COMMON_NOT_FOUND";
            case 405 -> "405-COMMON_METHOD_NOT_ALLOWED";
            case 406 -> "406-COMMON_NOT_ACCEPTABLE";
            case 415 -> "415-COMMON_UNSUPPORTED_MEDIA_TYPE";
            default -> status + "-COMMON_REQUEST_FAILED";
        };
    }

    private static String messageOf(int status) {
        return switch (status) {
            case 400 -> "요청 형식이 올바르지 않습니다.";
            case 404 -> "요청한 주소를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 방식입니다.";
            case 406 -> "지원하지 않는 응답 형식입니다.";
            case 415 -> "지원하지 않는 요청 본문 형식입니다.";
            default -> status >= 500 ? "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요." : "요청을 처리하지 못했습니다.";
        };
    }
}
