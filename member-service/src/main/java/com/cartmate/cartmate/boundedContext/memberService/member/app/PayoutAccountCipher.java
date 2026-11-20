package com.cartmate.cartmate.boundedContext.memberService.member.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 출금 계좌번호 암호화 (AES-256-GCM). 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문·태그). {@link OtpSecretCipher}와 같은 형식.
 * 키는 환경 변수(ACCOUNT_ENCRYPTION_KEY, Base64 32바이트). 운영엔 기본값이 없다. 키를 바꾸면 저장된 계좌를 풀 수 없다 → 회원이 다시 등록.
 */
@Component
class PayoutAccountCipher {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    PayoutAccountCipher(@Value("${custom.member.payout-account.encryption-key}") String base64Key) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != 32) {
            throw new IllegalStateException("custom.member.payout-account.encryption-key는 Base64로 인코딩한 32바이트여야 합니다.");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    String encrypt(String accountNumber) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(accountNumber.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호를 암호화하지 못했습니다.", e);
        }
    }

    String decrypt(String stored) {
        if (!stored.startsWith(VERSION)) {
            throw new IllegalStateException("알 수 없는 계좌번호 암호화 형식입니다.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호를 풀지 못했습니다 (키가 바뀌었거나 값이 손상됨).", e);
        }
    }
}
