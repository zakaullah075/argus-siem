package com.argus.alerts;

import com.argus.ingest.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RULE = UUID.randomUUID();

    @Mock
    private AlertRepository alertRepository;

    @InjectMocks
    private AlertService alertService;

    @Test
    void createsAlertWhenNoneIsOpenForTheDedupeKey() {
        when(alertRepository.findByTenantIdAndDedupeKeyAndStatusNot(
                eq(TENANT), anyString(), eq(AlertStatus.RESOLVED)))
                .thenReturn(Optional.empty());
        when(alertRepository.save(any(Alert.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Alert alert = alertService.raise(TENANT, RULE, Severity.HIGH, "rule:actor");

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.getOccurrenceCount()).isEqualTo(1);
        verify(alertRepository).save(any(Alert.class));
    }

    @Test
    void foldsIntoExistingOpenAlertRatherThanCreatingAnother() {
        Alert existing = new Alert(TENANT, RULE, Severity.HIGH, "rule:actor");
        when(alertRepository.findByTenantIdAndDedupeKeyAndStatusNot(
                eq(TENANT), anyString(), eq(AlertStatus.RESOLVED)))
                .thenReturn(Optional.of(existing));

        Alert alert = alertService.raise(TENANT, RULE, Severity.HIGH, "rule:actor");

        assertThat(alert).isSameAs(existing);
        assertThat(alert.getOccurrenceCount()).isEqualTo(2);
        verify(alertRepository, never()).save(any(Alert.class));
    }

    @Test
    void repeatedOccurrencesKeepAccumulating() {
        Alert existing = new Alert(TENANT, RULE, Severity.HIGH, "rule:actor");
        when(alertRepository.findByTenantIdAndDedupeKeyAndStatusNot(
                eq(TENANT), anyString(), eq(AlertStatus.RESOLVED)))
                .thenReturn(Optional.of(existing));

        for (int i = 0; i < 20; i++) {
            alertService.raise(TENANT, RULE, Severity.HIGH, "rule:actor");
        }

        assertThat(existing.getOccurrenceCount()).isEqualTo(21);
    }

    @Test
    void advancesLastSeenButNeverFirstSeen() throws Exception {
        Alert alert = new Alert(TENANT, RULE, Severity.HIGH, "rule:actor");
        var firstSeen = alert.getFirstSeenAt();

        Thread.sleep(5);
        alert.recordOccurrence();

        assertThat(alert.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(alert.getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void acknowledgedAlertStillAbsorbsOccurrences() {
        // An analyst acknowledging an alert has not fixed the underlying problem,
        // so continued activity must fold in rather than open a duplicate.
        Alert acknowledged = new Alert(TENANT, RULE, Severity.HIGH, "rule:actor");
        acknowledged.acknowledge();
        when(alertRepository.findByTenantIdAndDedupeKeyAndStatusNot(
                eq(TENANT), anyString(), eq(AlertStatus.RESOLVED)))
                .thenReturn(Optional.of(acknowledged));

        Alert alert = alertService.raise(TENANT, RULE, Severity.HIGH, "rule:actor");

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(alert.getOccurrenceCount()).isEqualTo(2);
    }

    @Test
    void statusTransitions() {
        Alert alert = new Alert(TENANT, RULE, Severity.HIGH, "rule:actor");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);

        alert.acknowledge();
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);

        alert.resolve();
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.RESOLVED);
    }
}
