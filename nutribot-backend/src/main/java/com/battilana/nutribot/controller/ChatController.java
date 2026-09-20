package com.battilana.nutribot.controller;

import com.battilana.nutribot.dto.ChatRequest;
import com.battilana.nutribot.dto.ChatResponse;
import com.battilana.nutribot.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de chat (HU01): el cliente envia una consulta y recibe una respuesta
 * fundamentada en la base de conocimiento (RAG), manteniendo el contexto de la
 * conversacion mediante el conversationId (HU05).
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.responder(request.message(), request.conversationId(), request.previousQuestion());
    }
}
