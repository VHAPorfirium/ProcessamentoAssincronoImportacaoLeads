package com.Processamento.Assincrono.Importacao.Leads.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;

public interface ImportacaoRepository extends JpaRepository<Importacao, Long> {

    Optional<Importacao> findByKeyS3AndStatus(String keyS3, String status);

    Page<Importacao> findAllByOrderByCriadoEmDesc(Pageable pageable);
}
