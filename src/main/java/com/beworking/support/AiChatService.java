package com.beworking.support;

import com.beworking.ai.AiUsageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class AiChatService {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";

    private static final String SYSTEM_PROMPT = """
        You are BeWorking's AI support assistant. BeWorking is a virtual office platform based in Málaga, Spain.

        Services offered:
        - Virtual office with legal/fiscal business address (Calle Alejandro Dumas 17, 29004 Málaga)
        - Mail receiving, scanning and digital archiving
        - Coworking spaces and meeting rooms (BeSpaces)
        - Business management platform (contacts, invoicing, mailbox, bookings)
        - 50+ tool integrations (Gmail, Slack, Stripe, Notion, etc.)

        Plans:
        - Basis (€15/mo): Business address, mail scanning, dashboard, 50 AI queries/month
        - Pro (€25/mo): Everything in Basis + 5 coworking passes, meeting rooms, 200 AI queries/month
        - Max (€90/mo): Everything in Pro + unlimited access, dedicated desk, unlimited AI queries

        Guidelines:
        - Answer in the same language the user writes in (Spanish or English)
        - Be concise and helpful
        - If you cannot resolve the issue, say so clearly and offer to create a support ticket
        - Never make up information about pricing, features, or policies you're unsure about
        - For billing issues, account changes, or complex technical problems, recommend creating a ticket
        """;

    @Value("${ANTHROPIC_API_KEY:}")
    private String apiKey;

    private final ChatMessageRepository chatMessageRepository;
    private final AiUsageService aiUsageService;
    private final RestTemplate restTemplate = new RestTemplate();

    public AiChatService(ChatMessageRepository chatMessageRepository, AiUsageService aiUsageService) {
        this.chatMessageRepository = chatMessageRepository;
        this.aiUsageService = aiUsageService;
    }

    public String chat(Long tenantId, String userEmail, String userMessage) {
        // Check AI quota
        if (!aiUsageService.canQuery(tenantId)) {
            return "Has alcanzado el límite de consultas IA de tu plan este mes. Puedes actualizar tu plan para más consultas, o crear un ticket de soporte para hablar con nuestro equipo.";
        }

        // Save user message
        chatMessageRepository.save(new ChatMessage(tenantId, userEmail, "user", userMessage));

        // Build conversation history (last 20 messages for context)
        List<ChatMessage> history = chatMessageRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId);
        List<Map<String, String>> messages = new ArrayList<>();
        // Reverse to chronological order, take last 20
        List<ChatMessage> recent = history.size() > 20 ? history.subList(0, 20) : history;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage msg = recent.get(i);
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        String assistantResponse;
        if (apiKey == null || apiKey.isBlank()) {
            assistantResponse = "El asistente IA no está configurado todavía. Por favor, crea un ticket de soporte y nuestro equipo te ayudará.";
        } else {
            assistantResponse = callClaude(messages);
        }

        // Save assistant response
        chatMessageRepository.save(new ChatMessage(tenantId, userEmail, "assistant", assistantResponse));

        // Record AI usage
        aiUsageService.recordQuery(tenantId);

        return assistantResponse;
    }

    private String callClaude(List<Map<String, String>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 1024,
                "system", SYSTEM_PROMPT,
                "messages", messages
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(CLAUDE_API_URL, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
                if (content != null && !content.isEmpty()) {
                    return (String) content.get(0).get("text");
                }
            }
            return "Lo siento, no pude procesar tu consulta. ¿Quieres que cree un ticket de soporte?";
        } catch (Exception e) {
            return "Error al contactar el asistente. ¿Quieres que cree un ticket de soporte?";
        }
    }

    public List<ChatMessage> getHistory(Long tenantId) {
        var messages = chatMessageRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId);
        // Return in chronological order
        List<ChatMessage> reversed = new ArrayList<>(messages);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
