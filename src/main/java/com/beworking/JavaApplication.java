package com.beworking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entrypoint for the BeWorking backend API.
 *
 * <p>Bootstraps component scanning, configuration, and async execution.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class JavaApplication {
	/**
	 * Application entrypoint invoked by the JVM.
	 *
	 * @param args CLI arguments passed to Spring Boot.
	 */
	public static void main(String[] args) {
		SpringApplication.run(JavaApplication.class, args);
	}

}
