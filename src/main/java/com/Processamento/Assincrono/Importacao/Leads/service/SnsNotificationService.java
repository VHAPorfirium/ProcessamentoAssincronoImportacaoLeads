package com.Processamento.Assincrono.Importacao.Leads.service;

import io.awspring.cloud.sns.core.SnsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Publica mensagens no tópico SNS configurado (ARN via variável de ambiente).
 */
@Service
public class SnsNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SnsNotificationService.class);

    private final SnsTemplate snsTemplate;
    private final String topicArn;

    public SnsNotificationService(SnsTemplate snsTemplate,
                                  @Value("${spring.cloud.aws.sns.topic-arn}") String topicArn) {
        this.snsTemplate = snsTemplate;
        this.topicArn = topicArn;
    }

    /**
     * Envia texto simples para o tópico SNS (ex.: resumo da importação).
     */
    public void publicar(String mensagem) {
        try {
            snsTemplate.sendNotification(topicArn, mensagem, null);
            log.debug("Notificação publicada no SNS: {}", mensagem);
        } catch (Exception e) {
            log.error("Falha ao publicar no SNS: {}", e.getMessage(), e);
            throw new RuntimeException("Falha ao publicar no SNS", e);
        }
    }
}
