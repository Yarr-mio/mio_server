package com.mio.common.crypto;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬 개발용 pass-through 암호화기. 다음 스프린트에 AES-256 DEK 구현으로 교체 */
@Component
@Profile({"local", "test"})
public class StubMessageEncryptor implements MessageEncryptor {

    private static final String STUB_DEK_ID = "stub-dek-v1";

    @Override
    public byte[] encrypt(byte[] plaintext) {
        return plaintext;
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        return ciphertext;
    }

    @Override
    public String dekId() {
        return STUB_DEK_ID;
    }
}
