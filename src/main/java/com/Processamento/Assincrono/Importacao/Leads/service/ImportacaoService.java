package com.Processamento.Assincrono.Importacao.Leads.service;

import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;
import com.Processamento.Assincrono.Importacao.Leads.domain.Lead;
import com.Processamento.Assincrono.Importacao.Leads.repository.ImportacaoRepository;
import com.Processamento.Assincrono.Importacao.Leads.repository.LeadRepository;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Processa CSV do S3 em streaming, persiste leads em batch e notifica via SNS ao final.
 */
@Service
public class ImportacaoService {

    private static final Logger log = LoggerFactory.getLogger(ImportacaoService.class);
    private static final int BATCH_SIZE = 100;
    private static final String STATUS_PROCESSANDO = "PROCESSANDO";
    private static final String STATUS_CONCLUIDO = "CONCLUIDO";
    private static final String STATUS_ERRO = "ERRO";

    private final S3Operations s3Operations;
    private final ImportacaoRepository importacaoRepository;
    private final LeadRepository leadRepository;
    private final SnsNotificationService snsNotificationService;

    public ImportacaoService(S3Operations s3Operations,
                             ImportacaoRepository importacaoRepository,
                             LeadRepository leadRepository,
                             SnsNotificationService snsNotificationService) {
        this.s3Operations = s3Operations;
        this.importacaoRepository = importacaoRepository;
        this.leadRepository = leadRepository;
        this.snsNotificationService = snsNotificationService;
    }

    /**
     * Baixa o CSV do S3, processa em streaming e persiste leads em batch.
     * Cria/atualiza o registro de importação e publica resumo no SNS ao final.
     * Em falha: atualiza importação com ERRO, loga e relança para a mensagem SQS voltar à fila.
     */
    @Transactional
    public void processar(String bucket, String key) {
        String nomeArquivo = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        Importacao importacao = criarImportacaoInicial(bucket, key, nomeArquivo);

        try {
            S3Resource resource = s3Operations.download(bucket, key);
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                processarCsvEmStreaming(reader, importacao);
            }
            finalizarComSucesso(importacao);
            notificarSns(importacao);
        } catch (Exception e) {
            log.error("Erro ao processar importação id={} s3://{}/{}: {}", importacao.getId(), bucket, key, e.getMessage(), e);
            finalizarComErro(importacao, e.getMessage());
            throw new RuntimeException("Processamento falhou para reprocessamento pela fila", e);
        }
    }

    private Importacao criarImportacaoInicial(String bucket, String key, String nomeArquivo) {
        Importacao imp = new Importacao();
        imp.setNomeArquivo(nomeArquivo);
        imp.setStatus(STATUS_PROCESSANDO);
        imp.setBucket(bucket);
        imp.setKeyS3(key);
        return importacaoRepository.save(imp);
    }

    private void processarCsvEmStreaming(Reader reader, Importacao importacao) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

        List<Lead> batch = new ArrayList<>(BATCH_SIZE);
        int totalLinhas = 0;

        try (CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                String nome = get(record, "nome");
                String email = get(record, "email");
                String telefone = get(record, "telefone");
                String origem = get(record, "origem");

                if (email == null || email.isBlank()) {
                    log.warn("Linha {} ignorada: email vazio", record.getRecordNumber());
                    continue;
                }

                Lead lead = new Lead();
                lead.setImportacao(importacao);
                lead.setNome(truncate(nome, 255));
                lead.setEmail(truncate(email, 255));
                lead.setTelefone(truncate(telefone, 50));
                lead.setOrigem(truncate(origem, 100));

                batch.add(lead);
                totalLinhas++;

                if (batch.size() >= BATCH_SIZE) {
                    leadRepository.saveAll(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            leadRepository.saveAll(batch);
        }

        importacao.setTotalLinhas(totalLinhas);
        importacao.setLinhasInseridas(totalLinhas);
    }

    private static String get(CSVRecord record, String header) {
        try {
            return record.get(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private void finalizarComSucesso(Importacao importacao) {
        importacao.setStatus(STATUS_CONCLUIDO);
        importacao.setFinalizadoEm(Instant.now());
        importacaoRepository.save(importacao);
        log.info("Importação {} concluída: {} leads inseridos", importacao.getId(), importacao.getLinhasInseridas());
    }

    private void finalizarComErro(Importacao importacao, String erroMensagem) {
        importacao.setStatus(STATUS_ERRO);
        importacao.setErroMensagem(erroMensagem != null ? truncate(erroMensagem, 4000) : null);
        importacao.setFinalizadoEm(Instant.now());
        importacaoRepository.save(importacao);
    }

    private void notificarSns(Importacao importacao) {
        String resumo = String.format("Importação %d concluída: %d leads processados (s3://%s/%s).",
            importacao.getId(),
            importacao.getLinhasInseridas() != null ? importacao.getLinhasInseridas() : 0,
            importacao.getBucket(),
            importacao.getKeyS3());
        snsNotificationService.publicar(resumo);
    }
}
