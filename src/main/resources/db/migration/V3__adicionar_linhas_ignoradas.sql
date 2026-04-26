-- Adiciona coluna para rastrear linhas do CSV que foram ignoradas (ex.: email vazio).
ALTER TABLE importacao
    ADD COLUMN linhas_ignoradas INT NULL;
