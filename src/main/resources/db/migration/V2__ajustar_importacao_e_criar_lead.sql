-- Estender importacao com campos de processamento S3 e resultado
ALTER TABLE importacao
    ADD COLUMN bucket VARCHAR(255),
    ADD COLUMN key_s3 VARCHAR(512),
    ADD COLUMN total_linhas INT,
    ADD COLUMN linhas_inseridas INT,
    ADD COLUMN erro_mensagem TEXT,
    ADD COLUMN finalizado_em TIMESTAMP NULL;

CREATE TABLE `lead` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    importacao_id BIGINT NOT NULL,
    nome VARCHAR(255),
    email VARCHAR(255),
    telefone VARCHAR(50),
    origem VARCHAR(100),
    criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_lead_importacao FOREIGN KEY (importacao_id) REFERENCES importacao(id)
);

CREATE INDEX idx_lead_importacao_id ON `lead`(importacao_id);
CREATE INDEX idx_lead_email ON `lead`(email);
