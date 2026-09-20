package com.battilana.nutribot.dto;

/** Respuesta de NutriBot. 'accion' = NINGUNA | BOTON (mostrar asesor) | PREGUNTAR (ofrecer y esperar confirmacion). */
public record ChatResponse(String answer, String accion) {
}
