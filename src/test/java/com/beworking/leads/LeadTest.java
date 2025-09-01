package com.beworking.leads;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code Lead} entity validation rules.
 *
 * <p>These tests use the Jakarta Bean Validation API (JSR 380) to verify that
 * the constraints placed on the {@code Lead} fields (e.g. {@code @NotBlank},
 * {@code @Email}) behave as expected. Tests are written as small, focused
 * cases that create a {@code Lead} instance, set fields to valid or invalid
 * values and then assert whether the validator reports constraint violations.
 */
public class LeadTest {

    /**
     * Validator used to programmatically validate {@link Lead} instances inside
     * test methods. The validator is created once for the whole test class.
     */
    static Validator validator;

    /**
     * Initialize the Validator before any tests run.
     *
     * This method builds a default {@link ValidatorFactory} and obtains a
     * {@link Validator} from it. The factory uses the project's validation
     * provider and configuration (if any) to construct the validator.
     */
    @BeforeAll
    static void setUpValidator() {
        // Create a ValidatorFactory and get a Validator instance. The factory is responsible for producing Validator objects, which are used to check if java objects meet their constraints
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Verify that a Lead with all required fields set to valid values
     * passes Bean Validation with no constraint violations.
     */
    @Test
    void validLeadShouldPassValidation() {
        Lead lead = new Lead(); // Create a new Lead object
        lead.setName("John Doe"); // Set valid name
        lead.setEmail("john@example.com"); // Set valid email
        lead.setPhone("123456789"); // Set valid phone
        // Assert that there are no validation errors
        assertTrue(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that omitting the required {@code name} field produces
     * at least one constraint violation.
     */
    @Test
    void missingNameShouldFailValidation() {
        Lead lead = new Lead(); // Create a new Lead object
        lead.setEmail("john@example.com"); // Set valid email
        lead.setPhone("123456789"); // Set valid phone
        // Assert that validation errors are present (name is missing)
        assertFalse(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that an invalid email format is rejected by the validator.
     */
    @Test 
    void invalidEmailShouldFailValidation() {
        Lead lead = new Lead();
        lead.setName("John Doe");
        lead.setEmail("invalid-email"); // No '@' or domain
        lead.setPhone("123456789");
        assertFalse(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that an empty phone value violates the {@code @NotBlank}
     * constraint and results in validation errors.
     */
    @Test 
    void emptyPhoneShouldFailValidation() {
        Lead lead = new Lead();
        lead.setName("John Doe");
        lead.setEmail("john@example.com");
        lead.setPhone(""); // Empty phone
        assertFalse(validator.validate(lead).isEmpty());
    }
    
    /**
     * Verify that setting the {@code name} field to an empty string
     * triggers validation errors.
     */
    @Test
    void emptyNameShouldFailValidation () {
        Lead lead = new Lead();
        lead.setName(""); // Empty name
        lead.setEmail("john@example.com");
        lead.setPhone("123456789");
        assertFalse(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that {@code createdAt} is automatically initialized when a
     * Lead instance is created.
     */
    @Test 
    void createAtShouldBeSetAutomatically() {
        Lead lead = new Lead();
        assertNotNull((lead.getCreatedAt()));
    }

    /**
     * Verify that the {@code hubspotSyncStatus} enum can be set and read
     * correctly on the Lead entity.
     */
    @Test
    void hubspotSyncStatusShouldBeSetAndRetrieved() {
        Lead lead = new Lead();
        lead.setHubspotSyncStatus(SyncStatus.SYNCED);
        assertEquals(SyncStatus.SYNCED, lead.getHubspotSyncStatus());
    }

    @Test
    void nullNameShouldFailValidation() {
        Lead lead = new Lead();
        lead.setName(null);              // null name
        lead.setEmail("john@example.com");
        lead.setPhone("123456789");
        assertFalse(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that a null {@code name} value triggers validation errors.
     */
    @Test
    void nullEmailShouldFailValidation() {
        Lead lead = new Lead();
        lead.setName("John");
        lead.setEmail(null);             // null email
        lead.setPhone("123456789");
        assertFalse(validator.validate(lead).isEmpty());
    }

    /**
     * Verify that a null {@code email} value triggers validation errors.
     */
    @Test 
    void whitespaceNameShouldFailValidation() {
        Lead lead = new Lead();
        // Set a name consisting only of whitespace characters
        lead.setName("   ");             // whitespace only
        lead.setEmail("john@example.com");
        lead.setPhone("123456789");
        // Expect validation to fail because name is blank
        assertFalse(validator.validate(lead).isEmpty());
    }


}
