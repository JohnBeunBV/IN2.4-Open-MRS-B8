package nl.avans.communicatiemodule.repository;

import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentNotificationRepository extends JpaRepository<AppointmentNotification, UUID> {

    Optional<AppointmentNotification> findByFhirAppointmentId(String fhirAppointmentId);

    /** Find 24h notifications that are due now and not yet sent */
    @Query("SELECT a FROM AppointmentNotification a " +
           "WHERE a.sent24h = false " +
           "  AND a.notifyAt24h <= :now " +
           "  AND a.status NOT IN ('CANCELLED','COMPLETED','FAILED')")
    List<AppointmentNotification> findDue24hNotifications(@Param("now") Instant now);

    /** Find 1h notifications that are due now and not yet sent */
    @Query("SELECT a FROM AppointmentNotification a " +
           "WHERE a.sent1h = false " +
           "  AND a.notifyAt1h <= :now " +
           "  AND a.status NOT IN ('CANCELLED','COMPLETED','FAILED')")
    List<AppointmentNotification> findDue1hNotifications(@Param("now") Instant now);

    /** GDPR cleanup: nullify patient PII where the expiry has passed */
    @Modifying
    @Transactional
    @Query("UPDATE AppointmentNotification a " +
           "SET a.patientPhone = '[PURGED]', a.patientName = '[PURGED]' " +
           "WHERE a.expiresAt < :now " +
           "  AND a.patientPhone <> '[PURGED]'")
    int purgeExpiredPatientData(@Param("now") Instant now);

    /** Delete old completed/cancelled records beyond the 14-day grace period */
    @Modifying
    @Transactional
    @Query("DELETE FROM AppointmentNotification a WHERE a.expiresAt < :cutoff")
    int deleteExpiredRecords(@Param("cutoff") Instant cutoff);
}
