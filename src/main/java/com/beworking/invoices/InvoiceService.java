package com.beworking.invoices;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class InvoiceService {
    private final JdbcTemplate jdbcTemplate;
    private final RestClient http;

    public InvoiceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.http = RestClient.create();
    }

    public Optional<URI> resolvePdfUrl(Long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT idfactura, holdedinvoicepdf FROM beworking.facturas WHERE id = ?",
            id
        );
        Integer idFactura = (Integer) row.get("idfactura");
        String stored = (String) row.get("holdedinvoicepdf");

        if (stored != null && (stored.startsWith("http://") || stored.startsWith("https://"))) {
            return Optional.of(URI.create(stored));
        }

        if (idFactura == null) {
            return Optional.empty();
        }

        String file = "Factura-" + idFactura + ".pdf";
        List<String> bases = Arrays.asList(
            "https://app.manaager.com/filesbeworking",
            "https://app.be-working.com/filesbeworking",
            "https://be-working.com/filesbeworking"
        );

        for (String base : bases) {
            URI candidate = URI.create(base + "/" + file);
            try {
                ResponseEntity<Void> resp = http.method(HttpMethod.HEAD)
                    .uri(candidate)
                    .retrieve()
                    .toBodilessEntity();
                if (resp.getStatusCode().is2xxSuccessful()) {
                    return Optional.of(candidate);
                }
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }
}


