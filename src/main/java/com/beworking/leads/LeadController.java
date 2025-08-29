
package com.beworking.leads;

import jakarta.validation.Valid;  // Import for validation annotations
import org.springframework.http.HttpStatus; // Import for HttpStatus which is used to define HTTP status codes
import org.springframework.http.ResponseEntity; // Import for ResponseEntity which is used to return HTTP responses
import org.springframework.web.bind.annotation.*;
import org.springframework.context.ApplicationEventPublisher;
import java.util.HashMap; // Import for HashMap to store leads in memory
import java.util.Map; // Import for Map interface which is used to define the structure of the response
import com.beworking.leads.SanitizationUtils;

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
    // LeadRequest is now a separate DTO class in the same package
    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    public ResponseEntity<Map<String, Object>> createLead(@Valid @RequestBody LeadRequest req) {
        Lead lead = new Lead();
        logger.info("Received lead request: name={}, email={}, phone={}", req.getName(), req.getEmail(), req.getPhone());
    lead.setName(SanitizationUtils.sanitizeText(req.getName()));
    lead.setEmail(req.getEmail().trim());
    lead.setPhone(SanitizationUtils.sanitizePhone(req.getPhone()));
        leadRepository.save(lead);
        
        // Publish event after saving lead. From it goes to LeadEmailListener where the email is sent
        logger.info("Lead saved to database: id={}, name={}, email={}, phone={}", lead.getId(), lead.getName(), lead.getEmail(), lead.getPhone());
        eventPublisher.publishEvent(new LeadCreatedEvent(lead));
        logger.info("Published LeadCreatedEvent for lead id={}", lead.getId());

        // Return the created lead's ID in the response
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Lead created successfully");
        body.put("id", lead.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
    
    
}