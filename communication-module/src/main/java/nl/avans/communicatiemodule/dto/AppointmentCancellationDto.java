package nl.avans.communicatiemodule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for appointment cancellation requests.
 * Contains no PII - only confirmation and timestamp.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentCancellationDto {
    private boolean success;
    private String message;
    private String appointmentId;
    private Instant cancelledAt;
}
