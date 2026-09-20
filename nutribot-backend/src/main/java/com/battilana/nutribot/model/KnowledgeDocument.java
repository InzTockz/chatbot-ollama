package com.battilana.nutribot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Metadatos de cada documento cargado a la base de conocimiento (HU06).
 * El TEXTO/vectores viven en Qdrant; aqui en MySQL solo guardamos el registro
 * para poder listar y administrar los documentos.
 */
@Entity
@Table(name = "knowledge_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    private String contentType;

    private Long sizeBytes;

    private Integer chunkCount;   // en cuantos fragmentos quedo dividido

    private String status;        // INDEXADO / ERROR

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
