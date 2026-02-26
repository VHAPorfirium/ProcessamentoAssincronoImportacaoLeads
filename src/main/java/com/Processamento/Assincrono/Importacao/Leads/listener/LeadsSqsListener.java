package com.Processamento.Assincrono.Importacao.Leads.listener;

import com.Processamento.Assincrono.Importacao.Leads.api.dto.S3EventNotification;
import com.Processamento.Assincrono.Importacao.Leads.service.ImportacaoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import io.awspring.cloud.sqs.annotation.SqsListener;

/**
 * Listener SQS que recebe eventos "Object Created" do S3 e delega o processamento do CSV.
 * Em falha não confirma a mensagem: ela volta para a fila após o Visibility Timeout (resiliência).
 */
@Component
public class LeadsSqsListener {

    private static final Logger log = LoggerFactory.getLogger(LeadsSqsListener.class);

    private final ObjectMapper objectMapper;
    private final ImportacaoService importacaoService;

    public LeadsSqsListener(ObjectMapper objectMapper, ImportacaoService importacaoService) {
        this.objectMapper = objectMapper;
        this.importacaoService = importacaoService;
    }

    @SqsListener("${spring.cloud.aws.sqs.queue-url}")
    public void onS3ObjectCreated(@Payload String messageBody) {
        try {
            String payload = messageBody;
            JsonNode root = objectMapper.readTree(messageBody);
            if (root.has("Message") && root.get("Message").isTextual()) {
                payload = root.get("Message").asText();
            }
            S3EventNotification event = objectMapper.readValue(payload, S3EventNotification.class);
            String bucket = event.bucketName();
            String key = event.objectKey();
            if (bucket == null || key == null) {
                log.warn("Evento S3 sem bucket ou key: {}", messageBody);
                return;
            }
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (!key.endsWith(".csv")) {
                log.debug("Ignorando objeto não-CSV: s3://{}/{}", bucket, key);
                return;
            }
            log.info("Processando CSV s3://{}/{}", bucket, key);
            importacaoService.processar(bucket, key);
        } catch (Exception e) {
            log.error("Erro ao processar mensagem SQS (mensagem voltará à fila após Visibility Timeout): {}", e.getMessage(), e);
            throw new RuntimeException("Falha no processamento para reprocessamento pela fila", e);
        }
    }
}
