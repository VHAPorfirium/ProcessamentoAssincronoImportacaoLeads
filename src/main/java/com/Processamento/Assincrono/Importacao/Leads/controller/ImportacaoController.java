package com.Processamento.Assincrono.Importacao.Leads.controller;

import com.Processamento.Assincrono.Importacao.Leads.api.dto.ImportacaoResponse;
import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;
import com.Processamento.Assincrono.Importacao.Leads.repository.ImportacaoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints de consulta do status das importações.
 *
 * GET /api/importacoes          — lista paginada (mais recente primeiro)
 * GET /api/importacoes/{id}     — detalhe de uma importação específica
 *
 * Útil para o cliente consultar o resultado do processamento assíncrono
 * sem precisar aguardar o e-mail do SNS.
 */
@RestController
@RequestMapping("/api/importacoes")
public class ImportacaoController {

    private final ImportacaoRepository importacaoRepository;

    public ImportacaoController(ImportacaoRepository importacaoRepository) {
        this.importacaoRepository = importacaoRepository;
    }

    @GetMapping
    public Page<ImportacaoResponse> listar(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        size = Math.min(size, 100); // teto de 100 itens por página
        Pageable pageable = PageRequest.of(page, size);
        return importacaoRepository.findAllByOrderByCriadoEmDesc(pageable)
                .map(ImportacaoResponse::from);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImportacaoResponse> buscarPorId(@PathVariable Long id) {
        return importacaoRepository.findById(id)
                .map(ImportacaoResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
