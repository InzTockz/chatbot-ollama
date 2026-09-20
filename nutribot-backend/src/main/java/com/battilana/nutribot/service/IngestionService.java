package com.battilana.nutribot.service;

import com.battilana.nutribot.model.KnowledgeDocument;
import com.battilana.nutribot.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

/**
 * Servicio de ingesta (HU06). Pipeline ETL de Spring AI con:
 *  - Validacion de formato (allowlist).
 *  - "Actualizar": al re-subir un documento se borran sus vectores previos (sin duplicar).
 *  - Eliminacion de un documento (vectores en Qdrant + registro en MySQL).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository repository;

    // Formatos permitidos (HU06: "valida el formato")
    private static final Set<String> FORMATOS_VALIDOS =
            Set.of("pdf", "doc", "docx", "txt", "csv", "md", "html", "htm");

    public KnowledgeDocument ingest(MultipartFile file) {
        String fileName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename() : "documento";

        // ---- Validacion de formato ----
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo esta vacio.");
        }
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!FORMATOS_VALIDOS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato no permitido (." + ext + "). Use PDF, Word, TXT, CSV, MD o HTML.");
        }

        log.info("Iniciando ingesta de: {}", fileName);
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("nutribot-", "." + ext);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 1) EXTRACT: Tika extrae el texto
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(tempFile));
            List<Document> documents = reader.read();
            documents.forEach(d -> d.getMetadata().put("source", fileName));

            // 2) TRANSFORM: fragmentar
            // Fragmentos mas pequenos y enfocados -> mejor precision del RAG (evita volcar bloques enteros)
            List<Document> chunks = TokenTextSplitter.builder()
                    .withChunkSize(300)
                    .withMinChunkSizeChars(150)
                    .build()
                    .apply(documents);

            // 3) ACTUALIZAR: si el documento ya existia, elimina vectores y registro previos
            borrarVectoresPorFuente(fileName);
            repository.findByFileName(fileName).ifPresent(repository::delete);

            // 4) LOAD: embeddings (Ollama) + Qdrant
            vectorStore.add(chunks);
            log.info("Documento '{}' indexado en {} fragmentos.", fileName, chunks.size());

            // 5) Registrar metadatos en MySQL (con fecha)
            KnowledgeDocument meta = KnowledgeDocument.builder()
                    .fileName(fileName)
                    .contentType(file.getContentType())
                    .sizeBytes(file.getSize())
                    .chunkCount(chunks.size())
                    .status("INDEXADO")
                    .build();
            return repository.save(meta);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error al ingerir el documento '{}'", fileName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo procesar el documento: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** HU06: eliminar un documento (sus vectores en Qdrant y su registro en MySQL). */
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado."));
        borrarVectoresPorFuente(doc.getFileName());
        repository.delete(doc);
        log.info("Documento '{}' (id {}) eliminado.", doc.getFileName(), id);
    }

    /** Borra de Qdrant todos los fragmentos cuyo metadato source == fileName. */
    private void borrarVectoresPorFuente(String fileName) {
        try {
            var filtro = new FilterExpressionBuilder().eq("source", fileName).build();
            vectorStore.delete(filtro);
        } catch (Exception e) {
            log.warn("No se pudieron borrar vectores previos de '{}': {}", fileName, e.getMessage());
        }
    }
}
