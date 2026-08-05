package com.watermonitor.config;

import com.watermonitor.alert.AlertSettingsService;
import com.watermonitor.device.Device;
import com.watermonitor.device.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * First-startup seeding:
 *  - registers the initial device with the API key from APP_DEVICE_API_KEY
 *  - seeds alert settings (incl. Telegram credentials from env); afterwards the DB row
 *    is the source of truth and is editable from the dashboard (owner-confirmed approach)
 */
@Configuration
public class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    @Bean
    CommandLineRunner seed(DeviceRepository devices, AlertSettingsService settings, AppProperties props) {
        return args -> {
            if (devices.count() == 0) {
                Device d = new Device();
                d.setName(props.deviceName());
                d.setApiKey(props.deviceApiKey());
                devices.save(d);
                log.info("Bootstrap: registered initial device '{}'", props.deviceName());
            }
            settings.seedIfEmpty(props);
        };
    }
}
