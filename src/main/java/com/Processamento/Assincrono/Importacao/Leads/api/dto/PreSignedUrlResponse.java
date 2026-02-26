package com.Processamento.Assincrono.Importacao.Leads.api.dto;

public record PreSignedUrlResponse(String url, String method) {

    public static PreSignedUrlResponse put(String url) {
        return new PreSignedUrlResponse(url, "PUT");
    }
}
