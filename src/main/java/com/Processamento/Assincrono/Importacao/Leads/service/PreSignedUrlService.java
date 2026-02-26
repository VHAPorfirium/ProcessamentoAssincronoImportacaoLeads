package com.Processamento.Assincrono.Importacao.Leads.service;

import java.net.URL;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.Processamento.Assincrono.Importacao.Leads.api.dto.PreSignedUrlResponse;

import io.awspring.cloud.s3.S3Operations;

@Service
public class PreSignedUrlService {

    private static final String KEY_PREFIX = "uploads/";
    private static final Duration URL_VALIDITY = Duration.ofMinutes(15);
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+\\.csv$");

    private final S3Operations s3Operations;
    private final String bucket;

    public PreSignedUrlService(S3Operations s3Operations,
            @Value("${spring.cloud.aws.s3.bucket}") String bucket) {
        this.s3Operations = s3Operations;
        this.bucket = bucket;
    }

    public PreSignedUrlResponse createPresignedPutUrl(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName é obrigatório");
        }
        String trimmed = fileName.trim();
        if (!SAFE_FILENAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "fileName deve ser um nome simples terminando em .csv (ex.: leads-2026-02-18.csv)");
        }
        String key = KEY_PREFIX + trimmed;
        URL url = s3Operations.createSignedPutURL(bucket, key, URL_VALIDITY);
        return PreSignedUrlResponse.put(url.toString());
    }
}
