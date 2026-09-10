package com.vpn.server.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.service.BlockchainPaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Component
public class BlockchainScannerTask {

    private static final Logger log = LoggerFactory.getLogger(BlockchainScannerTask.class);

    private final BlockchainPaymentService blockchainPaymentService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Set<String> processedTxHashes = new HashSet<>();

    @Value("${vpn.blockchain.scanner.enabled:false}")
    private boolean enabled;

    @Value("${vpn.blockchain.trongrid-url:https://api.trongrid.io}")
    private String tronGridUrl;

    @Value("${vpn.blockchain.trongrid-api-key:}")
    private String tronGridApiKey;

    @Value("${vpn.blockchain.usdt-contract:TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t}")
    private String usdtContract;

    public BlockchainScannerTask(BlockchainPaymentService blockchainPaymentService) {
        this.blockchainPaymentService = blockchainPaymentService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setTronGridUrl(String tronGridUrl) {
        this.tronGridUrl = tronGridUrl;
    }

    public void setTronGridApiKey(String tronGridApiKey) {
        this.tronGridApiKey = tronGridApiKey;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void scanTronGrid() {
        if (!enabled) {
            return;
        }

        String depositAddress = blockchainPaymentService.getDefaultTronDepositAddress();
        if (depositAddress == null || depositAddress.isBlank() || depositAddress.startsWith("TXxxDefault")) {
            log.debug("TronGrid scanner skipped: deposit address is default placeholder");
            return;
        }

        try {
            String uriStr = String.format("%s/v1/accounts/%s/transactions/trc20?limit=20&contract_address=%s",
                    tronGridUrl, depositAddress, usdtContract);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(uriStr))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET();

            if (tronGridApiKey != null && !tronGridApiKey.isBlank()) {
                reqBuilder.header("TRON-PRO-API-KEY", tronGridApiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("TronGrid returned HTTP status {}: {}", response.statusCode(), response.body());
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");

            if (data.isArray()) {
                for (JsonNode item : data) {
                    String to = item.path("to").asText("");
                    if (!depositAddress.equalsIgnoreCase(to)) {
                        continue;
                    }

                    String txHash = item.path("transaction_id").asText("");
                    if (txHash.isBlank() || processedTxHashes.contains(txHash)) {
                        continue;
                    }

                    String valueStr = item.path("value").asText("0");
                    long amountMicro = Long.parseLong(valueStr);

                    log.info("Processing detected TRC20 deposit: tx={}, amount={} micro-USDT to {}",
                            txHash, amountMicro, depositAddress);

                    var credited = blockchainPaymentService.processIncomingDeposit(
                            "TRON", depositAddress, amountMicro, txHash
                    );

                    if (credited != null) {
                        processedTxHashes.add(txHash);
                        log.info("Successfully reconciled invoice #{} with tx {}", credited.getId(), txHash);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error occurred during TronGrid blockchain scan: {}", e.getMessage(), e);
        }
    }
}
