package com.beworking.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.mail.javamail.JavaMailSender;
import jakarta.mail.internet.MimeMessage;

import static org.mockito.Mockito.*;

class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Test for sending a confirmation email.
     * This test verifies that the email is sent sucessfully
     * @throws Exception
     */

    @Test
    void sendConfirmationEmail_Success() throws Exception {
        MimeMessage message = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(message);

        emailService.sendConfirmationEmail("test@example.com", "token123");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(message);
    }

    /**
     * Test for sending a confirmation email with an exception.
     * This test verifies that the exception is handled gracefully
     * and does not cause the application to crash.
     */

     @Test 
     void sendCoimfirmationEmail_ExceptionHandled() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail error"));

        // Should not throw an exception, just log the error
        emailService.sendConfirmationEmail("test@example.com", "toeken123");
     }
        
    /**
    * Test for sending a passwor reset email)
    * This verifies that the email is sent successfully
    * @throws Exception
    */
     
    @Test
    void sendPasswordResetEmail_Success() throws Exception {
        MimeMessage mimeMessage = mock (MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendPasswordResetEmail("test@example.com", "token123");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }

    /**
     * Test for sending a password reset email with an exception.
     * This test verifies that the exception is handled gracefully
     * and does not cause the application to crash.
     */
    @Test
    void sendPassowordResetEmail_ExceptionHandled() throws Exception {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail error"));

        // Should not throw an exception, just log the error
        emailService.sendPasswordResetEmail("test@example.com", "token123");
    }
}