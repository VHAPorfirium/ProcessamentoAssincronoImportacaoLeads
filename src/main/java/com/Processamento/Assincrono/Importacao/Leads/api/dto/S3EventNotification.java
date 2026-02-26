package com.Processamento.Assincrono.Importacao.Leads.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Estrutura do evento S3 enviado para SQS (Object Created).
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html">S3 Event Message Structure</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record S3EventNotification(
    @JsonProperty("Records") List<S3EventRecord> records
) {
    public String bucketName() {
        if (records == null || records.isEmpty()) return null;
        return records.get(0).s3().bucket().name();
    }

    public String objectKey() {
        if (records == null || records.isEmpty()) return null;
        return records.get(0).s3().object().key();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3EventRecord(
        @JsonProperty("s3") S3Info s3
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3Info(
        @JsonProperty("bucket") S3BucketInfo bucket,
        @JsonProperty("object") S3ObjectInfo object
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3BucketInfo(
        @JsonProperty("name") String name
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3ObjectInfo(
        @JsonProperty("key") String key
    ) {}
}
