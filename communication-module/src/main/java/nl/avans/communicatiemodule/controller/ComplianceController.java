package nl.avans.communicatiemodule.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.service.DataRetentionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance and data retention policy endpoints.
 * 
 * These endpoints provide transparency into data handling:
 * - Retention policies (14 days for PII, 1 year for audit logs)
 * - Encryption details (AES-256-GCM)
 * - GDPR compliance information
 * 
 * Accessible to authenticated administrators and audit systems.
 */
@Slf4j
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final DataRetentionService dataRetentionService;

    /**
     * Get data retention and GDPR compliance policy.
     * Public endpoint - available to all users for transparency.
     */
    @GetMapping("/retention-policy")
    public ResponseEntity<String> getRetentionPolicy() {
        return ResponseEntity.ok(dataRetentionService.getRetentionPolicySummary());
    }

    /**
     * Security policy details.
     * Public endpoint.
     */
    @GetMapping("/security-policy")
    public ResponseEntity<SecurityPolicyResponse> getSecurityPolicy() {
        return ResponseEntity.ok(SecurityPolicyResponse.builder()
                .encryption("AES-256-GCM for data at rest")
                .transport("TLS 1.3 for data in transit")
                .keyManagement("Vault-managed with periodic rotation")
                .patientDataRetentionDays(14)
                .auditLogRetentionDays(365)
                .build());
    }

    /**
     * Encryption and transport security details.
     * Public endpoint.
     */
    @GetMapping("/encryption-details")
    public ResponseEntity<EncryptionDetailsResponse> getEncryptionDetails() {
        return ResponseEntity.ok(EncryptionDetailsResponse.builder()
                .algorithm("AES-256-GCM")
                .keySize(256)
                .mode("Galois/Counter Mode (authenticated encryption)")
                .transportProtocol("TLS 1.3")
                .keyDerivation("Vault-managed master keys")
                .build());
    }

    /**
     * FHIR HL7 compliance details.
     * Public endpoint.
     */
    @GetMapping("/hl7-compliance")
    public ResponseEntity<HL7ComplianceResponse> getHL7Compliance() {
        return ResponseEntity.ok(HL7ComplianceResponse.builder()
                .standard("HL7 FHIR R4")
                .messageProcessing(new String[]{
                        "Receipt - Validate incoming FHIR resources",
                        "Validation - Verify FHIR schema compliance",
                        "ACK - Send HTTP 200 for successful processing",
                        "Transformation - Convert to provider-specific format",
                        "Queueing - Queue via RabbitMQ",
                        "Retry - Exponential backoff with jitter"
                })
                .supportedResources(new String[]{"Appointment", "Patient", "Location"})
                .build());
    }

    // ── Response DTOs ────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SecurityPolicyResponse {
        private String encryption;
        private String transport;
        private String keyManagement;
        private int patientDataRetentionDays;
        private int auditLogRetentionDays;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EncryptionDetailsResponse {
        private String algorithm;
        private int keySize;
        private String mode;
        private String transportProtocol;
        private String keyDerivation;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HL7ComplianceResponse {
        private String standard;
        private String[] messageProcessing;
        private String[] supportedResources;
    }
}
