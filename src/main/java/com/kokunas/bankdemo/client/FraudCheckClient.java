package com.kokunas.bankdemo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Calls the fraud-cve microservice (github.com/kokunas/fraud-cve) before a
 * mortgage application or transfer is finalized. Fails open with a short
 * timeout: if the service is unreachable, the operation proceeds as if no
 * fraud signal was available, rather than making an internal risk-scoring
 * dependency a hard availability requirement for core banking operations.
 */
@Component
public class FraudCheckClient {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckClient.class);

    private final RestClient restClient;

    public FraudCheckClient(@Value("${fraud.service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(800);
        requestFactory.setReadTimeout(800);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public FraudCheckResult check(String customerNif, String transactionType, BigDecimal amount) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("customerNif", customerNif);
            form.add("transactionType", transactionType);
            form.add("amount", amount.toPlainString());

            FraudCheckResult result = restClient.post()
                    .uri("/api/fraudCheck")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(FraudCheckResult.class);

            return result != null ? result : FraudCheckResult.unavailable();
        } catch (Exception e) {
            log.warn("fraud-cve service unavailable, proceeding without a risk assessment: {}", e.getMessage());
            return FraudCheckResult.unavailable();
        }
    }
}
