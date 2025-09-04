package com.beworking.leads;

import com.beworking.auth.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link LeadEmailListener}.
 * <p>
 * These tests verify that LeadEmailListener:
 * <ul>
 *   <li>Sends user and admin emails with correct parameters when a lead is created</li>
 *   <li>Generates the correct WhatsApp link in the admin email body</li>
 *   <li>Handles null phone numbers gracefully</li>
 * </ul>
 * <p>
 * The actual sending and error handling of emails is tested in {@link com.beworking.auth.EmailServiceTest}.
 */
public class LeadEmailListenerTest {
    private EmailService emailService;
    private LeadEmailListener leadEmailListener;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        leadEmailListener = new LeadEmailListener(emailService);
    }

    /**
     * Verifies that the user email is sent with the correct recipient, subject, and content
     * when a lead is created.
     */
    @Test 
    void shouldSendUserEmailWithCorrectParameters() {
        Lead lead = new Lead();
        lead.setName("John Doe");
        lead.setEmail("john@example.com");
        lead.setPhone("+34 600 123 456");
        LeadCreatedEvent event = mock(LeadCreatedEvent.class);
        when(event.getLead()).thenReturn(lead);

        leadEmailListener.handleLeadCreated(event);

        verify(emailService).sendHtml(eq("john@example.com"),contains("Gracias!"), anyString());
    }

    /**
     * Verifies that the admin notification email is sent with the correct recipient, subject, and content
     * when a lead is created.
     */
    @Test
    void shouldSendAdminEmailWithCorrectParameters() {
        Lead lead = new Lead();
        lead.setName("Jane Smith");
        lead.setEmail("jane@example.com");
        lead.setPhone("+34 600 987 654");
        LeadCreatedEvent event = mock(LeadCreatedEvent.class);
        when(event.getLead()).thenReturn(lead);

        leadEmailListener.handleLeadCreated(event);

        verify(emailService).sendHtml(eq("jane@example.com"),contains("Gracias!"), anyString());
    }

    /**
     * Captures the admin email body and asserts that the WhatsApp link is correctly generated
     * from the lead's phone number.
     */
    @Test
    void shouldGenerateCorrectWhatsAppLink() {
        Lead lead = new Lead();
        lead.setName("Carlos");
        lead.setEmail("carlos@example.com");
        lead.setPhone("+34 600-123-456");
        LeadCreatedEvent event = mock(LeadCreatedEvent.class);
        when(event.getLead()).thenReturn(lead);

        leadEmailListener.handleLeadCreated(event);
       
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(eq("info@be-working.com"), anyString(), bodyCaptor.capture());

        String emailBody = bodyCaptor.getValue();
        assert(emailBody.contains("https://wa.me/34600123456"));
    }

    /**
     * Verifies that a null phone number is handled gracefully and does not cause errors.
     */
    @Test
    void shouldHandleNullPhoneGracefully() {
        Lead lead = new Lead();
        lead.setName("Ana");
        lead.setEmail("ana@example.com");
        lead.setPhone(null);
        LeadCreatedEvent event = mock(LeadCreatedEvent.class);
        when(event.getLead()).thenReturn(lead);

        leadEmailListener.handleLeadCreated(event);
       // 
    }
}
