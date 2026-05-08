package nl.avans.communicatiemodule.service;

import ca.uhn.fhir.context.FhirContext;
import nl.avans.communicatiemodule.domain.AppointmentNotification;
import nl.avans.communicatiemodule.domain.NotificationStatus;
import nl.avans.communicatiemodule.domain.OrganisationConfig;
import nl.avans.communicatiemodule.domain.ProviderType;
import nl.avans.communicatiemodule.repository.AppointmentNotificationRepository;
import nl.avans.communicatiemodule.repository.OrganisationConfigRepository;
import org.hl7.fhir.r4.model.Appointment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FhirProcessorServiceTest {

    @Mock private AppointmentNotificationRepository notificationRepository;
    @Mock private OrganisationConfigRepository organisationRepository;

    private FhirProcessorService service;
    private FhirContext fhirContext;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        fhirContext = FhirContext.forR4();
        service = new FhirProcessorService(fhirContext, notificationRepository, organisationRepository);
        orgId = UUID.randomUUID();

        OrganisationConfig org = new OrganisationConfig();
        org.setId(orgId);
        org.setName("Test Hospital");
        org.setTimezone("Europe/Amsterdam");
        org.setProviderType(ProviderType.SWIFT_SEND);
        org.setActive(true);

        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(org));
    }

    @Test
    void processIncoming_pendingAppointment_savesNotificationWithSchedule() {
        // Given: a FHIR Appointment in JSON, 3 days in the future
        long inThreeDays = System.currentTimeMillis() + (3L * 24 * 3600 * 1000);
        String fhirJson = buildAppointmentJson("appt-001", "booked", inThreeDays);

        when(notificationRepository.findByFhirAppointmentId("appt-001"))
            .thenReturn(Optional.empty());
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.processIncoming(orgId, fhirJson);

        // Then
        ArgumentCaptor<AppointmentNotification> captor = ArgumentCaptor.forClass(AppointmentNotification.class);
        verify(notificationRepository).save(captor.capture());

        AppointmentNotification saved = captor.getValue();
        assertThat(saved.getFhirAppointmentId()).isEqualTo("appt-001");
        assertThat(saved.getOrganisationId()).isEqualTo(orgId);
        assertThat(saved.getNotifyAt24h()).isNotNull();
        assertThat(saved.getNotifyAt1h()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void processIncoming_cancelledAppointment_setsStatusCancelled() {
        // Given: existing notification that gets cancelled
        AppointmentNotification existing = new AppointmentNotification();
        existing.setFhirAppointmentId("appt-002");
        existing.setStatus(NotificationStatus.PENDING);

        when(notificationRepository.findByFhirAppointmentId("appt-002"))
            .thenReturn(Optional.of(existing));

        long tomorrow = System.currentTimeMillis() + (24L * 3600 * 1000);
        String fhirJson = buildAppointmentJson("appt-002", "cancelled", tomorrow);

        // When
        service.processIncoming(orgId, fhirJson);

        // Then
        verify(notificationRepository).save(argThat(n ->
            n.getStatus() == NotificationStatus.CANCELLED));
    }

    @Test
    void processIncoming_invalidJson_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.processIncoming(orgId, "{ invalid json }"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private String buildAppointmentJson(String id, String status, long startMs) {
        Appointment appt = new Appointment();
        appt.setId(id);
        appt.setStatus(Appointment.AppointmentStatus.fromCode(status));
        appt.setStart(new Date(startMs));

        Appointment.AppointmentParticipantComponent participant =
            new Appointment.AppointmentParticipantComponent();
        participant.getActor().setReference("Patient/p-001").setDisplay("Test Patient");
        appt.addParticipant(participant);

        return fhirContext.newJsonParser().encodeResourceToString(appt);
    }
}
