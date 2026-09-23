package com.dajin.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import io.jsonwebtoken.Claims;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SyncWebSocketHandler extends TextWebSocketHandler {
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;
    private final JwtService jwt;
    public SyncWebSocketHandler(ObjectMapper objectMapper, JwtService jwt) { this.objectMapper = objectMapper; this.jwt = jwt; }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            String token = null;
            if (uri != null && uri.getQuery() != null) {
                for (String pair : uri.getQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && "token".equals(parts[0])) token = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            if (token == null || token.isBlank()) throw new IllegalArgumentException("missing token");
            Claims claims = jwt.parse(token);
            session.getAttributes().put("storeId", claims.get("storeId"));
            session.getAttributes().put("userId", claims.getSubject());
            sessions.add(session);
        } catch (Exception ex) {
            try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }
    public void broadcast(String type, Object data) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send(type, data); }
            });
            return;
        }
        send(type, data);
    }

    private void send(String type, Object data) {
        sessions.removeIf(s -> !s.isOpen());
        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(Map.of("type", type, "data", data)));
            Long eventStore = storeId(data);
            // Every business event is scoped to one store. Events without a
            // store id are intentionally dropped instead of leaking across
            // tenants; callers must include storeId/store_id in their payload.
            if (eventStore == null) return;
            sessions.stream().filter(s -> eventStore.equals(storeId(s.getAttributes().get("storeId"))))
                    .forEach(s -> { try { s.sendMessage(message); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    private Long storeId(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object id = map.containsKey("storeId") ? map.get("storeId") : map.get("store_id");
            if (id instanceof Number n) return n.longValue();
            if (id != null) try { return Long.parseLong(String.valueOf(id)); } catch (NumberFormatException ignored) { }
        }
        if (value instanceof Number n) return n.longValue();
        if (value != null) try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { }
        return null;
    }
}
