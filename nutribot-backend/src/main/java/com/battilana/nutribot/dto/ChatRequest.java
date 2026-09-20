package com.battilana.nutribot.dto;

/**
 * Lo que envia el cliente:
 *  - message: su consulta en lenguaje natural.
 *  - conversationId: identificador de la conversacion (HU05), para la memoria.
 *  - previousQuestion: su pregunta anterior, usada para contextualizar los seguimientos
 *    y que la busqueda en el vector store recupere los fragmentos del tema correcto.
 */
public record ChatRequest(String message, String conversationId, String previousQuestion) {
}
