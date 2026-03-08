package com.beworking.support;

import com.beworking.auth.User;
import com.beworking.auth.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/support")
public class SupportController {

    private final AiChatService aiChatService;
    private final SupportTicketRepository ticketRepository;
    private final UserRepository userRepository;

    public SupportController(AiChatService aiChatService, SupportTicketRepository ticketRepository,
                             UserRepository userRepository) {
        this.aiChatService = aiChatService;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
    }

    // --- Chat endpoints ---

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        var user = getUser(principal);
        if (user == null || user.getTenantId() == null) return ResponseEntity.badRequest().build();

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        String response = aiChatService.chat(user.getTenantId(), user.getEmail(), message);
        return ResponseEntity.ok(Map.of("response", response));
    }

    @GetMapping("/chat/history")
    public ResponseEntity<List<ChatMessage>> getChatHistory(@AuthenticationPrincipal UserDetails principal) {
        var user = getUser(principal);
        if (user == null || user.getTenantId() == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(aiChatService.getHistory(user.getTenantId()));
    }

    // --- Ticket endpoints ---

    @PostMapping("/tickets")
    public ResponseEntity<SupportTicket> createTicket(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        var user = getUser(principal);
        if (user == null || user.getTenantId() == null) return ResponseEntity.badRequest().build();

        String subject = body.get("subject");
        String message = body.get("message");
        if (subject == null || subject.isBlank() || message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setTenantId(user.getTenantId());
        ticket.setUserEmail(user.getEmail());
        ticket.setSubject(subject);
        ticket.setMessage(message);
        ticket.setPriority(getPriorityForUser(user));
        ticket.setStatus("open");
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());

        return ResponseEntity.ok(ticketRepository.save(ticket));
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<SupportTicket>> getTickets(@AuthenticationPrincipal UserDetails principal) {
        var user = getUser(principal);
        if (user == null || user.getTenantId() == null) return ResponseEntity.badRequest().build();

        if ("ADMIN".equals(user.getRole().name())) {
            return ResponseEntity.ok(ticketRepository.findAllByOrderByCreatedAtDesc());
        }
        return ResponseEntity.ok(ticketRepository.findByTenantIdOrderByCreatedAtDesc(user.getTenantId()));
    }

    @PutMapping("/tickets/{id}")
    public ResponseEntity<SupportTicket> updateTicket(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        var user = getUser(principal);
        if (user == null || !"ADMIN".equals(user.getRole().name())) {
            return ResponseEntity.status(403).build();
        }

        var ticketOpt = ticketRepository.findById(id);
        if (ticketOpt.isEmpty()) return ResponseEntity.notFound().build();

        SupportTicket ticket = ticketOpt.get();
        if (body.containsKey("status")) ticket.setStatus(body.get("status"));
        if (body.containsKey("adminNotes")) ticket.setAdminNotes(body.get("adminNotes"));
        ticket.setUpdatedAt(LocalDateTime.now());

        return ResponseEntity.ok(ticketRepository.save(ticket));
    }

    private User getUser(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByEmail(principal.getUsername()).orElse(null);
    }

    private String getPriorityForUser(User user) {
        // Priority based on plan (determined by subscription amount)
        // For now, use a simple approach based on role
        return "ADMIN".equals(user.getRole().name()) ? "high" : "normal";
    }
}
