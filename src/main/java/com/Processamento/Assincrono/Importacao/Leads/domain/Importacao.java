package com.Processamento.Assincrono.Importacao.Leads.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "importacao")
public class Importacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome_arquivo")
    private String nomeArquivo;

    @Column(length = 50)
    private String status;

    @Column(name = "criado_em")
    private Instant criadoEm;

    @Column(length = 255)
    private String bucket;

    @Column(name = "key_s3", length = 512)
    private String keyS3;

    @Column(name = "total_linhas")
    private Integer totalLinhas;

    @Column(name = "linhas_inseridas")
    private Integer linhasInseridas;

    @Column(name = "erro_mensagem", columnDefinition = "TEXT")
    private String erroMensagem;

    @Column(name = "finalizado_em")
    private Instant finalizadoEm;

    @OneToMany(mappedBy = "importacao", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Lead> leads = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (criadoEm == null) {
            criadoEm = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNomeArquivo() {
        return nomeArquivo;
    }

    public void setNomeArquivo(String nomeArquivo) {
        this.nomeArquivo = nomeArquivo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(Instant criadoEm) {
        this.criadoEm = criadoEm;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getKeyS3() {
        return keyS3;
    }

    public void setKeyS3(String keyS3) {
        this.keyS3 = keyS3;
    }

    public Integer getTotalLinhas() {
        return totalLinhas;
    }

    public void setTotalLinhas(Integer totalLinhas) {
        this.totalLinhas = totalLinhas;
    }

    public Integer getLinhasInseridas() {
        return linhasInseridas;
    }

    public void setLinhasInseridas(Integer linhasInseridas) {
        this.linhasInseridas = linhasInseridas;
    }

    public String getErroMensagem() {
        return erroMensagem;
    }

    public void setErroMensagem(String erroMensagem) {
        this.erroMensagem = erroMensagem;
    }

    public Instant getFinalizadoEm() {
        return finalizadoEm;
    }

    public void setFinalizadoEm(Instant finalizadoEm) {
        this.finalizadoEm = finalizadoEm;
    }

    public List<Lead> getLeads() {
        return leads;
    }

    public void setLeads(List<Lead> leads) {
        this.leads = leads;
    }
}
