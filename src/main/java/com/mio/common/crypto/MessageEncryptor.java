package com.mio.common.crypto;

public interface MessageEncryptor {

    byte[] encrypt(byte[] plaintext);

    byte[] decrypt(byte[] ciphertext);

    String dekId();
}
