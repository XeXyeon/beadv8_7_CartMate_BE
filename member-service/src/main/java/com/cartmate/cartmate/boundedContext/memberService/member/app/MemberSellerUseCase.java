package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.exception.MemberErrorCode;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SellerAccountFingerprint;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SellerType;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.SellerRegisterRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.SellerResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.out.AccountVerificationStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.security.JwtProvider;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSellerUseCase {
    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final AccountVerificationStore accountVerificationStore;
    private final DomainEventPublisher eventPublisher;
    private final JwtProvider jwtProvider;

    public record RegisterResult(SellerResponse seller, String accessToken) {
    }

    public Optional<SellerResponse> getMySeller(Long memberId) {
        return sellerRepository.findByMemberId(memberId).map(SellerResponse::from);
    }

    public boolean isBusinessNumberAvailable(String businessNumber) {
        return !sellerRepository.existsByBusinessNumber(businessNumber.trim());
    }

    public RegisterResult register(Long memberId, SellerRegisterRequest req) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (sellerRepository.existsByMemberId(memberId)) {
            throw new ServiceException(MemberErrorCode.SELLER_ALREADY_REGISTERED);
        }

        boolean business = req.sellerType() == SellerType.BUSINESS;
        String businessName = trimToNull(req.businessName());
        String representativeName = trimToNull(req.representativeName());
        String businessNumber = trimToNull(req.businessNumber());

        if (business) {
            if (businessName == null || representativeName == null || businessNumber == null) {
                throw new ServiceException(MemberErrorCode.SELLER_BUSINESS_INFO_REQUIRED);
            }
            if (sellerRepository.existsByBusinessNumber(businessNumber)) {
                throw new ServiceException(MemberErrorCode.SELLER_DUPLICATE_BUSINESS_NUMBER);
            }
        } else {
            // 개인 판매자는 사업자 정보를 저장하지 않음
            businessName = null;
            representativeName = null;
            businessNumber = null;
        }

        String bankName = req.bankName().trim();
        String accountHolder = req.accountHolder().trim();
        String accountNumber = req.accountNumber().trim();

        // 계좌 확인을 통과한 입력값과 같아야 한다. member를 바꾸기 전에 거절한다.
        String fingerprint = SellerAccountFingerprint.of(req.sellerType(), bankName, accountNumber,
                accountHolder, businessName, representativeName, businessNumber);
        if (!accountVerificationStore.isVerified(memberId, fingerprint)) {
            throw new ServiceException(MemberErrorCode.SELLER_ACCOUNT_NOT_VERIFIED);
        }
        if (sellerRepository.existsByBankNameAndAccountNumber(bankName, accountNumber)) {
            throw new ServiceException(MemberErrorCode.SELLER_ACCOUNT_DUPLICATE);
        }

        member.becomeSeller(); // 나이, 상태, 역할 검사

        Seller seller;
        try {
            seller = sellerRepository.saveAndFlush(Seller.create(member, req.sellerType(), businessName,
                    representativeName, businessNumber, req.phoneNumber().trim(), bankName,
                    accountHolder, accountNumber));
        } catch (DataIntegrityViolationException e) {
            throw new ServiceException(MemberErrorCode.SELLER_ALREADY_REGISTERED.resultCode(), "이미 등록된 판매자 정보입니다.");
        }

        // 등록이 성공했을 때만 통과 표시를 지운다. 실패하면 남겨 두어 입력을 고쳐 다시 제출할 수 있다.
        removeVerificationAfterCommit(memberId);

        // 판매자 등록과 같은 트랜잭션에서 Outbox에 저장한다 → payment(정산 판매자), product(판매글 판매자)가 받는다
        eventPublisher.publish(toRegisteredEvent(seller));

        // 역할이 SELLER로 바뀌었으니 새 토큰 발급 (다시 로그인하지 않아도 판매자로 인식, 판매자 ID 포함)
        String accessToken = jwtProvider.createAccessToken(
                member.getId(), member.getEmail(), member.getRole().name(), seller.getId());

        return new RegisterResult(SellerResponse.from(seller), accessToken);
    }

    // 통과 표시는 DB 커밋이 끝난 뒤에 지운다. 커밋 전에 지우면 동기 이벤트 처리나 커밋이 실패해 롤백돼도 표시만 사라진다.
    private void removeVerificationAfterCommit(Long memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            accountVerificationStore.remove(memberId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    accountVerificationStore.remove(memberId);
                } catch (RuntimeException e) {
                    // 등록은 이미 끝났다. 통과 표시는 유효시간이 지나면 사라지므로 경고만 남긴다.
                    log.warn("[계좌 확인] 통과 표시 삭제에 실패했습니다. memberId={}", memberId, e);
                }
            }
        });
    }

    public record RepublishPage(int count, Long lastId) {
    }

    // 이미 등록된 판매자의 등록 이벤트를 Outbox로 다시 발행한다 (서비스 분리 후 결제·상품 복제본에 빠진 판매자 백필).
    // 받는 쪽이 멱등이라 이미 복제된 판매자에게 다시 보내도 안전하다. 한 번에 한 페이지 (Facade가 페이지마다 트랜잭션을 연다)
    RepublishPage republishRegistered(Long afterId, int size) {
        List<Seller> sellers = sellerRepository.findByIdGreaterThanOrderByIdAsc(afterId, Limit.of(size));
        sellers.forEach(seller -> eventPublisher.publish(toRegisteredEvent(seller)));
        return new RepublishPage(sellers.size(), sellers.isEmpty() ? afterId : sellers.getLast().getId());
    }

    // 계좌·사업자번호·전화번호는 넣지 않는다 (다른 서비스가 쓰지 않고, Outbox·Kafka로 퍼지면 안 된다)
    static SellerRegisteredEvent toRegisteredEvent(Seller seller) {
        return new SellerRegisteredEvent(
                seller.getId(),
                seller.getMember().getId(),
                seller.getSellerType().name(),
                seller.getDisplayName(),
                seller.getRepresentativeName(),
                seller.getStatus().name(),
                seller.getCreateDate(),
                seller.getModifyDate()
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}