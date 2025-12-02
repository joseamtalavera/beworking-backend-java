package com.beworking.payments;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/payment-intents")
public class PublicPaymentController {

    private final String stripeSecretKey;

    public PublicPaymentController(@Value("${stripe.secret:}") String stripeSecretkey) {
        this.stripeSecretKey = stripeSecretkey;
    }

    @PostMapping
    public ResponseEntity<?> createPaymentIntent(@RequestBody PaymentIntentRequest request) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Stripe secret key is not configured"));
        }
        if (request.amount() == null || request.currency() == null || request.amount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount or currency"));
        }

        Stripe.apiKey = stripeSecretKey;

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(request.amount())
                .setCurrency(request.currency().toLowerCase());
        
        if (request.productName() != null) {
            builder.putMetadata("product_name", request.productName());
        }
        if (request.contactName() != null) {
            builder.putMetadata("contact_name", request.contactName());
        }
        if (request.contactEmail() != null) {
            builder.putMetadata("contact_email", request.contactEmail());
        }

        try {
            PaymentIntent intent = PaymentIntent.create(builder.build()); // SDK method to send the parameters to Stripe
            return ResponseEntity.ok(Map.of("clientSecret", intent.getClientSecret()));

        }catch (StripeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    public record PaymentIntentRequest(
            Long amount, 
            String currency,
            String productName,
            String centerCode,
            String contactName,
            String contactEmail
    ) {}
}
