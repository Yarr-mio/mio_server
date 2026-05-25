package com.mio.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmMessageEncryptorTest {

    @Test
    @DisplayName("AES-GCM 암호화기는 평문을 복호화 가능해야 한다")
    void encryptDecrypt_roundTrip() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        String encodedKey = Base64.getEncoder().encodeToString(key);
        AesGcmMessageEncryptor encryptor = new AesGcmMessageEncryptor(encodedKey, "test-dek");

        byte[] ciphertext = encryptor.encrypt("hello mio".getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = encryptor.decrypt(ciphertext);

        assertThat(ciphertext).isNotEqualTo("hello mio".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo("hello mio");
        assertThat(encryptor.dekId()).isEqualTo("test-dek");
    }

    @Test
    @DisplayName("APP_ENCRYPTION_KEY가 32바이트가 아니면 예외가 발생한다")
    void invalidKeyLength_throws() {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmMessageEncryptor(encodedKey, "test-dek"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
