package nl.avans.communicatiemodule.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * JPA AttributeConverter that transparently encrypts/decrypts sensitive string fields
 * (patient_phone, patient_name) using AES-256-GCM before storing in the database.
 *
 * The encryption key must be provided as a Base64-encoded 32-byte value via the
 * DB_ENCRYPTION_KEY environment variable.
 *
 * Format stored in DB: base64(iv) + ":" + base64(ciphertext+authTag)
 */
@Slf4j
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedStringConverter(@Value("${app.security.db-encryption-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "DB_ENCRYPTION_KEY must be a Base64-encoded 32-byte (256-bit) key");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                   + ":" + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt field value", ex);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            String[] parts = dbData.split(":", 2);
            if (parts.length != 2) {
                // Value was stored unencrypted (migration scenario) - return as-is with a warning
                log.warn("Found unencrypted field value in DB - consider re-encrypting existing data");
                return dbData;
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt field value", ex);
        }
    }
}
