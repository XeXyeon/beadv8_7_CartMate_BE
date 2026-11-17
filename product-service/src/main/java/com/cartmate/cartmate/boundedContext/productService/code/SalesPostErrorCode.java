package com.cartmate.cartmate.boundedContext.productService.code;

import com.cartmate.cartmate.global.exception.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum SalesPostErrorCode implements ErrorCode {
    POST_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "POST_UPDATE_FAILED", "게시글 수정에 실패했습니다."),
    // 본인 판매글이 아님. 판매자가 무엇을 하려다 막혔는지 알 수 있게 동작별로 나눈다
    POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "POST_ACCESS_DENIED", "본인 판매글만 수정할 수 있습니다."),
    POST_DELETE_DENIED(HttpStatus.FORBIDDEN, "POST_DELETE_DENIED", "본인 판매글만 삭제할 수 있습니다."),
    POST_SALE_CHANGE_DENIED(HttpStatus.FORBIDDEN, "POST_SALE_CHANGE_DENIED", "본인 판매글만 판매 중지 · 재개할 수 있습니다."),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "게시글을 찾을 수 없습니다."),
    POST_INVALID_INPUT(HttpStatus.BAD_REQUEST, "POST_INVALID_INPUT", "제목과 내용을 입력해 주세요."),
    POST_PENDING_STATUS(HttpStatus.NOT_ACCEPTABLE, "POST_PENDING_STATUS", "이전 수정이 처리 중입니다. 잠시 후 다시 시도해 주세요."),
    POST_RECOVERING_STATUS(HttpStatus.NOT_ACCEPTABLE, "POST_RECOVERING_STATUS", "이전 수정을 되돌리는 중입니다. 잠시 후 다시 시도해 주세요."),
    POST_SALE_PENDING_STATUS(HttpStatus.NOT_ACCEPTABLE, "POST_SALE_PENDING_STATUS", "등록 또는 삭제가 진행 중인 게시글입니다."),
    POST_NOT_RECOVERING_STATUS(HttpStatus.BAD_REQUEST, "POST_NOT_RECOVERING_STATUS", "복구중인 상태가 아닙니다."),
    POST_RECOVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_RECOVERY_NOT_FOUND", "복구 데이터가 존재하지 않습니다."),
    POST_SUSPEND_NOT_ALLOWED(HttpStatus.CONFLICT, "POST_SUSPEND_NOT_ALLOWED", "판매 중이거나 판매자가 내린 판매글만 숨길 수 있습니다."),
    POST_UPDATE_IN_PROGRESS(HttpStatus.CONFLICT, "POST_UPDATE_IN_PROGRESS", "판매자가 판매글을 수정하는 중입니다. 잠시 후 다시 시도해 주세요."),
    POST_RESUME_NOT_ALLOWED(HttpStatus.CONFLICT, "POST_RESUME_NOT_ALLOWED", "관리자가 숨긴 판매글만 해제할 수 있습니다."),
    POST_PAUSE_NOT_ALLOWED(HttpStatus.CONFLICT, "POST_PAUSE_NOT_ALLOWED", "판매 중인 판매글만 판매 중지할 수 있습니다."),
    POST_SELLER_RESUME_NOT_ALLOWED(HttpStatus.CONFLICT, "POST_SELLER_RESUME_NOT_ALLOWED", "판매자가 중지한 판매글만 다시 판매할 수 있습니다."),
    SELLER_TRUST_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "SELLER_TRUST_INVALID", "판매자 신뢰도 정보가 올바르지 않습니다."),

    // 댓글
    COMMENT_LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "COMMENT_LOGIN_REQUIRED", "로그인이 필요합니다."),
    COMMENT_INVALID_CONTENT(HttpStatus.BAD_REQUEST, "COMMENT_INVALID_CONTENT", "댓글 내용을 1~500자로 입력해 주세요."),
    COMMENT_POST_NOT_WRITABLE(HttpStatus.CONFLICT, "COMMENT_POST_NOT_WRITABLE", "판매 중이거나 판매 중지된 판매글에만 댓글을 쓸 수 있습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다."),
    COMMENT_REPLY_TO_REPLY(HttpStatus.BAD_REQUEST, "COMMENT_REPLY_TO_REPLY", "답글에는 답글을 달 수 없습니다."),
    COMMENT_REPLY_DENIED(HttpStatus.FORBIDDEN, "COMMENT_REPLY_DENIED", "판매자와 댓글 작성자만 답글을 달 수 있습니다."),
    COMMENT_EDIT_DENIED(HttpStatus.FORBIDDEN, "COMMENT_EDIT_DENIED", "본인 댓글만 수정할 수 있습니다."),
    COMMENT_DELETE_DENIED(HttpStatus.FORBIDDEN, "COMMENT_DELETE_DENIED", "본인 댓글만 삭제할 수 있습니다."),
    COMMENT_WRITER_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMENT_WRITER_NOT_FOUND", "회원 정보를 찾을 수 없습니다."),

    // 리뷰
    REVIEW_INVALID_RATING(HttpStatus.BAD_REQUEST, "REVIEW_INVALID_RATING", "별점은 1~5점으로 입력해 주세요."),
    REVIEW_INVALID_CONTENT(HttpStatus.BAD_REQUEST, "REVIEW_INVALID_CONTENT", "리뷰 내용을 10~1000자로 입력해 주세요."),
    // 구매 기록이 없거나 본인 것이 아님 (다른 사람의 구매 여부가 드러나지 않게 같은 응답)
    REVIEW_PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_PURCHASE_NOT_FOUND", "리뷰를 쓸 수 있는 구매 내역을 찾을 수 없습니다."),
    REVIEW_NOT_CONFIRMED(HttpStatus.CONFLICT, "REVIEW_NOT_CONFIRMED", "구매확정 후에 리뷰를 쓸 수 있습니다."),
    REVIEW_PERIOD_EXPIRED(HttpStatus.CONFLICT, "REVIEW_PERIOD_EXPIRED", "리뷰 작성 기한(구매확정 후 30일)이 지났습니다."),
    REVIEW_ALREADY_WRITTEN(HttpStatus.CONFLICT, "REVIEW_ALREADY_WRITTEN", "이미 리뷰를 쓴 상품입니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "리뷰를 찾을 수 없습니다."),
    REVIEW_EDIT_DENIED(HttpStatus.FORBIDDEN, "REVIEW_EDIT_DENIED", "본인 리뷰만 수정할 수 있습니다."),
    REVIEW_DELETE_DENIED(HttpStatus.FORBIDDEN, "REVIEW_DELETE_DENIED", "본인 리뷰만 삭제할 수 있습니다."),
    REVIEW_EDIT_PERIOD_EXPIRED(HttpStatus.CONFLICT, "REVIEW_EDIT_PERIOD_EXPIRED", "리뷰 수정 기한(구매확정 후 30일)이 지났습니다."),
    REVIEW_SELLER_REPLY_DENIED(HttpStatus.FORBIDDEN, "REVIEW_SELLER_REPLY_DENIED", "이 판매글의 판매자만 리뷰에 답글을 달 수 있습니다."),
    REVIEW_SELLER_REPLY_ALREADY_EXISTS(HttpStatus.CONFLICT, "REVIEW_SELLER_REPLY_ALREADY_EXISTS", "이미 답글을 단 리뷰입니다."),
    REVIEW_SELLER_REPLY_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_SELLER_REPLY_NOT_FOUND", "판매자 답글을 찾을 수 없습니다."),
    REVIEW_SELLER_REPLY_INVALID_CONTENT(HttpStatus.BAD_REQUEST, "REVIEW_SELLER_REPLY_INVALID_CONTENT", "답글 내용을 1~1000자로 입력해 주세요."),

    // 찜
    WISH_LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "WISH_LOGIN_REQUIRED", "로그인이 필요합니다."),
    // 판매글 상품이 없거나 삭제됨, 또는 판매글이 다른 사용자에게 보이지 않는 상태
    WISH_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "WISH_PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
    WISH_NOT_ALLOWED(HttpStatus.CONFLICT, "WISH_NOT_ALLOWED", "판매 중이거나 판매 중지된 상품만 찜할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    SalesPostErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public String getMsg() {
        return this.message;
    }
}
