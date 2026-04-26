package com.beworking.bekey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * One-shot health check at startup, dev profile only.
 * Confirms AkilesClient + the API token can talk to https://api.akiles.app/v2.
 * Delete or convert to a real /actuator/health contributor once the
 * integration is past discovery.
 */
@Component
@Profile("dev")
class AkilesProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(AkilesProbe.class);

    private final AkilesClient akilesClient;

    AkilesProbe(AkilesClient akilesClient) {
        this.akilesClient = akilesClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    void probeOnStartup() {
        try {
            Map<String, Object> org = akilesClient.getOrganization();
            LOGGER.info("Akiles probe OK - organization: id={}, name={}",
                    org.get("id"), org.get("name"));
        } catch (Exception e) {
            LOGGER.error("Akiles probe FAILED - {}", e.toString());
        }
    }
}
