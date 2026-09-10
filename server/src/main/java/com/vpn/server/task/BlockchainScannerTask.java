package com.vpn.server.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.service.BlockchainPaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
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

    // keccak256("Transfer(address,address,uint256)") — the ERC-20 Transfer event
    // signature, a public standard, not something specific to any one token.
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

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

    @Value("${vpn.blockchain.evm.enabled:false}")
    private boolean evmEnabled;

    @Value("${vpn.blockchain.evm.chain-name:ETHEREUM}")
    private String evmChainName;

    @Value("${vpn.blockchain.evm.rpc-url:}")
    private String evmRpcUrl;

    @Value("${vpn.blockchain.evm.usdt-contract:0xdAC17F958D2ee523a2206206994597C13D831ec7}")
    private String evmUsdtContract;

    @Value("${vpn.blockchain.evm.usdt-decimals:6}")
    private int evmUsdtDecimals;

    @Value("${vpn.blockchain.evm.confirmations:12}")
    private int evmConfirmations;

    // In-memory only, like processedTxHashes above — reset on restart. A missed
    // deposit in that window still gets picked up manually via "я оплатил, вот
    // хеш" (POST /api/v1/user/billing/claim-tx), same safety net as for TRC-20.
    private volatile Long evmLastScannedBlock;

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

    public void setEvmEnabled(boolean evmEnabled) {
        this.evmEnabled = evmEnabled;
    }

    public void setEvmRpcUrl(String evmRpcUrl) {
        this.evmRpcUrl = evmRpcUrl;
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

    /**
     * ERC-20 USDT watcher via plain JSON-RPC {@code eth_getLogs} — no web3 SDK
     * dependency, same shape as {@link #scanTronGrid()}. Works against any
     * EVM-compatible chain (Ethereum, Base, Arbitrum, Polygon, ...) by pointing
     * {@code vpn.blockchain.evm.rpc-url} at that chain's RPC endpoint — see
     * docs/PLAN.md §7 ("Base/Arbitrum/Polygon включаются конфигом без нового кода").
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void scanEvmChain() {
        if (!evmEnabled) {
            return;
        }

        String depositAddress = blockchainPaymentService.getDefaultEvmDepositAddress();
        if (depositAddress == null || depositAddress.isBlank()) {
            log.debug("EVM scanner skipped: no deposit address configured (vpn.crypto.evm-deposit-address)");
            return;
        }
        if (evmRpcUrl == null || evmRpcUrl.isBlank()) {
            log.debug("EVM scanner skipped: no RPC URL configured (vpn.blockchain.evm.rpc-url)");
            return;
        }

        try {
            long currentBlock = evmRpcBlockNumber();
            long toBlock = currentBlock - Math.max(0, evmConfirmations);

            if (evmLastScannedBlock == null) {
                // First run: look back a bounded window instead of the whole chain
                // history. Anything older is still recoverable via "я оплатил, вот хеш".
                evmLastScannedBlock = Math.max(0, toBlock - 2000);
            }
            if (toBlock <= evmLastScannedBlock) {
                return;
            }
            long fromBlock = evmLastScannedBlock + 1;

            JsonNode logs = evmRpcGetLogs(fromBlock, toBlock, evmUsdtContract, depositAddress);
            if (logs != null && logs.isArray()) {
                for (JsonNode logEntry : logs) {
                    String txHash = logEntry.path("transactionHash").asText("");
                    if (txHash.isBlank() || processedTxHashes.contains(txHash)) {
                        continue;
                    }

                    BigInteger rawValue = new BigInteger(stripHexPrefix(logEntry.path("data").asText("0x0")), 16);
                    long amountMicro = scaleToMicroUsdt(rawValue, evmUsdtDecimals);

                    log.info("Processing detected {} USDT deposit: tx={}, amount={} micro-USDT to {}",
                            evmChainName, txHash, amountMicro, depositAddress);

                    var credited = blockchainPaymentService.processIncomingDeposit(
                            evmChainName, depositAddress, amountMicro, txHash
                    );

                    if (credited != null) {
                        processedTxHashes.add(txHash);
                        log.info("Successfully reconciled invoice #{} with tx {}", credited.getId(), txHash);
                    }
                }
            }

            evmLastScannedBlock = toBlock;
        } catch (Exception e) {
            log.error("Error occurred during {} EVM blockchain scan: {}", evmChainName, e.getMessage(), e);
        }
    }

    /** Converts a raw ERC-20 token amount (token's own decimals) to micro-USDT (fixed 6 decimals). */
    public static long scaleToMicroUsdt(BigInteger rawValue, int tokenDecimals) {
        int diff = tokenDecimals - 6;
        BigInteger scaled = diff >= 0
                ? rawValue.divide(BigInteger.TEN.pow(diff))
                : rawValue.multiply(BigInteger.TEN.pow(-diff));
        return scaled.longValueExact();
    }

    private long evmRpcBlockNumber() throws Exception {
        JsonNode result = evmRpcCall("eth_blockNumber", objectMapper.createArrayNode());
        return Long.parseLong(stripHexPrefix(result.asText("0x0")), 16);
    }

    private JsonNode evmRpcGetLogs(long fromBlock, long toBlock, String contractAddress, String depositAddress) throws Exception {
        var params = objectMapper.createArrayNode();
        var filter = params.addObject();
        filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
        filter.put("toBlock", "0x" + Long.toHexString(toBlock));
        filter.put("address", contractAddress);
        var topics = filter.putArray("topics");
        topics.add(ERC20_TRANSFER_TOPIC);
        topics.addNull(); // from: any address
        topics.add(addressToTopic(depositAddress)); // to: our deposit address only
        return evmRpcCall("eth_getLogs", params);
    }

    private JsonNode evmRpcCall(String method, JsonNode params) throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.set("params", params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(evmRpcUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("EVM RPC " + method + " returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.has("error")) {
            throw new IllegalStateException("EVM RPC " + method + " error: " + root.path("error"));
        }
        return root.path("result");
    }

    /** Left-pads a 20-byte 0x address into the 32-byte word shape eth_getLogs topics require. */
    public static String addressToTopic(String address) {
        String hex = stripHexPrefix(address).toLowerCase();
        return "0x" + "0".repeat(24) + hex;
    }

    private static String stripHexPrefix(String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }
}
