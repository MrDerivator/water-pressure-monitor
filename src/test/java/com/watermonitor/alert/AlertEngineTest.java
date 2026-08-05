package com.watermonitor.alert;

import com.watermonitor.measurement.MeasurementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertEngineTest {

    @Mock AlertSettingsService settingsService;
    @Mock AlertEventRepository events;
    @Mock MeasurementRepository measurements;
    @Mock TelegramService telegram;

    AlertEngine engine;
    AlertSettings settings;
    final Long DEVICE = 1L;
    final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        engine = new AlertEngine(settingsService, events, measurements, telegram);
        settings = new AlertSettings(); // defaults: low=20, drop=15/10min, cooldown=30
        lenient().when(settingsService.get()).thenReturn(settings);
    }

    @Test
    void lowPressureFiresAlertAndSavesEvent() {
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), any()))
                .thenReturn(Optional.empty());
        when(events.findTopByDeviceIdAndTypeOrderByFiredAtDesc(eq(DEVICE), any()))
                .thenReturn(Optional.empty());
        when(measurements.maxPressureSince(eq(DEVICE), any())).thenReturn(new BigDecimal("18"));

        engine.evaluate(DEVICE, new BigDecimal("15"), NOW);

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(events, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> e.getType() == AlertEvent.Type.LOW_PRESSURE);
        verify(telegram, atLeastOnce()).send(contains("LOW PRESSURE"));
    }

    @Test
    void noDuplicateAlertWhileIncidentOpen() {
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.LOW_PRESSURE)))
                .thenReturn(Optional.of(new AlertEvent(DEVICE, AlertEvent.Type.LOW_PRESSURE, "open", NOW.minusMinutes(5))));
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.OFFLINE)))
                .thenReturn(Optional.empty());
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.SUDDEN_DROP)))
                .thenReturn(Optional.empty());
        when(measurements.maxPressureSince(eq(DEVICE), any())).thenReturn(new BigDecimal("16"));

        engine.evaluate(DEVICE, new BigDecimal("15"), NOW);

        verify(telegram, never()).send(contains("LOW PRESSURE"));
    }

    @Test
    void recoveryResolvesOpenEventAndNotifies() {
        AlertEvent open = new AlertEvent(DEVICE, AlertEvent.Type.LOW_PRESSURE, "open", NOW.minusMinutes(20));
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.LOW_PRESSURE)))
                .thenReturn(Optional.of(open));
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.OFFLINE)))
                .thenReturn(Optional.empty());
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.SUDDEN_DROP)))
                .thenReturn(Optional.empty());
        when(measurements.maxPressureSince(eq(DEVICE), any())).thenReturn(new BigDecimal("55"));

        engine.evaluate(DEVICE, new BigDecimal("55"), NOW); // well above 20 + hysteresis

        assertThat(open.getResolvedAt()).isNotNull();
        verify(telegram).send(contains("recovered"));
    }

    @Test
    void suddenDropFiresAlert() {
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), any()))
                .thenReturn(Optional.empty());
        when(events.findTopByDeviceIdAndTypeOrderByFiredAtDesc(eq(DEVICE), any()))
                .thenReturn(Optional.empty());
        when(measurements.maxPressureSince(eq(DEVICE), any())).thenReturn(new BigDecimal("60"));

        engine.evaluate(DEVICE, new BigDecimal("40"), NOW); // 20 PSI drop > 15 default

        verify(telegram).send(contains("SUDDEN PRESSURE DROP"));
    }

    @Test
    void cooldownBlocksRefire() {
        when(events.findTopByDeviceIdAndTypeAndResolvedAtIsNullOrderByFiredAtDesc(eq(DEVICE), any()))
                .thenReturn(Optional.empty());
        // last LOW_PRESSURE event fired 10 min ago (cooldown is 30)
        when(events.findTopByDeviceIdAndTypeOrderByFiredAtDesc(eq(DEVICE), eq(AlertEvent.Type.LOW_PRESSURE)))
                .thenReturn(Optional.of(new AlertEvent(DEVICE, AlertEvent.Type.LOW_PRESSURE, "old", NOW.minusMinutes(10))));
        when(measurements.maxPressureSince(eq(DEVICE), any())).thenReturn(new BigDecimal("16"));

        engine.evaluate(DEVICE, new BigDecimal("15"), NOW);

        verify(telegram, never()).send(contains("LOW PRESSURE"));
    }
}
