package com.battilana.nutribot.dto;

/** HU09: datos que deja el cliente cuando no hay un asesor disponible. */
public record PendingQueryRequest(String nombre, String telefono, String consulta) {
}
