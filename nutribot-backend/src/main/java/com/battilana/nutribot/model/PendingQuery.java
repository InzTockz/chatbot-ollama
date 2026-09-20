package com.battilana.nutribot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * HU09: consulta pendiente registrada en la bitacora cuando no hay un asesor
 * disponible. Un asesor la atendera despues (la gestion es HU10/HU11).
 */
@Entity
@Table(name = "pending_query")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String telefono;

    @Column(length = 1000)
    private String consulta;

    private String estado;   // PENDIENTE / ATENDIDA

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (estado == null) {
            estado = "PENDIENTE";
        }
    }
}
