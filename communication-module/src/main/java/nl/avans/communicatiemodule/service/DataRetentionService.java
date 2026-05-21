package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Automated data retention and GDPR compliance service.
 * 
 * Handles:
 * 1. Purging encrypted patient data (14 days after appointment)
 * 2. Purging message logs (1 year after creation, for traceability)
 * 3. Audit logging of all purge operations
 * 
 * Compliance Requirements:
 * - Patient and appointment data deleted within 14 days
 * - Audit trails retained for 1 year for legal/invoice purposes
 * - All deletions logged for compliance verification
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    private final AppointmentNotificationRepository appointmentRepository;
    private final MessageLogRepository messageLogRepository;

    /**
     * Scheduled task: Purge patient data from appointments older than 14 days.
     * Runs daily at 2 AM (configurable).
     * 
     * Nullifies encrypted fields:
     * - patientContactEncrypted
     * - patientNameEncrypted
     * - appointmentDetailsEncrypted
     * 
     * Keeps encrypted message logs for 1 year for traceability/invoicing.
     */
    @Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
    @Transactional
    public void purgeExpiredPatientData() {
        log.info("Starting GDPR patient data purge (14-day retention period)...");
        
        Instant cutoff = Instant.now();
        int purgedCount = 0;
        
        try {
            // Find all appointments where expiry date has passed
            var expiredNotifications = appointmentRepository.findByExpiresAtBefore(cutoff);
            
            for (var notification : expiredNotifications) {
                // Clear encrypted PII fields
                notification.setPatientContactEncrypted(null);
                notification.setPatientNameEncrypted(null);
                notification.setAppointmentDetailsEncrypted(null);
                
                // Metadata fields can stay for up to 1 year (audit trail)
                appointmentRepository.save(notification);
                purgedCount++;
            }
            
            log.info("Purged patient data for {} appointments (14-day retention expired)", purgedCount);
        } catch (Exception e) {
            log.error("Error during patient data purge: {}", e.getMessage(), e);
        }
    }

    /**
     * Scheduled task: Delete message logs older than 1 year.
     * Runs daily at 3 AM (configurable).
     * 
     * Retention rationale:
     * - Allows verification of sent messages for 1 year (compliance, disputes)
     * - No PII is stored in message logs (only message IDs and statuses)
     * - Helps with provider reconciliation and invoice verification
     */
    @Scheduled(cron = "0 0 3 * * * ")  // Daily at 3 AM
    @Transactional
    public void purgeExpiredMessageLogs() {
        log.info("Starting message log purge (1-year retention period)...");
        
        Instant cutoff = Instant.now().minusSeconds(365L * 24 * 3600);
        long deletedCount = 0;
        
        try {
            deletedCount = messageLogRepository.deleteByCreatedAtBefore(cutoff);
            log.info("Deleted {} message logs older than 1 year", deletedCount);
        } catch (Exception e) {
            log.error("Error during message log purge: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for on-demand patient data purging (for testing/urgent requests).
     * Purges all patient data regardless of age.
     */
    @Transactional
    public void purgeAllPatientData() {
        log.warn("Manual purge of ALL patient data initiated - this should only be used for testing!");
        
        try {
            var allNotifications = appointmentRepository.findAll();
            for (var notification : allNotifications) {
                notification.setPatientContactEncrypted(null);
                notification.setPatientNameEncrypted(null);
                notification.setAppointmentDetailsEncrypted(null);
                appointmentRepository.save(notification);
            }
            log.warn("Purged ALL patient data from {} records", allNotifications.size());
        } catch (Exception e) {
            log.error("Error during manual patient data purge: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for on-demand message log deletion (for testing).
     * Deletes all message logs.
     */
    @Transactional
    public void purgeAllMessageLogs() {
        log.warn("Manual purge of ALL message logs initiated - this should only be used for testing!");
        
        try {
            long deletedCount = messageLogRepository.count();
            messageLogRepository.deleteAll();
            log.warn("Deleted all {} message logs", deletedCount);
        } catch (Exception e) {
            log.error("Error during manual message log purge: {}", e.getMessage(), e);
        }
    }

    /**
     * Get retention policy summary for compliance reporting.
     */
    public String getRetentionPolicySummary() {
        return """
            Data Retention & Compliance Policy:
            ====================================
            
            Patient Data (PII):
            - Retention Period: 14 days from appointment creation
            - Action: Automatic purging of encrypted patient contact and details
            - Timing: Daily at 2 AM
            - Purpose: GDPR compliance
            
            Message Logs (Non-PII audit trail):
            - Retention Period: 1 year from message creation
            - Contains: Message IDs, status, retry count, provider reference
            - NO direct identifiers or personal data
            - Purpose: Invoice verification, traceability, compliance
            - Action: Automatic deletion after 1 year
            - Timing: Daily at 3 AM
            
            Encryption:
            - Algorithm: AES-256-GCM (authenticated encryption)
            - Transport: TLS 1.3 (enforced at reverse proxy)
            - Key Management: Vault-based, rotated regularly
            
            Audit:
            - All purge operations logged
            - Retention policy available via API (/api/compliance/retention-policy)
            """;
    }
}