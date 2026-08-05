package com.watermonitor.device;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByApiKey(String apiKey);
}
