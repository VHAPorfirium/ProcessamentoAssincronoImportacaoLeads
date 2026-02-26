package com.Processamento.Assincrono.Importacao.Leads.repository;

import com.Processamento.Assincrono.Importacao.Leads.domain.Importacao;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportacaoRepository extends JpaRepository<Importacao, Long> {
}
