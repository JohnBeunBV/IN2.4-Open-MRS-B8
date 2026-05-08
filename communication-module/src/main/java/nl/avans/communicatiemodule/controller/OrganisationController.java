package nl.avans.communicatiemodule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.avans.communicatiemodule.domain.MessageLog;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.dto.OrganisationConfigDto;
import nl.avans.communicatiemodule.repository.MessageLogRepository;
import nl.avans.communicatiemodule.service.OrganisationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organisations")
@RequiredArgsConstructor
public class OrganisationController {

    private final OrganisationService organisationService;
    private final MessageLogRepository messageLogRepository;

    @GetMapping
    public List<OrganisationConfig> listAll() {
        return organisationService.findAll();
    }

    @GetMapping("/{id}")
    public OrganisationConfig getById(@PathVariable UUID id) {
        return organisationService.findById(id);
    }

    @PostMapping
    public ResponseEntity<OrganisationConfig> create(@Valid @RequestBody OrganisationConfigDto dto) {
        OrganisationConfig created = organisationService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public OrganisationConfig update(@PathVariable UUID id,
                                     @Valid @RequestBody OrganisationConfigDto dto) {
        return organisationService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        organisationService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    /** Get message logs for an organisation (for monitoring dashboard) */
    @GetMapping("/{id}/logs")
    public List<MessageLog> getLogs(@PathVariable UUID id) {
        return messageLogRepository.findByOrganisationId(id);
    }
}
