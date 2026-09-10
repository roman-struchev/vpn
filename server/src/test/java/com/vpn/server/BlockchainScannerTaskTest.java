package com.vpn.server;

import com.vpn.server.service.BlockchainPaymentService;
import com.vpn.server.task.BlockchainScannerTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockchainScannerTaskTest {

    @Mock
    private BlockchainPaymentService blockchainPaymentService;

    private BlockchainScannerTask task;

    @BeforeEach
    void setUp() {
        task = new BlockchainScannerTask(blockchainPaymentService);
    }

    @Test
    void testDisabledScannerDoesNothing() {
        task.setEnabled(false);
        task.scanTronGrid();
        verifyNoInteractions(blockchainPaymentService);
    }

    @Test
    void testPlaceholderAddressSkips() {
        task.setEnabled(true);
        when(blockchainPaymentService.getDefaultTronDepositAddress())
                .thenReturn("TXxxDefaultDepositAddressTRC20");

        task.scanTronGrid();
        verify(blockchainPaymentService).getDefaultTronDepositAddress();
        verifyNoMoreInteractions(blockchainPaymentService);
    }

    @Test
    void testDisabledEvmScannerDoesNothing() {
        task.setEvmEnabled(false);
        task.scanEvmChain();
        verifyNoInteractions(blockchainPaymentService);
    }

    @Test
    void testEvmScannerSkipsWithoutDepositAddress() {
        task.setEvmEnabled(true);
        task.setEvmRpcUrl("https://rpc.example.com");
        when(blockchainPaymentService.getDefaultEvmDepositAddress()).thenReturn("");

        task.scanEvmChain();
        verify(blockchainPaymentService).getDefaultEvmDepositAddress();
        verifyNoMoreInteractions(blockchainPaymentService);
    }

    @Test
    void testEvmScannerSkipsWithoutRpcUrl() {
        task.setEvmEnabled(true);
        task.setEvmRpcUrl("");
        when(blockchainPaymentService.getDefaultEvmDepositAddress()).thenReturn("0xDepositAddress");

        task.scanEvmChain();
        verifyNoMoreInteractions(blockchainPaymentService);
    }

    @Test
    void testScaleToMicroUsdtNoOpAtSixDecimals() {
        // USDT on Ethereum mainnet already uses 6 decimals, same scale as micro-USDT.
        assertEquals(10_000_000L, BlockchainScannerTask.scaleToMicroUsdt(BigInteger.valueOf(10_000_000L), 6));
    }

    @Test
    void testScaleToMicroUsdtDownscalesFromEighteenDecimals() {
        // A hypothetical 18-decimal token transferring 10 whole units.
        BigInteger raw = BigInteger.TEN.pow(19); // 10 * 10^18
        assertEquals(10_000_000L, BlockchainScannerTask.scaleToMicroUsdt(raw, 18));
    }

    @Test
    void testAddressToTopicLeftPadsTo32Bytes() {
        String topic = BlockchainScannerTask.addressToTopic("0xAbCdEf0000000000000000000000000000000001");
        // 0x + 24 zero hex chars (12 bytes) + the (lowercased) 20-byte address = 66 chars total.
        assertEquals(66, topic.length());
        assertEquals("0x" + "0".repeat(24) + "abcdef0000000000000000000000000000000001", topic);
    }
}
