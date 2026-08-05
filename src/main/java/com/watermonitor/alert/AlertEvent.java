package com.watermonitor.alert;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alert_event")
public class AlertEvent {

    public enum Type { LOW_PRESSURE, SUDDEN_DROP, OFFLINE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String message;

    @Column(name = "fired_at", nullable = false)
    private OffsetDateTime firedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    public AlertEvent() {}

    public AlertEvent(Long deviceId, Type type, String message, OffsetDateTime firedAt) {
        this.deviceId = deviceId;
        this.type = type;
        this.message = message;
        this.firedAt = firedAt;
    }

    public Long getId() { return id; }
    public Long getDeviceId() { return deviceId; }
    public Type getType() { return type; }
    public String getMessage() { return message; }
    public OffsetDateTime getFiredAt() { return firedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
