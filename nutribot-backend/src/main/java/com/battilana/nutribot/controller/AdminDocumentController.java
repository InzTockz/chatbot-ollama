package com.battilana.nutribot.controller;

import com.battilana.nutribot.model.KnowledgeDocument;
import com.battilana.nutribot.repository.KnowledgeDocumentRepository;
import com.battilana.nutribot.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * API de administracion de la base de conocimiento (HU06).
 */
@RestController
@RequestMapping("/api/admin/documents")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final IngestionService ingestionService;
    private final KnowledgeDocumentRepository repository;

    /** HU06: cargar un documento; se indexa automaticamente en Qdrant. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocument upload(@RequestParam("file") MultipartFile file) {
        return ingestionService.ingest(file);
    }

    /** Listar los documentos cargados (metadatos desde MySQL). */
    @GetMapping
    public List<KnowledgeDocument> list() {
        return repository.findAll();
    }

    /** HU06: eliminar un documento de la base de conocimiento. */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ingestionService.deleteDocument(id);
    }
}
