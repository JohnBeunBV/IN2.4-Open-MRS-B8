package nl.avans.communicatiemodule.domain;

public enum NotificationStatus {
    PENDING,       // Appointment received, notifications not yet sent
    PARTIAL,       // 24h sent, 1h not yet sent
    COMPLETED,     // Both notifications sent
    CANCELLED,     // Appointment was cancelled
    RETRYING,      // Failed but retrying
    FAILED         // All retries exhausted
}
