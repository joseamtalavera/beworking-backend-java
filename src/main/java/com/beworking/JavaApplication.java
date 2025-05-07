package com.beworking;

import org.springframework.boot.SpringApplication; // it is the import of the main class of the Spring Boot application. It contains the main method which is the entry point of the application.
import org.springframework.boot.autoconfigure.SpringBootApplication; // it is the import of the Spring Boot application annotation. It is used to mark the main class of a Spring Boot application. It enables auto-configuration and component scanning.

@SpringBootApplication
public class JavaApplication {

	public static void main(String[] args) {
		SpringApplication.run(JavaApplication.class, args);
	}

}
