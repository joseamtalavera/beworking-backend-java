package com.beworking.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mailroom.storage")
public class FileStorageProperties {

    /**
     * Absolute or relative path where uploaded files are written.
     */
    private String location = "uploads/mailroom";

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
