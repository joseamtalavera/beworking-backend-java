
package com.beworking.leads;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LeadEmailService}.
 * <p>
 * These tests verify that LeadEmailService:
 * <ul>
 *   <li>Generates user HTML including the user's name</li>
 *   <li>Generates admin HTML including all lead info</li>
 *   <li>Handles null or empty fields gracefully</li>
 * </ul>
 */
class LeadEmailServiceTest {

    /**
     * Verifies that getUserHtml includes the user's name in the generated HTML.
     */
    @Test
    void getUserHtml_includesUserName() {
        String name = "John Doe";
        String html = LeadEmailService.getUserHtml(name);
        assertNotNull(html);
        assertTrue(html.contains("John Doe"), "Email should include the user's name ");
    }

    /**
     * Verifies that getAdminHtml includes all lead information (name, email, phone, WhatsApp link) in the generated HTML.
     */
    @Test
    void getAdminHtml_includesAllLeadInfo() {
        String name = "Bob";
        String email = "bob@example.com";
        String phone = "600123456";
        String waLink = "https://api.whatsapp.com/send/?phone=34600123456&text=&type=phone_number&app_absent=0";
        String waWebLink = "https://web.whatsapp.com/send?phone=34600123456";
        String gmailThreadLink = "https://mail.google.com/mail/u/0/#search/rfc822msgid%3A%3C2024-05-24T12-00-00%40beworking%3E";
        String mailtoLink = "mailto:bob@example.com?subject=Re%3A%20%C2%A1Gracias%21%20Tu%20Oficina%20Virtual%20ya%20est%C3%A1%20en%20marcha%20%F0%9F%9A%80";
        String html = LeadEmailService.getAdminHtml(name, email, phone, gmailThreadLink, mailtoLink, waLink, waWebLink);
        assertNotNull(html);
        assertTrue(html.contains(name), "Email should include the lead's name");
        assertTrue(html.contains(email), "Email should include the lead's email");
        assertTrue(html.contains(phone), "Email should include the lead's phone");
        assertTrue(html.contains(gmailThreadLink), "Email should include the Gmail thread link");
        assertTrue(html.contains(mailtoLink), "Email should include the reply mailto link");
        assertTrue(html.contains(waLink), "Email should include the WhatsApp link");
        assertTrue(html.contains(waWebLink), "Email should include the WhatsApp Web link");
    }

    /**
     * Verifies that getUserHtml handles null or empty name values gracefully and does not break the output.
     */
    @Test
    void getUserHtml_handlesNullOrEmptyNameGracefully() {
        String htmlNull = LeadEmailService.getUserHtml(null);
        String htmlEmpty = LeadEmailService.getUserHtml("");
        assertNotNull(htmlNull);
        assertNotNull(htmlEmpty);
    }

    /**
     * Verifies that getAdminHtml handles null or empty field values gracefully and does not break the output.
     */
    @Test
    void getAdminHtml_handlesNullOrEmptyFieldsGracefully() {
        String html = LeadEmailService.getAdminHtml(null, null, null, null, null, null, null);
        assertNotNull(html);
    }
}
