package com.watermonitor.measurement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "measurement")
public class Measurement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "pressure_psi", nullable = false)
    private BigDecimal pressurePsi;

    @Column(name = "measured_at", nullable = false)
    private OffsetDateTime measuredAt;

    public Measurement() {}

    public Measurement(Long deviceId, BigDecimal pressurePsi, OffsetDateTime measuredAt) {
        this.deviceId = deviceId;
        this.pressurePsi = pressurePsi;
        this.measuredAt = measuredAt;
    }

    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public BigDecimal getPressurePsi() { return pressurePsi; }
    public OffsetDateTime getMeasuredAt() { return measuredAt; }
}
