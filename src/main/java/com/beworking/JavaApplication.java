package com.beworking;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import com.beworking.auth.RateLimitingFilter;
import org.springframework.boot.SpringApplication; 
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync; 
@SpringBootApplication
@EnableAsync
public class JavaApplication {
	public static void main(String[] args) {
		SpringApplication.run(JavaApplication.class, args);
	}

}
