package com.vpn.server;

import com.vpn.server.service.BlockchainPaymentService;
import com.vpn.server.task.BlockchainScannerTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
