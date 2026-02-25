package com.beworking.contacts;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ViesVatService {

    private static final Logger logger = LoggerFactory.getLogger(ViesVatService.class);
    private static final String VIES_URL = "https://ec.europa.eu/taxation_customs/vies/rest-api/ms/%s/vat/%s";
    private static final Pattern EU_VAT_PATTERN = Pattern.compile("^([A-Z]{2})\\s*([A-Z0-9]+)$");
    private static final Set<String> EU_COUNTRIES = Set.of(
        "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES",
        "FI", "FR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT",
        "NL", "PL", "PT", "RO", "SE", "SI", "SK", "XI"
    );

    private final RestTemplate restTemplate;

    public ViesVatService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public record VatValidationResult(boolean valid, String name, String address, String error) {
        public static VatValidationResult valid(String name, String address) {
            return new VatValidationResult(true, name, address, null);
        }
        public static VatValidationResult invalid(String error) {
            return new VatValidationResult(false, null, null, error);
        }
        public static VatValidationResult serviceError(String error) {
            return new VatValidationResult(false, null, null, error);
        }
    }

    public boolean isEuVatFormat(String taxId) {
        if (taxId == null || taxId.isBlank()) return false;
        Matcher m = EU_VAT_PATTERN.matcher(taxId.trim().toUpperCase());
        return m.matches() && EU_COUNTRIES.contains(m.group(1));
    }

    public VatValidationResult validate(String taxId) {
        if (taxId == null || taxId.isBlank()) {
            return VatValidationResult.invalid("Empty VAT number");
        }

        String normalized = taxId.trim().toUpperCase().replaceAll("\\s+", "");
        Matcher m = EU_VAT_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return VatValidationResult.invalid("Not a valid EU VAT format");
        }

        String countryCode = m.group(1);
        String vatNumber = m.group(2);

        if (!EU_COUNTRIES.contains(countryCode)) {
            return VatValidationResult.invalid("Country code " + countryCode + " is not an EU member state");
        }

        String url = String.format(VIES_URL, countryCode, vatNumber);
        logger.info("VIES validation: {} → {}", normalized, url);

        try {
            ResponseEntity<ViesResponse> response = restTemplate.getForEntity(url, ViesResponse.class);
            ViesResponse body = response.getBody();

            if (body == null) {
                return VatValidationResult.serviceError("Empty response from VIES");
            }

            if (body.isValid) {
                logger.info("VIES valid: {} (name={})", normalized, body.name);
                return VatValidationResult.valid(body.name, body.address);
            } else {
                logger.info("VIES invalid: {} (error={})", normalized, body.userError);
                return VatValidationResult.invalid(body.userError != null ? body.userError : "Invalid VAT number");
            }
        } catch (Exception e) {
            logger.warn("VIES service error for {}: {}", normalized, e.getMessage());
            return VatValidationResult.serviceError("VIES service unavailable");
        }
    }

    static class ViesResponse {
        @JsonProperty("isValid")
        public boolean isValid;

        @JsonProperty("userError")
        public String userError;

        @JsonProperty("name")
        public String name;

        @JsonProperty("address")
        public String address;

        @JsonProperty("vatNumber")
        public String vatNumber;

        @JsonProperty("requestDate")
        public String requestDate;
    }
}
