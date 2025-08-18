package com.beworking.leads;

import jakarta.validation.Valid;  // Import for validation annotations
import jakarta.validation.constraints.NotBlank;  // Import for validation annotations
import jakarta.validation.constraints.Email;  // Import for validation annotations for email validation
import org.springframework.http.HttpStatus; // Import for HttpStatus which is used to define HTTP status codes
import org.springframework.http.ResponseEntity; // Import for ResponseEntity which is used to return HTTP responses
import org.springframework.web.bind.annotation.*;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap; // Import for HashMap to store leads in memory
import java.util.Map; // Import for Map interface which is used to define the structure of the response


@RestController // Annotation to define this class as a REST controller which handles HTTP requests
@RequestMapping ("/api/leads") // Base URL for all endpoints in this controller. We receive the RESTful requests here from the client side, in this case from the handleSubmit function in the frontend
public class LeadController {

    private final LeadRepository leadRepository;
    private final ApplicationEventPublisher eventPublisher;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(LeadController.class);
   

    public LeadController(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher) {
        this.leadRepository = leadRepository;
         this.eventPublisher = eventPublisher; // Initialize the event publisher
    }

    public static class LeadRequest {
        @NotBlank
        public String name;
        @NotBlank @Email
        public String email;
        @NotBlank
        public String phone;
    }
    @org.springframework.transaction.annotation.Transactional // Annotation to indicate that this method should be executed within a transaction
    @PostMapping // Endpoint to create a new lead
    public ResponseEntity<Map<String, Object>> createLead(@Valid @RequestBody LeadRequest req) { // Method to handle POST requests to create a new lead
        Lead lead = new Lead(); // Create a new Lead object
        logger.info("Received lead request: name={}, email={}, phone={}", req.name, req.email, req.phone);
        lead.setName(req.name.trim());
        lead.setEmail(req.email.trim());
        lead.setPhone(req.phone.trim());
        leadRepository.save(lead); // Save the lead to the repository

        // Publish event after saving lead. From it goes to LeadEmailListener where the email is sent
        logger.info("Lead saved to database: id={}, name={}, email={}, phone={}", lead.getId(), lead.getName(), lead.getEmail(), lead.getPhone());
        eventPublisher.publishEvent(new LeadCreatedEvent(lead));
        logger.info("Published LeadCreatedEvent for lead id={}", lead.getId());

        // Create response body using a HashMap, where the key is a string and the value is an object
        Map<String, Object> body = new HashMap<>(); // Create a map to hold the response body
        body.put("message", "Lead created successfully"); // Add a success message to the response body
        body.put("id", lead.getId()); // Add the ID of the created lead to the response body
        return ResponseEntity.status(HttpStatus.CREATED).body(body); // Return a 201 Created response with the body
    }
    
    
}