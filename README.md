# AWS Data Bridge — Leads Processor

Backend assíncrono para importação em massa de leads via CSV. O cliente faz upload de um arquivo para o S3, o sistema processa em background e envia uma notificação por e-mail ao terminar.

```
Cliente → API (Pre-signed URL) → S3 → SQS → Spring Boot Worker → MySQL → SNS → E-mail
```

---

## Sumário

- [Visão Geral](#visão-geral)
- [Arquitetura](#arquitetura)
- [Stack](#stack)
- [Pré-requisitos](#pré-requisitos)
- [Configuração AWS](#configuração-aws)
- [Variáveis de Ambiente](#variáveis-de-ambiente)
- [Rodando com Docker Compose](#rodando-com-docker-compose)
- [Rodando Localmente (sem Docker)](#rodando-localmente-sem-docker)
- [API Reference](#api-reference)
- [Formato do CSV](#formato-do-csv)
- [Banco de Dados](#banco-de-dados)
- [Decisões de Design](#decisões-de-design)
- [Estrutura do Projeto](#estrutura-do-projeto)

---

## Visão Geral

O sistema resolve o problema de importar arquivos CSV grandes sem travar o servidor. Em vez de processar o arquivo na requisição HTTP, o cliente recebe uma URL pré-assinada do S3, faz o upload direto, e o backend processa de forma assíncrona via fila SQS.

**Fluxo completo:**

1. Cliente chama `GET /api/upload/leads/presigned-url?fileName=leads.csv`
2. API devolve uma URL pré-assinada válida por 15 minutos
3. Cliente faz `PUT` diretamente no S3 usando essa URL (sem passar pelo servidor)
4. S3 dispara evento para a fila SQS
5. Worker Spring Boot consome a fila, baixa o CSV do S3 e processa linha por linha em streaming
6. Leads são persistidos no MySQL em batches de 100
7. Ao final, SNS publica uma notificação → e-mail chega para o assinante

---

## Arquitetura

```
┌──────────────┐     GET /presigned-url      ┌─────────────────────────┐
│    Cliente   │ ──────────────────────────► │  Spring Boot API :8080  │
│  (Postman /  │ ◄────────────────────────── │  /api/upload/leads      │
│   Frontend)  │      { url, method }         └────────────┬────────────┘
│              │                                           │ createSignedPutURL
│              │     PUT (CSV direto)         ┌────────────▼────────────┐
│              │ ──────────────────────────► │       AWS S3 Bucket      │
└──────────────┘                             │  s3://bucket/uploads/   │
                                             └────────────┬────────────┘
                                                          │ Event Notification
                                             ┌────────────▼────────────┐
                                             │       AWS SQS Queue      │
                                             └────────────┬────────────┘
                                                          │ @SqsListener
                                             ┌────────────▼────────────┐
                                             │  Spring Boot Worker      │
                                             │  (streaming CSV parser)  │
                                             └──────┬──────────┬────────┘
                                                    │          │
                                       ┌────────────▼───┐  ┌──▼──────────────────┐
                                       │  MySQL (JPA)   │  │    AWS SNS Topic     │
                                       │  importacao    │  │    → E-mail (Gmail)  │
                                       │  lead          │  └──────────────────────┘
                                       └────────────────┘
```

---

## Stack

| Componente | Versão |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.2 |
| Spring Cloud AWS | 4.0.0 |
| MySQL | 8.4 |
| Flyway | (gerenciado pelo Spring Boot) |
| Apache Commons CSV | 1.12.0 |
| Docker / Docker Compose | — |

**AWS Services:** S3 · SQS · SNS · IAM

---

## Pré-requisitos

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) instalado e rodando
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) configurado (`aws configure`)
- Conta AWS com os recursos abaixo criados

### Recursos AWS necessários

| Recurso | Descrição |
|---|---|
| S3 Bucket | Bucket para receber os CSVs (ex.: `meu-projeto-leads-123`) |
| SQS Queue | Fila Standard para receber eventos do S3 |
| SNS Topic | Tópico para notificações de conclusão |
| IAM User | Usuário com as permissões mínimas descritas abaixo |

---

## Configuração AWS

### IAM Policy mínima

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::SEU-BUCKET/*"
    },
    {
      "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:REGIAO:ACCOUNT_ID:NOME-DA-FILA"
    },
    {
      "Effect": "Allow",
      "Action": ["sns:Publish"],
      "Resource": "arn:aws:sns:REGIAO:ACCOUNT_ID:NOME-DO-TOPICO"
    }
  ]
}
```

### SQS Resource Policy (permite que o S3 envie mensagens para a fila)

No console AWS → SQS → sua fila → Access Policy:

```json
{
  "Effect": "Allow",
  "Principal": { "Service": "s3.amazonaws.com" },
  "Action": "sqs:SendMessage",
  "Resource": "arn:aws:sqs:REGIAO:ACCOUNT_ID:NOME-DA-FILA",
  "Condition": {
    "ArnLike": {
      "aws:SourceArn": "arn:aws:s3:::SEU-BUCKET"
    }
  }
}
```

### Checklist de configurações no console AWS

| Serviço | Onde | O que configurar |
|---|---|---|
| S3 | Bucket → Properties → Event notifications | Tipo: "All object create events" · Destino: SQS |
| SQS | sua fila → Edit | Visibility Timeout: **300 segundos** |
| SNS | seu tópico → Create subscription | Protocol: Email · Endpoint: seu e-mail (confirmar o link recebido) |

---

## Variáveis de Ambiente

Copie o arquivo `.env.example` para `.env` e preencha todos os valores:

```bash
cp .env.example .env
```

| Variável | Exemplo | Descrição |
|---|---|---|
| `DB_NAME` | `AsynchronousProcessingLeadImport` | Nome do banco MySQL |
| `DB_USER` | `root` | Usuário do banco |
| `DB_PASSWORD` | `sua_senha_aqui` | Senha do banco |
| `AWS_REGION` | `sa-east-1` | Região AWS |
| `AWS_S3_BUCKET_LEADS` | `meu-projeto-leads-123` | Nome do bucket S3 |
| `AWS_SQS_QUEUE_URL` | `https://sqs.sa-east-1.amazonaws.com/123456/minha-fila` | URL completa da fila SQS |
| `AWS_SNS_TOPIC_ARN` | `arn:aws:sns:sa-east-1:123456:MeuTopico` | ARN do tópico SNS |
| `AWS_ACCESS_KEY_ID` | `AKIAIOSFODNN7EXAMPLE` | Access Key do IAM user |
| `AWS_SECRET_ACCESS_KEY` | `wJalrXUtnFEMI/K7MDENG/...` | Secret Key do IAM user |

> **Segurança:** O `.env` está no `.gitignore`. Nunca o comite no repositório.

---

## Rodando com Docker Compose

```bash
# Primeira execução ou após mudança de código
docker compose up --build

# Demais execuções (sem rebuild)
docker compose up

# Parar e remover os containers
docker compose down
```

O Docker Compose sobe dois serviços:

- **leads-mysql** — MySQL 8.4 na porta `3306`
- **leads-app** — Spring Boot na porta `8080` (aguarda o MySQL ficar saudável antes de iniciar)

O Flyway executa as migrations automaticamente na inicialização.

### Verificar que está funcionando

```bash
# Status dos containers
docker ps

# Logs em tempo real
docker compose logs -f leads-app

# Acessar o MySQL dentro do container
docker exec -it leads-mysql mysql -u root -p AsynchronousProcessingLeadImport
```

Procure no log a linha: `Started ProcessamentoAssincronoImportacaoLeadsApplication in X.XXX seconds`

---

## Rodando Localmente (sem Docker)

Você precisa de um MySQL rodando localmente na porta 3306.

```bash
# Compilar e rodar diretamente
./mvnw spring-boot:run

# Ou gerar o JAR e executar
./mvnw clean package -DskipTests
java -jar target/ProcessamentoAssincronoImportacaoLeads-0.0.1-SNAPSHOT.jar
```

> O projeto usa `springboot4-dotenv`, então o arquivo `.env` na raiz é lido automaticamente sem precisar exportar variáveis.

---

## API Reference

### 1. Obter Pre-signed URL para upload

Gera uma URL temporária para o cliente fazer upload direto ao S3 sem expor credenciais AWS.

```
GET /api/upload/leads/presigned-url?fileName={fileName}
```

**Parâmetros:**

| Nome | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `fileName` | string | sim | Nome do arquivo `.csv` (ex.: `leads-2026-01-15.csv`) |

**Validação de segurança:** O `fileName` aceita apenas caracteres alfanuméricos, ponto, hífen e underscore, com extensão `.csv` obrigatória. Path traversal (`../`) é bloqueado com `400 Bad Request`.

**Resposta 200 OK:**

```json
{
  "url": "https://meu-bucket.s3.sa-east-1.amazonaws.com/uploads/leads-2026-01-15.csv?X-Amz-Algorithm=AWS4-HMAC-SHA256&...",
  "method": "PUT"
}
```

A URL expira em **15 minutos**.

**Resposta 400 Bad Request:**

```
fileName deve ser um nome simples terminando em .csv (ex.: leads-2026-02-18.csv)
```

---

### 2. Upload do CSV para o S3

Com a URL retornada no passo anterior, faça o `PUT` diretamente no S3:

```
PUT {url}
Content-Type: text/plain

nome,email,telefone,origem
Maria Silva,maria@email.com,11987654321,site
João Santos,joao@email.com,21976543210,indicacao
```

**Resposta 200** sem body — arquivo salvo no S3. O processamento começa automaticamente em segundos.

---

### 3. Listar importações

```
GET /api/importacoes?page=0&size=20
```

Retorna a lista de importações ordenada da mais recente para a mais antiga.

**Parâmetros de query:**

| Nome | Tipo | Padrão | Máximo | Descrição |
|---|---|---|---|---|
| `page` | int | `0` | — | Número da página (base 0) |
| `size` | int | `20` | `100` | Itens por página |

**Resposta 200 OK:**

```json
{
  "content": [
    {
      "id": 4,
      "nomeArquivo": "leads-2026-02-18.csv",
      "status": "CONCLUIDO",
      "totalLinhas": 4,
      "linhasInseridas": 4,
      "linhasIgnoradas": 0,
      "erroMensagem": null,
      "criadoEm": "2026-02-18T15:43:00Z",
      "finalizadoEm": "2026-02-18T15:43:02Z"
    }
  ],
  "totalElements": 4,
  "totalPages": 1,
  "number": 0,
  "size": 20
}
```

**Status possíveis:**

| Status | Significado |
|---|---|
| `PROCESSANDO` | Worker está processando o arquivo |
| `CONCLUIDO` | Processamento finalizado com sucesso |
| `ERRO` | Falha no processamento — mensagem retorna à fila para reprocessamento automático |

---

### 4. Detalhe de uma importação

```
GET /api/importacoes/{id}
```

**Resposta 200 OK:** Mesmo objeto do item do array acima.

**Resposta 404 Not Found:** ID inexistente.

---

## Formato do CSV

O arquivo deve ter cabeçalho na primeira linha. A coluna `email` é obrigatória — linhas sem email são ignoradas e contadas em `linhasIgnoradas`.

```csv
nome,email,telefone,origem
Maria Silva,maria.silva@email.com,11987654321,site
João Santos,joao.santos@email.com,21976543210,indicacao
Ana Oliveira,ana.oliveira@email.com,31965432109,evento
Pedro Costa,pedro.costa@email.com,41954321098,google
```

**Limites por coluna:**

| Coluna | Obrigatório | Tamanho máximo |
|---|---|---|
| `nome` | não | 255 caracteres |
| `email` | **sim** | 255 caracteres |
| `telefone` | não | 50 caracteres |
| `origem` | não | 100 caracteres |

Valores acima do limite são truncados silenciosamente. Colunas extras são ignoradas.

---

## Banco de Dados

### Schema

```sql
-- Controle das importações
CREATE TABLE importacao (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    nome_arquivo     VARCHAR(255),
    status           VARCHAR(50),         -- PROCESSANDO | CONCLUIDO | ERRO
    bucket           VARCHAR(255),
    key_s3           VARCHAR(512),
    total_linhas     INT,
    linhas_inseridas INT,
    linhas_ignoradas INT,
    erro_mensagem    TEXT,
    criado_em        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finalizado_em    TIMESTAMP NULL
);

-- Leads importados
CREATE TABLE `lead` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    importacao_id BIGINT NOT NULL,
    nome          VARCHAR(255),
    email         VARCHAR(255),
    telefone      VARCHAR(50),
    origem        VARCHAR(100),
    criado_em     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_lead_importacao FOREIGN KEY (importacao_id) REFERENCES importacao(id)
);

CREATE INDEX idx_lead_importacao_id ON `lead`(importacao_id);
CREATE INDEX idx_lead_email ON `lead`(email);
```

### Migrations Flyway

| Versão | Arquivo | O que faz |
|---|---|---|
| V1 | `V1__criar_tabela_importacao.sql` | Cria tabela inicial de importação |
| V2 | `V2__ajustar_importacao_e_criar_lead.sql` | Adiciona campos de resultado + cria tabela lead |
| V3 | `V3__adicionar_linhas_ignoradas.sql` | Adiciona coluna `linhas_ignoradas` |

### Consultas úteis

```sql
-- Histórico de importações
SELECT id, nome_arquivo, status, linhas_inseridas, linhas_ignoradas, criado_em
FROM importacao ORDER BY criado_em DESC;

-- Leads de uma importação específica
SELECT * FROM `lead` WHERE importacao_id = 1;

-- Contagem de leads por arquivo
SELECT i.nome_arquivo, COUNT(l.id) AS total_leads
FROM importacao i
LEFT JOIN `lead` l ON i.id = l.importacao_id
GROUP BY i.id ORDER BY i.criado_em DESC;
```

---

## Decisões de Design

### Pre-signed URL em vez de multipart upload pelo servidor

O cliente envia o arquivo diretamente ao S3 sem passar pelo servidor. Elimina risco de timeout HTTP em arquivos grandes e remove a carga de I/O do worker.

### Processamento assíncrono via SQS

Desacopla upload de processamento. Se o worker cair no meio do processamento, a mensagem volta à fila após o Visibility Timeout (300s) e é reprocessada automaticamente. Zero perda de dados.

### Streaming do CSV sem carregar na memória

O CSV é lido linha por linha diretamente do `InputStream` do S3 via `InputStreamReader`. Arquivos com milhões de linhas são suportados sem impacto no heap.

### Batch de 100 leads por `saveAll`

Reduz drasticamente o número de roundtrips ao banco. Para um CSV de 100.000 linhas, são 1.000 inserts em batch em vez de 100.000 inserts individuais.

### `TransactionTemplate` em vez de `@Transactional`

O método `processar()` chama métodos auxiliares na mesma instância. Em Spring AOP, chamadas internas bypassam o proxy, então `@Transactional` não funcionaria corretamente. O `TransactionTemplate` dá controle explícito e previsível sobre os limites de cada transação.

### `PROPAGATION_REQUIRES_NEW` para persistir o status `ERRO`

Quando o processamento falha, a transação principal faz rollback. O status `ERRO` precisa ser persistido no banco mesmo assim — por isso usa uma transação completamente independente.

### SNS publicado via `afterCommit`

O callback `TransactionSynchronizationManager.registerSynchronization` garante que o SNS só é acionado **depois do commit confirmado** no banco. Uma falha no envio do e-mail não desfaz a importação já concluída.

### Idempotência por `key_s3 + status`

SQS tem semântica *at-least-once*: a mesma mensagem pode ser entregue mais de uma vez. O sistema verifica se o arquivo já foi processado com sucesso antes de começar, ignorando duplicatas silenciosamente.

### `globally_quoted_identifiers` no Hibernate

`LEAD` é palavra reservada no MySQL 8 (função de janela). Sem quoting, o Hibernate gera SQL inválido (`insert into lead ...`). A propriedade `hibernate.globally_quoted_identifiers=true` faz o Hibernate envolver todos os identificadores em backticks automaticamente, resolvendo o conflito.

---

## Estrutura do Projeto

```
ProcessamentoAssincronoImportacaoLeads/
├── src/main/java/.../
│   ├── ProcessamentoAssincronoImportacaoLeadsApplication.java
│   ├── api/dto/
│   │   ├── ImportacaoResponse.java      # Record de resposta da API de importações
│   │   ├── PreSignedUrlResponse.java    # Record com { url, method }
│   │   └── S3EventNotification.java     # Documentação do contrato do evento S3
│   ├── config/
│   │   └── JacksonConfig.java           # Configuração Jackson (placeholder)
│   ├── controller/
│   │   ├── ImportacaoController.java    # GET /api/importacoes
│   │   └── UploadLeadsController.java   # GET /api/upload/leads/presigned-url
│   ├── domain/
│   │   ├── Importacao.java              # Entidade de controle das importações
│   │   └── Lead.java                    # Entidade de lead importado
│   ├── listener/
│   │   └── LeadsSqsListener.java        # Consome eventos S3 da fila SQS
│   ├── repository/
│   │   ├── ImportacaoRepository.java
│   │   └── LeadRepository.java
│   └── service/
│       ├── ImportacaoService.java        # Orquestra o processamento do CSV
│       ├── PreSignedUrlService.java      # Gera pre-signed PUT URL com validação
│       └── SnsNotificationService.java   # Publica resumo no tópico SNS
│
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__criar_tabela_importacao.sql
│       ├── V2__ajustar_importacao_e_criar_lead.sql
│       └── V3__adicionar_linhas_ignoradas.sql
│
├── Dockerfile                            # Multi-stage build: JDK Alpine → JRE Alpine
├── docker-compose.yml                    # MySQL 8.4 + Spring Boot com healthcheck
├── .env                                  # Variáveis locais (não commitado)
├── .env.example                          # Template de variáveis
└── pom.xml
```
