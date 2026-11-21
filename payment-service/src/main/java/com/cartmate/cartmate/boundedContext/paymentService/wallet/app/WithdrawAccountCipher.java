package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

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
 * 출금 건에 보관하는 계좌번호 암호화 (AES-256-GCM). 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문·태그).
 * 키는 환경 변수(WALLET_ACCOUNT_ENCRYPTION_KEY, Base64 32바이트). 회원 서버 키와 따로 둔다 (한쪽 키가 새어도 다른 쪽은 안전).
 * 운영엔 기본값이 없다. 키를 바꾸면 끝나지 않은 출금 건의 계좌를 풀 수 없다 → 바꾸기 전에 진행 중 출금을 모두 끝낸다.
 */
@Component
public class WithdrawAccountCipher {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public WithdrawAccountCipher(@Value("${custom.wallet.withdraw.account-encryption-key}") String base64Key) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != 32) {
            throw new IllegalStateException("custom.wallet.withdraw.account-encryption-key는 Base64로 인코딩한 32바이트여야 합니다.");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String accountNumber) {
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

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION)) {
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

    // 화면용: 끝 4자리만
    public static String mask(String accountNumber) {
        return "****" + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
    }
}
