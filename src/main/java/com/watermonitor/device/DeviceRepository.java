package com.watermonitor.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByApiKey(String apiKey);

    List<Device> findByDeviceType(String deviceType);
}