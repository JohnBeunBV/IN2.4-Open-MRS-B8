package nl.avans.communicatiemodule.repository;

import nl.avans.communicatiemodule.domain.MessageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MessageLogRepository extends JpaRepository<MessageLog, UUID> {

    List<MessageLog> findByOrganisationId(UUID organisationId);

    /** Count total messages for an organisation */
    long countByOrganisationId(UUID organisationId);

    /** Count messages by organisation and status */
    long countByOrganisationIdAndStatus(UUID organisationId, MessageLog.MessageStatus status);

    /** Find expired message logs ready for purging */
    List<MessageLog> findByExpiresAtBefore(Instant cutoff);

    /**
     * Delete message logs where the expiry date has passed.
     * NOTE: @Modifying @Query must return int (or void), not long.
     *       The previous long return type caused a clash with CrudRepository.deleteAll().
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MessageLog m WHERE m.expiresAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageLog m WHERE m.expiresAt < :cutoff")
    int deleteExpiredLogs(@Param("cutoff") Instant cutoff);
}