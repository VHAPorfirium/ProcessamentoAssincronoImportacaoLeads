package com.Processamento.Assincrono.Importacao.Leads.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.Processamento.Assincrono.Importacao.Leads.api.dto.PreSignedUrlResponse;
import com.Processamento.Assincrono.Importacao.Leads.service.PreSignedUrlService;

@RestController
@RequestMapping("/api/upload/leads")
public class UploadLeadsController {

    private final PreSignedUrlService preSignedUrlService;

    public UploadLeadsController(PreSignedUrlService preSignedUrlService) {
        this.preSignedUrlService = preSignedUrlService;
    }

    @GetMapping("/presigned-url")
    public ResponseEntity<?> getPresignedUrl(@RequestParam String fileName) {
        try {
            PreSignedUrlResponse response = preSignedUrlService.createPresignedPutUrl(fileName);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
