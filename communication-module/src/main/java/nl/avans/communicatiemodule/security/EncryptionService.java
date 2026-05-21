package nl.avans.communicatiemodule.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256 GCM encryption service for sensitive data.
 * Uses Vault-managed keys for key derivation.
 * All encryption/decryption uses AES-256-GCM (authenticated encryption).
 * 
 * - Sensitive data stored encrypted: credentials, tokens, appointment details
 * - Encryption key stored in Vault, never in database
 * - Each field is encrypted independently with unique IV/nonce
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionService {

    private final VaultTemplate vaultTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;     // 96 bits for GCM
    private static final int GCM_TAG_LENGTH = 128;   // 128 bits for authentication tag
    private static final int KEY_SIZE = 256;         // AES-256

    /**
     * Encrypt sensitive data using AES-256-GCM.
     * Returns: BASE64(IV + CIPHERTEXT + TAG)
     * 
     * The IV (nonce) is generated fresh for each encryption, ensuring that
     * encrypting the same plaintext multiple times produces different ciphertexts.
     */
    public String encrypt(String plaintext, String keyPath) throws Exception {
        SecretKey key = getKeyFromVault(keyPath);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = cipher.doFinal(plainBytes);
        
        // Combine IV + ciphertext + tag
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherBytes.length);
        buffer.put(iv);
        buffer.put(cipherBytes);
        
        byte[] combined = buffer.array();
        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Decrypt AES-256-GCM encrypted data.
     * Expects: BASE64(IV + CIPHERTEXT + TAG)
     */
    public String decrypt(String ciphertext, String keyPath) throws Exception {
        SecretKey key = getKeyFromVault(keyPath);
        
        byte[] combined = Base64.getDecoder().decode(ciphertext);
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        
        byte[] cipherBytes = new byte[buffer.remaining()];
        buffer.get(cipherBytes);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    /**
     * Retrieve the encryption key from Vault.
     * Key is cached in Vault and rotated periodically.
     * Path example: "secret/communicatiemodule/keys/master-key"
     */
    private SecretKey getKeyFromVault(String keyPath) throws Exception {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> secret = 
                (java.util.Map<String, Object>) vaultTemplate.read(keyPath).getData();
            
            String keyString = (String) secret.get("key");
            if (keyString == null) {
                throw new IllegalArgumentException("Key not found in Vault at " + keyPath);
            }
            
            byte[] decodedKey = Base64.getDecoder().decode(keyString);
            return new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
        } catch (Exception e) {
            log.error("Failed to retrieve encryption key from Vault at {}: {}", keyPath, e.getMessage());
            throw e;
        }
    }

    /**
     * Generate and store a new AES-256 key in Vault.
     * Called during organisation setup.
     */
    public String generateAndStoreKey(String keyPath) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(KEY_SIZE, secureRandom);
        SecretKey key = keyGen.generateKey();
        
        String encodedKey = Base64.getEncoder().encodeToString(key.getEncoded());
        
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("key", encodedKey);
        
        vaultTemplate.write(keyPath, data);
        log.info("Stored AES-256 key in Vault at {}", keyPath);
        
        return keyPath;
    }
}
