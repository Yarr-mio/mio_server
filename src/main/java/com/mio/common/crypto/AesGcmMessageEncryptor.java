package com.mio.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Profile("!local & !test")
public class AesGcmMessageEncryptor implements MessageEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final String dekId;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmMessageEncryptor(
            @Value("${APP_ENCRYPTION_KEY}") String encodedKey,
            @Value("${APP_ENCRYPTION_DEK_ID:app-key-v1}") String dekId) {
        this.secretKey = new SecretKeySpec(decodeKey(encodedKey), ALGORITHM);
        this.dekId = dekId;
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = doCipher(Cipher.ENCRYPT_MODE, iv, plaintext);
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return combined;
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext.length <= IV_LENGTH_BYTES) {
            throw new IllegalStateException("Ciphertext is too short.");
        }
        byte[] iv = Arrays.copyOfRange(ciphertext, 0, IV_LENGTH_BYTES);
        byte[] payload = Arrays.copyOfRange(ciphertext, IV_LENGTH_BYTES, ciphertext.length);
        return doCipher(Cipher.DECRYPT_MODE, iv, payload);
    }

    @Override
    public String dekId() {
        return dekId;
    }

    private byte[] doCipher(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to process message encryption.", e);
        }
    }

    private static byte[] decodeKey(String encodedKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to 32 bytes.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be a valid base64 string.", e);
        }
    }
}
