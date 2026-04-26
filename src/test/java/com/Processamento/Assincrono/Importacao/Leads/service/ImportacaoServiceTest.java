package com.Processamento.Assincrono.Importacao.Leads.service;

import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;
import com.Processamento.Assincrono.Importacao.Leads.domain.Lead;
import com.Processamento.Assincrono.Importacao.Leads.repository.ImportacaoRepository;
import com.Processamento.Assincrono.Importacao.Leads.repository.LeadRepository;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportacaoServiceTest {

    private S3Operations s3Operations;
    private ImportacaoRepository importacaoRepository;
    private LeadRepository leadRepository;
    private SnsNotificationService snsNotificationService;
    private PlatformTransactionManager txManager;
    private ImportacaoService service;

    private static final String BUCKET = "test-bucket";
    private static final String KEY    = "uploads/leads.csv";

    @BeforeEach
    void setUp() throws Exception {
        s3Operations            = mock(S3Operations.class);
        importacaoRepository    = mock(ImportacaoRepository.class);
        leadRepository          = mock(LeadRepository.class);
        snsNotificationService  = mock(SnsNotificationService.class);

        // Mock do PlatformTransactionManager que executa o callback diretamente (sem TX real).
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(txManager).commit(any());
        doNothing().when(txManager).rollback(any());

        service = new ImportacaoService(
            s3Operations, importacaoRepository, leadRepository,
            snsNotificationService, txManager);

        // Simula o save retornando a própria entidade com ID setado via reflexão.
        when(importacaoRepository.save(any())).thenAnswer(inv -> {
            Importacao imp = inv.getArgument(0);
            if (imp.getId() == null) {
                try {
                    var field = Importacao.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(imp, 1L);
                } catch (Exception ignored) {}
            }
            return imp;
        });
        when(leadRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // Inicializa suporte a sincronização de transação para os callbacks afterCommit.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Deve processar CSV válido e persistir os leads corretamente")
    void deveProcessarCsvValido() throws IOException {
        String csv = "nome,email,telefone,origem\n" +
                     "Maria Silva,maria@email.com,11999999999,site\n" +
                     "João Santos,joao@email.com,21988888888,indicacao\n";

        mockS3Download(csv);
        when(importacaoRepository.findByKeyS3AndStatus(KEY, "CONCLUIDO")).thenReturn(Optional.empty());

        service.processar(BUCKET, KEY);

        ArgumentCaptor<Importacao> captor = ArgumentCaptor.forClass(Importacao.class);
        verify(importacaoRepository, atLeastOnce()).save(captor.capture());

        Importacao finalState = captor.getAllValues().stream()
            .filter(i -> "CONCLUIDO".equals(i.getStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Nenhuma importação com status CONCLUIDO salva"));

        assertThat(finalState.getTotalLinhas()).isEqualTo(2);
        assertThat(finalState.getLinhasInseridas()).isEqualTo(2);
        assertThat(finalState.getLinhasIgnoradas()).isEqualTo(0);
        assertThat(finalState.getFinalizadoEm()).isNotNull();
    }

    @Test
    @DisplayName("Deve ignorar linhas com email vazio e contabilizar em linhasIgnoradas")
    void deveIgnorarLinhasSemEmail() throws IOException {
        String csv = "nome,email,telefone,origem\n" +
                     "Maria Silva,maria@email.com,11999999999,site\n" +
                     "Sem Email,,21988888888,indicacao\n" +
                     "Ana Costa,ana@email.com,31977777777,evento\n";

        mockS3Download(csv);
        when(importacaoRepository.findByKeyS3AndStatus(KEY, "CONCLUIDO")).thenReturn(Optional.empty());

        service.processar(BUCKET, KEY);

        ArgumentCaptor<Importacao> captor = ArgumentCaptor.forClass(Importacao.class);
        verify(importacaoRepository, atLeastOnce()).save(captor.capture());

        Importacao finalState = captor.getAllValues().stream()
            .filter(i -> "CONCLUIDO".equals(i.getStatus()))
            .findFirst()
            .orElseThrow();

        assertThat(finalState.getTotalLinhas()).isEqualTo(3);
        assertThat(finalState.getLinhasInseridas()).isEqualTo(2);
        assertThat(finalState.getLinhasIgnoradas()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve ignorar reprocessamento de arquivo já concluído (idempotência)")
    void deveIgnorarArquivoJaConcluido() {
        Importacao existente = new Importacao();
        when(importacaoRepository.findByKeyS3AndStatus(KEY, "CONCLUIDO"))
            .thenReturn(Optional.of(existente));

        service.processar(BUCKET, KEY);

        // Nenhuma interação com S3 ou leads se o arquivo já foi processado.
        verifyNoInteractions(s3Operations);
        verifyNoInteractions(leadRepository);
    }

    @Test
    @DisplayName("Deve persistir leads em batches quando há mais de 100 registros")
    void devePersistirEmBatches() throws IOException {
        StringBuilder csv = new StringBuilder("nome,email,telefone,origem\n");
        for (int i = 1; i <= 250; i++) {
            csv.append("Lead ").append(i)
               .append(",lead").append(i).append("@email.com,11999999999,site\n");
        }

        mockS3Download(csv.toString());
        when(importacaoRepository.findByKeyS3AndStatus(KEY, "CONCLUIDO")).thenReturn(Optional.empty());

        service.processar(BUCKET, KEY);

        // 250 leads → 2 batches de 100 + 1 batch de 50 = 3 chamadas ao saveAll.
        verify(leadRepository, times(3)).saveAll(anyList());
    }

    @Test
    @DisplayName("Deve salvar status ERRO quando o download do S3 falha")
    void deveSalvarErroQuandoS3Falha() {
        when(importacaoRepository.findByKeyS3AndStatus(KEY, "CONCLUIDO")).thenReturn(Optional.empty());
        when(s3Operations.download(BUCKET, KEY)).thenThrow(new RuntimeException("S3 indisponível"));

        assertThatThrownBy(() -> service.processar(BUCKET, KEY))
            .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<Importacao> captor = ArgumentCaptor.forClass(Importacao.class);
        verify(importacaoRepository, atLeastOnce()).save(captor.capture());

        boolean erroSalvo = captor.getAllValues().stream()
            .anyMatch(i -> "ERRO".equals(i.getStatus()));
        assertThat(erroSalvo).isTrue();
    }

    // -------------------------------------------------------------------------

    private void mockS3Download(String csvContent) throws IOException {
        S3Resource resource = mock(S3Resource.class);
        when(resource.getInputStream())
            .thenReturn(new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8)));
        when(s3Operations.download(BUCKET, KEY)).thenReturn(resource);
    }
}
