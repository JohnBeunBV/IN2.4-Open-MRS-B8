package nl.avans.communicatiemodule.repository;

import nl.avans.communicatiemodule.domain.OrganisationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganisationConfigRepository extends JpaRepository<OrganisationConfig, UUID> {

    Optional<OrganisationConfig> findByName(String name);

    List<OrganisationConfig> findByActiveTrue();

    boolean existsByName(String name);
}
