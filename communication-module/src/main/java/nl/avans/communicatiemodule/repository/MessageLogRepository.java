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

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageLog m WHERE m.expiresAt < :cutoff")
    int deleteExpiredLogs(@Param("cutoff") Instant cutoff);
}
