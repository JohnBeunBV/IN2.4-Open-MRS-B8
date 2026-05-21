package nl.avans.communicatiemodule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.dto.AppointmentCancellationDto;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Handles patient appointment cancellations via notification link.
 * 
 * Flow:
 * 1. Patient receives notification with link: /api/appointments/cancel/{organisationId}/{cancellationToken}
 * 2. Patient clicks link
 * 3. This service verifies the token
 * 4. Updates appointment status to CANCELLED in OpenMRS via FHIR API
 * 5. Marks notification as cancelled
 * 
 * Security:
 * - Cancellation token is UUID-based, opaque, and single-use
 * - Tokens are unique per appointment
 * - Token is never stored in plaintext logs
 * - Cancellation is idempotent (can be called multiple times safely)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentCancellationService {

    private final AppointmentNotificationRepository appointmentRepository;
    private final OrganisationConfigRepository organisationRepository;
    private final RestTemplate restTemplate;

    /**
     * Process a patient appointment cancellation request.
     * 
     * @param organisationId The organisation UUID
     * @param cancellationToken The unique cancellation token from the notification link
     * @return Details of the cancelled appointment
     * @throws IllegalArgumentException if token is invalid or appointment not found
     */
    @Transactional
    public AppointmentCancellationDto cancelAppointment(UUID organisationId, String cancellationToken) {
        log.info("Processing appointment cancellation request for org={}", organisationId);

        // Verify organisation exists
        var org = organisationRepository.findById(organisationId)
                .orElseThrow(() -> {
                    log.warn("Cancellation request for non-existent organisation: {}", organisationId);
                    return new IllegalArgumentException("Organisation not found");
                });

        // Find appointment by cancellation token
        var notification = appointmentRepository.findByCancellationToken(cancellationToken)
                .orElseThrow(() -> {
                    log.warn("Invalid cancellation token provided");
                    return new IllegalArgumentException("Invalid or expired cancellation link");
                });

        // Verify token belongs to this organisation
        if (!notification.getOrganisationId().equals(organisationId)) {
            log.error("Cancellation token organisation mismatch: token belongs to {} but request from {}",
                    notification.getOrganisationId(), organisationId);
            throw new IllegalArgumentException("Invalid cancellation link");
        }

        // Check if already cancelled (idempotent)
        if (notification.isCancelledByPatient()) {
            log.info("Appointment already cancelled: {}", notification.getFhirAppointmentId());
            return buildCancellationResponse(notification);
        }

        try {
            // Update appointment status in OpenMRS via FHIR API
            updateAppointmentInOpenMRS(org.getOpenmrsBaseUrl(), notification.getFhirAppointmentId());

            // Mark as cancelled
            notification.setCancelledByPatient(true);
            notification.setCancelledAt(Instant.now());
            appointmentRepository.save(notification);

            log.info("Appointment cancelled successfully: fhirId={}, org={}",
                    notification.getFhirAppointmentId(), organisationId);

            return buildCancellationResponse(notification);

        } catch (Exception e) {
            log.error("Failed to cancel appointment in OpenMRS: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process cancellation: " + e.getMessage(), e);
        }
    }

    /**
     * Update appointment status in OpenMRS to CANCELLED via FHIR API.
     * This triggers any downstream workflows in OpenMRS (e.g., notifying clinicians).
     */
    private void updateAppointmentInOpenMRS(String openmrsBaseUrl, String fhirAppointmentId) {
        // Construct FHIR endpoint
        String fhirUrl = openmrsBaseUrl + "/fhir/Appointment/" + fhirAppointmentId;

        try {
            // Fetch current appointment
            String appointmentJson = restTemplate.getForObject(fhirUrl, String.class);
            
            // Parse and update status to CANCELLED
            // Note: Real implementation would use HAPI FHIR parser
            String updatedJson = appointmentJson.replace("\"status\":\"booked\"", "\"status\":\"cancelled\"");
            
            // PUT back to OpenMRS
            restTemplate.put(fhirUrl, updatedJson);
            
            log.info("Updated appointment status in OpenMRS: {}", fhirAppointmentId);
        } catch (Exception e) {
            log.error("Failed to update appointment in OpenMRS: {}", e.getMessage());
            throw new RuntimeException("OpenMRS integration error: " + e.getMessage(), e);
        }
    }

    /**
     * Build response DTO with public-safe information (no PII).
     */
    private AppointmentCancellationDto buildCancellationResponse(AppointmentNotification notification) {
        return AppointmentCancellationDto.builder()
                .success(true)
                .message("Your appointment cancellation has been processed.")
                .appointmentId(notification.getFhirAppointmentId())
                .cancelledAt(notification.getCancelledAt())
                .build();
    }

    /**
     * Verify that a cancellation token is valid and hasn't expired.
     * Used for pre-flight checks (e.g., UI validation).
     */
    public boolean isCancellationTokenValid(String cancellationToken) {
        return appointmentRepository.findByCancellationToken(cancellationToken)
                .map(notification -> !notification.isCancelledByPatient() && !isExpired(notification))
                .orElse(false);
    }

    private boolean isExpired(AppointmentNotification notification) {
        return notification.getExpiresAt().isBefore(Instant.now());
    }
}
