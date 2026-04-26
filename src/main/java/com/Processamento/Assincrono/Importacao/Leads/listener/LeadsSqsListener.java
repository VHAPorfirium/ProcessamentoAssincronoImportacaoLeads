package com.Processamento.Assincrono.Importacao.Leads.listener;

import com.Processamento.Assincrono.Importacao.Leads.service.ImportacaoService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Listener SQS que recebe eventos "Object Created" do S3.
 *
 * O parsing do JSON é feito manualmente via busca de campos pelo nome,
 * eliminando dependência de anotações Jackson (que mudaram de pacote no Jackson 3.x).
 *
 * Em falha não confirma a mensagem: ela volta para a fila após o Visibility Timeout.
 */
@Component
public class LeadsSqsListener {

    private static final Logger log = LoggerFactory.getLogger(LeadsSqsListener.class);

    private final ImportacaoService importacaoService;

    public LeadsSqsListener(ImportacaoService importacaoService) {
        this.importacaoService = importacaoService;
    }

    @SqsListener("${spring.cloud.aws.sqs.queue-url}")
    public void onS3ObjectCreated(@Payload String messageBody) {
        try {
            String bucket = extrairBucket(messageBody);
            String key    = extrairKey(messageBody);

            if (bucket == null || key == null) {
                log.warn("Evento S3 sem bucket ou key reconhecível: {}", messageBody);
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
            log.error("Erro ao processar mensagem SQS (voltará à fila): {}", e.getMessage(), e);
            throw new RuntimeException("Falha no processamento para reprocessamento pela fila", e);
        }
    }

    // -------------------------------------------------------------------------
    // Parsing manual do evento S3 — sem dependência de anotações Jackson
    // -------------------------------------------------------------------------

    /**
     * Extrai o nome do bucket do JSON do evento S3.
     * Suporta mensagens diretas do S3 e envelopes do SNS (campo "Message").
     */
    private static String extrairBucket(String json) {
        String payload = desembrulharSns(json);
        // Procura: "name":"valor" dentro do bloco "bucket"
        int bucketIdx = payload.indexOf("\"bucket\"");
        if (bucketIdx < 0) return null;
        return extrairCampo(payload, "name", bucketIdx);
    }

    /**
     * Extrai a key do objeto do JSON do evento S3.
     */
    private static String extrairKey(String json) {
        String payload = desembrulharSns(json);
        // Procura: "key":"valor" dentro do bloco "object"
        int objectIdx = payload.indexOf("\"object\"");
        if (objectIdx < 0) return null;
        return extrairCampo(payload, "key", objectIdx);
    }

    /**
     * Se o JSON for um envelope SNS (tem campo "Message"), extrai o conteúdo interno.
     */
    private static String desembrulharSns(String json) {
        String marker = "\"Message\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return json;

        // Pula até o primeiro " após o ":"
        int start = json.indexOf('"', json.indexOf(':', idx + marker.length()) + 1);
        if (start < 0) return json;
        start++; // pula a aspas de abertura

        // Encontra o fim da string (aspas não escapada)
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; continue; }
                if (next == '\\') { sb.append('\\'); i++; continue; }
                if (next == 'n')  { sb.append('\n'); i++; continue; }
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Extrai o valor de um campo JSON simples ("campo":"valor") a partir de uma posição.
     */
    private static String extrairCampo(String json, String campo, int fromIndex) {
        String marker = "\"" + campo + "\"";
        int idx = json.indexOf(marker, fromIndex);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) return null;

        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        start++;

        int end = json.indexOf('"', start);
        if (end < 0) return null;

        return json.substring(start, end);
    }
}
