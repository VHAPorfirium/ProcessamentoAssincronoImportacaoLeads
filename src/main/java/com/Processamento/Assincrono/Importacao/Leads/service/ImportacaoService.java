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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Processa CSV do S3 em streaming, persiste leads em batch e notifica via SNS ao final.
 *
 * Usa TransactionTemplate explícito em vez de @Transactional para garantir que:
 *   1. A transação do banco seja gerenciada de forma precisa (sem proxy bypass).
 *   2. O SNS só seja publicado após o commit (via afterCommit callback).
 *   3. Em caso de erro, o status ERRO seja persistido em uma transação separada
 *      (REQUIRES_NEW), independente do rollback da transação principal.
 */
@Service
public class ImportacaoService {

    private static final Logger log = LoggerFactory.getLogger(ImportacaoService.class);
    private static final int BATCH_SIZE = 100;
    private static final String STATUS_PROCESSANDO = "PROCESSANDO";
    private static final String STATUS_CONCLUIDO   = "CONCLUIDO";
    private static final String STATUS_ERRO        = "ERRO";

    private final S3Operations s3Operations;
    private final ImportacaoRepository importacaoRepository;
    private final LeadRepository leadRepository;
    private final SnsNotificationService snsNotificationService;

    /** Transação padrão (REQUIRED) — usada para o fluxo principal. */
    private final TransactionTemplate txPrincipal;

    /**
     * Transação independente (REQUIRES_NEW) — usada para persistir o status ERRO
     * mesmo que a transação principal tenha feito rollback.
     */
    private final TransactionTemplate txErro;

    public ImportacaoService(S3Operations s3Operations,
                             ImportacaoRepository importacaoRepository,
                             LeadRepository leadRepository,
                             SnsNotificationService snsNotificationService,
                             PlatformTransactionManager txManager) {
        this.s3Operations = s3Operations;
        this.importacaoRepository = importacaoRepository;
        this.leadRepository = leadRepository;
        this.snsNotificationService = snsNotificationService;

        this.txPrincipal = new TransactionTemplate(txManager);

        DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txErro = new TransactionTemplate(txManager, requiresNew);
    }

    /**
     * Ponto de entrada chamado pelo LeadsSqsListener.
     *
     * Fluxo:
     *   1. Verifica idempotência (arquivo já processado com sucesso → ignora).
     *   2. Cria registro de Importacao (PROCESSANDO) em TX própria.
     *   3. Processa o CSV em TX principal; registra callback SNS pós-commit.
     *   4. Em falha: atualiza Importacao para ERRO em TX independente e relança
     *      para o SQS devolver a mensagem após o Visibility Timeout.
     */
    public void processar(String bucket, String key) {

        // 1. Idempotência: evita reprocessar arquivo já concluído.
        Optional<Importacao> existente = importacaoRepository.findByKeyS3AndStatus(key, STATUS_CONCLUIDO);
        if (existente.isPresent()) {
            log.info("Arquivo já processado (id={}), ignorando mensagem duplicada: s3://{}/{}",
                existente.get().getId(), bucket, key);
            return;
        }

        // 2. Cria o registro inicial em TX própria para que ele persista
        //    mesmo que a TX principal de processamento precise fazer rollback.
        String nomeArquivo = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        Importacao importacao = txPrincipal.execute(status -> criarImportacaoInicial(bucket, key, nomeArquivo));

        try {
            // 3. Processa o CSV na TX principal.
            txPrincipal.executeWithoutResult(status -> {
                try {
                    S3Resource resource = s3Operations.download(bucket, key);
                    try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                        processarCsvEmStreaming(reader, importacao);
                    }
                    finalizarComSucesso(importacao);

                    // Registra o SNS para disparar APÓS o commit desta transação.
                    // Falha no SNS não desfaz os dados — o import já está CONCLUIDO.
                    registrarNotificacaoPosCommit(importacao);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

        } catch (Exception e) {
            log.error("Erro ao processar importação id={} s3://{}/{}: {}",
                importacao.getId(), bucket, key, e.getMessage(), e);

            // 4. Persiste o status ERRO em TX independente — sem depender da TX principal.
            txErro.executeWithoutResult(status ->
                finalizarComErro(importacao, e.getMessage()));

            // Relança para o SQS não confirmar a mensagem → reprocessamento automático.
            throw new RuntimeException("Processamento falhou — mensagem retornará à fila", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

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

        List<Lead> batch       = new ArrayList<>(BATCH_SIZE);
        int totalLinhas        = 0;
        int linhasInseridas    = 0;
        int linhasIgnoradas    = 0;

        try (CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                totalLinhas++;

                String nome     = get(record, "nome");
                String email    = get(record, "email");
                String telefone = get(record, "telefone");
                String origem   = get(record, "origem");

                if (email == null || email.isBlank()) {
                    log.warn("Linha {} ignorada: email vazio", record.getRecordNumber());
                    linhasIgnoradas++;
                    continue;
                }

                Lead lead = new Lead();
                lead.setImportacao(importacao);
                lead.setNome(truncate(nome, 255));
                lead.setEmail(truncate(email, 255));
                lead.setTelefone(truncate(telefone, 50));
                lead.setOrigem(truncate(origem, 100));

                batch.add(lead);
                linhasInseridas++;

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
        importacao.setLinhasInseridas(linhasInseridas);
        importacao.setLinhasIgnoradas(linhasIgnoradas);
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
        log.info("Importação {} concluída: {} leads inseridos, {} ignorados",
            importacao.getId(), importacao.getLinhasInseridas(), importacao.getLinhasIgnoradas());
    }

    private void finalizarComErro(Importacao importacao, String erroMensagem) {
        importacao.setStatus(STATUS_ERRO);
        importacao.setErroMensagem(erroMensagem != null ? truncate(erroMensagem, 4000) : null);
        importacao.setFinalizadoEm(Instant.now());
        importacaoRepository.save(importacao);
    }

    private void registrarNotificacaoPosCommit(Importacao importacao) {
        final Long id        = importacao.getId();
        final int inseridas  = importacao.getLinhasInseridas() != null ? importacao.getLinhasInseridas() : 0;
        final String bucket  = importacao.getBucket();
        final String key     = importacao.getKeyS3();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String resumo = String.format(
                    "Importação %d concluída: %d leads processados (s3://%s/%s).",
                    id, inseridas, bucket, key);
                try {
                    snsNotificationService.publicar(resumo);
                } catch (Exception e) {
                    log.error("Importação {} concluída com sucesso, mas falha ao notificar SNS: {}",
                        id, e.getMessage(), e);
                }
            }
        });
    }
}
