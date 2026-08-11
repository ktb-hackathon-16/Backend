package com.ktb.chatapp.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Spring Security Crypto를 사용한 암호화 유틸리티
 * AES-256-GCM with PBKDF2 key derivation
 */
@Slf4j
@Component
public class EncryptionUtil {

    @Value("${app.encryption.key}")
    private String encryptionKey;
    
    @Value("${app.encryption.salt:defaultSalt123456}")
    private String salt;
    
    private TextEncryptor textEncryptor;
    
    @PostConstruct
    public void init() {
        this.textEncryptor = Encryptors.text(encryptionKey, salt);
    }
    
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }
        return textEncryptor.encrypt(plainText);
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return encryptedText;
        }
        try {
            return textEncryptor.decrypt(encryptedText);
        } catch (IllegalArgumentException e) {
            log.warn("Decryption failed: invalid encrypted text");
            return null;
        }
    }
}
