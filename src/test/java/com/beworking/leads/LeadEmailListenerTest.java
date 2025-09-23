package com.beworking.leads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeadEmailListenerTest {

    @Test
    void buildWhatsappLink_addsDefaultCountryCodeForLocalNumber() {
        String link = LeadEmailListener.buildWhatsappLink("640 369 759");
        assertEquals("https://api.whatsapp.com/send/?phone=34640369759&text=&type=phone_number&app_absent=0", link);
    }

    @Test
    void buildWhatsappLink_preservesInternationalNumber() {
        String link = LeadEmailListener.buildWhatsappLink("+34 600 123 456");
        assertEquals("https://api.whatsapp.com/send/?phone=34600123456&text=&type=phone_number&app_absent=0", link);
    }

    @Test
    void buildWhatsappLink_handlesNullOrEmpty() {
        String nullLink = LeadEmailListener.buildWhatsappLink(null);
        String emptyLink = LeadEmailListener.buildWhatsappLink("   ");
        assertEquals("https://api.whatsapp.com/send/?phone=&text=&type=phone_number&app_absent=0", nullLink);
        assertEquals("https://api.whatsapp.com/send/?phone=&text=&type=phone_number&app_absent=0", emptyLink);
    }

    @Test
    void buildWhatsappWebLink_convertsApiUrl() {
        String apiLink = LeadEmailListener.buildWhatsappLink("640 369 759");
        String webLink = LeadEmailListener.buildWhatsappWebLink(apiLink);
        assertEquals("https://web.whatsapp.com/send?phone=34640369759", webLink);
    }

    @Test
    void buildWhatsappWebLink_handlesEmptyApiLink() {
        String webLink = LeadEmailListener.buildWhatsappWebLink("");
        assertEquals("https://web.whatsapp.com/", webLink);
    }

    @Test
    void buildMailtoReplyLink_encodesSubject() {
        String link = LeadEmailListener.buildMailtoReplyLink("lead@example.com");
        assertEquals("mailto:lead@example.com?subject=Re%3A%20%C2%A1Gracias%21%20Tu%20Oficina%20Virtual%20ya%20est%C3%A1%20en%20marcha%20%F0%9F%9A%80", link);
    }

    @Test
    void buildMailtoReplyLink_handlesNullOrEmpty() {
        String nullLink = LeadEmailListener.buildMailtoReplyLink(null);
        String emptyLink = LeadEmailListener.buildMailtoReplyLink("   ");
        assertEquals("mailto:", nullLink);
        assertEquals("mailto:", emptyLink);
    }

    @Test
    void buildGmailThreadLink_encodesMessageId() {
        String messageId = "<2024-05-24T12-00-00@beworking>";
        String link = LeadEmailListener.buildGmailThreadLink(messageId);
        assertEquals("https://mail.google.com/mail/u/0/#search/rfc822msgid%3A%3C2024-05-24T12-00-00%40beworking%3E", link);
    }

    @Test
    void buildGmailThreadLink_handlesMissingMessageId() {
        String link = LeadEmailListener.buildGmailThreadLink(null);
        assertEquals("https://mail.google.com/mail/", link);
    }
}
