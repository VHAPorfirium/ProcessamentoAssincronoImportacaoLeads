package com.Processamento.Assincrono.Importacao.Leads.service;

import io.awspring.cloud.s3.S3Operations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Processamento.Assincrono.Importacao.Leads.api.dto.PreSignedUrlResponse;

class PreSignedUrlServiceTest {

    private S3Operations s3Operations;
    private PreSignedUrlService service;

    private static final String BUCKET = "test-bucket";
    private static final String FAKE_URL = "https://s3.amazonaws.com/test-bucket/uploads/leads.csv?X-Amz-Signature=abc";

    @BeforeEach
    void setUp() throws MalformedURLException {
        s3Operations = mock(S3Operations.class);
        when(s3Operations.createSignedPutURL(eq(BUCKET), anyString(), any(Duration.class)))
            .thenReturn(new URL(FAKE_URL));
        service = new PreSignedUrlService(s3Operations, BUCKET);
    }

    @Test
    @DisplayName("Deve gerar URL pre-assinada para nome de arquivo válido")
    void deveGerarUrlParaNomeValido() {
        PreSignedUrlResponse response = service.createPresignedPutUrl("leads-2026-01-01.csv");

        assertThat(response.url()).isEqualTo(FAKE_URL);
        assertThat(response.method()).isEqualTo("PUT");
        verify(s3Operations).createSignedPutURL(eq(BUCKET), eq("uploads/leads-2026-01-01.csv"), any());
    }

    @Test
    @DisplayName("Deve usar o prefixo 'uploads/' na key do S3")
    void deveUsarPreficoUploads() {
        service.createPresignedPutUrl("meu-arquivo.csv");

        verify(s3Operations).createSignedPutURL(eq(BUCKET), startsWith("uploads/"), any());
    }

    @ParameterizedTest
    @DisplayName("Deve lançar IllegalArgumentException para nomes de arquivo inválidos")
    @ValueSource(strings = {
        "../hack.csv",          // path traversal
        "arquivo.txt",          // extensão errada
        "arquivo com espaço.csv", // espaço
        "",                     // vazio
        "pasta/arquivo.csv",    // caminho composto
        "arquivo.CSV"           // extensão maiúscula (não aceita)
    })
    void deveLancarExcecaoParaNomesInvalidos(String fileName) {
        assertThatThrownBy(() -> service.createPresignedPutUrl(fileName))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Deve lançar IllegalArgumentException para fileName nulo")
    void deveLancarExcecaoParaNomeNulo() {
        assertThatThrownBy(() -> service.createPresignedPutUrl(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Deve aceitar nomes com ponto, hífen e underscore")
    void deveAceitarNomesComCaracteresEspeciaisPermitidos() throws MalformedURLException {
        String[] nomesValidos = {
            "leads_2026.csv",
            "leads-jan-2026.csv",
            "LEADS.csv",
            "leads.v2.csv"
        };

        for (String nome : nomesValidos) {
            when(s3Operations.createSignedPutURL(any(), any(), any()))
                .thenReturn(new URL(FAKE_URL));
            assertThatCode(() -> service.createPresignedPutUrl(nome))
                .as("Nome '%s' deveria ser aceito", nome)
                .doesNotThrowAnyException();
        }
    }
}
