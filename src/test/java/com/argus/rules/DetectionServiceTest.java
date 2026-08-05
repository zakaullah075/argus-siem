package com.argus.rules;

import com.argus.alerts.AlertService;
import com.argus.ingest.Event;
import com.argus.ingest.EventRepository;
import com.argus.common.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the matching and windowing logic. The integration tests prove
 * the pipeline works end to end; these pin down the decisions the engine makes,
 * which is far cheaper to do without a database.
 */
@ExtendWith(MockitoExtension.class)
class DetectionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Mock
    private RuleRepository ruleRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AlertService alertService;

    @InjectMocks
    private DetectionService detectionService;

    @Test
    void raisesNoAlertWhenTenantHasNoRules() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT)).thenReturn(List.of());

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        verify(alertService, never()).raise(any(), any(), any(), anyString());
    }

    @Test
    void skipsRuleWhenSourceDoesNotMatch() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("nginx", "auth.failed", Severity.LOW, 1)));

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        verify(eventRepository, never())
                .countMatching(any(), any(), any(), any(), any(), any());
        verify(alertService, never()).raise(any(), any(), any(), anyString());
    }

    @Test
    void skipsRuleWhenSeverityBelowMinimum() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.CRITICAL, 1)));

        detectionService.evaluate(event("sshd", "auth.failed", Severity.MEDIUM, "root"));

        verify(alertService, never()).raise(any(), any(), any(), anyString());
    }

    @Test
    void treatsNullConditionsAsMatchingAnything() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule(null, null, null, 1)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("anything", "any.type", Severity.LOW, "someone"));

        verify(alertService).raise(eq(TENANT), any(), eq(Severity.CRITICAL), anyString());
    }

    @Test
    void doesNotRaiseWhenCountIsBelowThreshold() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.LOW, 5)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(4L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        verify(alertService, never()).raise(any(), any(), any(), anyString());
    }

    @Test
    void raisesWhenCountReachesThresholdExactly() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.LOW, 5)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(5L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        verify(alertService).raise(eq(TENANT), any(), eq(Severity.CRITICAL), anyString());
    }

    @Test
    void windowStartsFromTheEventTimeNotWallClock() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.LOW, 1)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        var since = ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository).countMatching(any(), any(), any(), any(), since.capture(), any());

        // Anchoring on wall clock would make a backfilled batch of old events
        // silently fall outside every window.
        assertThat(since.getValue()).isEqualTo(NOW.minusSeconds(300));
    }

    @Test
    void expandsMinimumSeverityIntoAnExplicitSet() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.MEDIUM, 1)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Severity>> severities =
                ArgumentCaptor.forClass(Collection.class);
        verify(eventRepository)
                .countMatching(any(), any(), any(), severities.capture(), any(), any());

        // The column stores enum names, so a >= comparison would sort them
        // alphabetically: CRITICAL < HIGH < LOW < MEDIUM. Wrong, and silently so.
        assertThat(severities.getValue())
                .containsExactlyInAnyOrder(Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL);
    }

    @Test
    void countsPerActorSoSeparateAccountsDoNotCombine() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT))
                .thenReturn(List.of(rule("sshd", "auth.failed", Severity.LOW, 1)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "alice"));

        var actor = ArgumentCaptor.forClass(String.class);
        verify(eventRepository).countMatching(any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue()).isEqualTo("alice");
    }

    @Test
    void dedupeKeyCombinesRuleAndActorSoOneAccountYieldsOneAlert() {
        Rule rule = rule("sshd", "auth.failed", Severity.LOW, 1);
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT)).thenReturn(List.of(rule));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "alice"));

        var dedupeKey = ArgumentCaptor.forClass(String.class);
        verify(alertService).raise(any(), any(), any(), dedupeKey.capture());
        assertThat(dedupeKey.getValue()).isEqualTo(rule.getId() + ":alice");
    }

    @Test
    void dedupeKeyFallsBackWhenEventHasNoActor() {
        Rule rule = rule("sshd", "auth.failed", Severity.LOW, 1);
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT)).thenReturn(List.of(rule));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, null));

        var dedupeKey = ArgumentCaptor.forClass(String.class);
        verify(alertService).raise(any(), any(), any(), dedupeKey.capture());
        assertThat(dedupeKey.getValue()).isEqualTo(rule.getId() + ":-");
    }

    @Test
    void evaluatesEveryMatchingRuleNotJustTheFirst() {
        when(ruleRepository.findByTenantIdAndEnabledTrue(TENANT)).thenReturn(List.of(
                rule("sshd", "auth.failed", Severity.LOW, 1),
                rule("sshd", "auth.failed", Severity.LOW, 1)));
        when(eventRepository.countMatching(any(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        detectionService.evaluate(event("sshd", "auth.failed", Severity.HIGH, "root"));

        verify(alertService, org.mockito.Mockito.times(2))
                .raise(any(), any(), any(), anyString());
    }

    private Rule rule(String source, String eventType, Severity minSeverity, int threshold) {
        return new Rule(TENANT, "test rule", source, eventType, minSeverity,
                threshold, 300, Severity.CRITICAL);
    }

    private Event event(String source, String eventType, Severity severity, String actor) {
        return new Event(UUID.randomUUID(), TENANT, source, eventType, severity,
                actor, "10.0.0.5", "{}", NOW);
    }
}
