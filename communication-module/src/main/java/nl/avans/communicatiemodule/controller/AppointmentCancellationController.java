package nl.avans.communicatiemodule.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.avans.communicatiemodule.dto.AppointmentCancellationDto;
import nl.avans.communicatiemodule.service.AppointmentCancellationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for patient appointment cancellations.
 * 
 * Endpoints:
 * - POST /api/appointments/cancel/{organisationId}/{cancellationToken}
 * - GET /api/appointments/cancel/validate/{cancellationToken}
 * 
 * These endpoints are public (no auth required) - security is via opaque token.
 */
@Slf4j
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
@CrossOrigin  // Allow CORS for patient-facing interfaces
public class AppointmentCancellationController {

    private final AppointmentCancellationService cancellationService;

    /**
     * Process a patient appointment cancellation.
     * 
     * Accessible via link in notification:
     * https://notification-service/api/appointments/cancel/{organisationId}/{token}
     * 
     * Request: GET or POST (both supported for UX flexibility)
     * Response: 200 OK with confirmation
     *          400 Bad Request if token invalid
     *          500 Internal Server Error if OpenMRS API fails
     */
    @PostMapping("/cancel/{organisationId}/{cancellationToken}")
    @GetMapping("/cancel/{organisationId}/{cancellationToken}")
    public ResponseEntity<?> cancelAppointment(
            @PathVariable UUID organisationId,
            @PathVariable String cancellationToken) {

        try {
            AppointmentCancellationDto result = cancellationService.cancelAppointment(
                    organisationId, cancellationToken);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cancellation request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Invalid or expired cancellation link. Please contact your healthcare provider.",
                    "INVALID_TOKEN"
            ));
        } catch (Exception e) {
            log.error("Cancellation processing error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                    "Failed to process cancellation. Please try again or contact support.",
                    "PROCESSING_ERROR"
            ));
        }
    }

    /**
     * Validate a cancellation token before user clicks the link.
     * Useful for checking token validity from patient UI.
     * 
     * Response:
     * - 200 OK if valid: { "valid": true }
     * - 400 Bad Request if invalid: { "valid": false }
     */
    @GetMapping("/cancel/validate/{cancellationToken}")
    public ResponseEntity<?> validateCancellationToken(
            @PathVariable String cancellationToken) {

        boolean isValid = cancellationService.isCancellationTokenValid(cancellationToken);
        return ResponseEntity.ok(new ValidationResponse(isValid));
    }

    // ── Response DTOs ────────────────────────────────────────────────────

    private record ErrorResponse(String message, String code) {}
    private record ValidationResponse(boolean valid) {}
}
