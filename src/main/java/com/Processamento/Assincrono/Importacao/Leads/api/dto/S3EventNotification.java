package com.Processamento.Assincrono.Importacao.Leads.api.dto;

/**
 * Representação do evento S3 (mantida apenas para documentação do contrato).
 * O parsing real é feito de forma manual no LeadsSqsListener para evitar
 * dependência de anotações Jackson cujo pacote muda entre versões major.
 *
 * Formato esperado do evento S3:
 * {
 *   "Records": [{
 *     "s3": {
 *       "bucket": { "name": "meu-bucket" },
 *       "object": { "key": "uploads/leads.csv" }
 *     }
 *   }]
 * }
 */
public final class S3EventNotification {
    private S3EventNotification() {}
}
