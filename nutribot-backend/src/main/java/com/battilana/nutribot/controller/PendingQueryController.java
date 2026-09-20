package com.battilana.nutribot.controller;

import com.battilana.nutribot.dto.PendingQueryRequest;
import com.battilana.nutribot.model.PendingQuery;
import com.battilana.nutribot.repository.PendingQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * HU09: bitacora de consultas pendientes.
 * POST registra una consulta; GET la lista (la gestion completa es HU10/HU11).
 */
@RestController
@RequestMapping("/api/pending-queries")
@RequiredArgsConstructor
public class PendingQueryController {

    private final PendingQueryRepository repository;

    @PostMapping
    public PendingQuery create(@RequestBody PendingQueryRequest req) {
        return repository.save(PendingQuery.builder()
                .nombre(req.nombre())
                .telefono(req.telefono())
                .consulta(req.consulta())
                .estado("PENDIENTE")
                .build());
    }

    @GetMapping
    public List<PendingQuery> list() {
        return repository.findAll();
    }
}
