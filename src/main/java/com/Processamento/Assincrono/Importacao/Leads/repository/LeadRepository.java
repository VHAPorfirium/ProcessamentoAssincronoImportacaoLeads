package com.Processamento.Assincrono.Importacao.Leads.repository;

import com.Processamento.Assincrono.Importacao.Leads.domain.Lead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadRepository extends JpaRepository<Lead, Long> {

    List<Lead> findAllByImportacaoId(Long importacaoId);
}
