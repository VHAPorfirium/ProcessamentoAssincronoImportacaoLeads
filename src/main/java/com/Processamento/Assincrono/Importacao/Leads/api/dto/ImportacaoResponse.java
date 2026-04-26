package com.Processamento.Assincrono.Importacao.Leads.api.dto;

import java.time.Instant;

import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;

public record ImportacaoResponse(
        Long id,
        String nomeArquivo,
        String status,
        Integer totalLinhas,
        Integer linhasInseridas,
        Integer linhasIgnoradas,
        String erroMensagem,
        Instant criadoEm,
        Instant finalizadoEm) {
    public static ImportacaoResponse from(Importacao imp) {
        return new ImportacaoResponse(
                imp.getId(),
                imp.getNomeArquivo(),
                imp.getStatus(),
                imp.getTotalLinhas(),
                imp.getLinhasInseridas(),
                imp.getLinhasIgnoradas(),
                imp.getErroMensagem(),
                imp.getCriadoEm(),
                imp.getFinalizadoEm());
    }
}
