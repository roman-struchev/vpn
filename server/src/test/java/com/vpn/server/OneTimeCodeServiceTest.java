package com.vpn.server;

import com.vpn.server.service.OneTimeCodeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OneTimeCodeServiceTest {

    private final OneTimeCodeService codes = new OneTimeCodeService();

    @Test
    void loginCodeWorksOnceAndToleratesHowItIsTyped() {
        String code = codes.createLoginCode(7L);
        assertTrue(code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertEquals(7L, codes.consumeLoginCode(" " + code.toLowerCase().replace("-", " ") + " ").orElseThrow());
        assertTrue(codes.consumeLoginCode(code).isEmpty());
    }

    @Test
    void aNewLoginCodeReplacesTheOldOne() {
        String first = codes.createLoginCode(7L);
        String second = codes.createLoginCode(7L);
        assertTrue(codes.consumeLoginCode(first).isEmpty());
        assertEquals(7L, codes.consumeLoginCode(second).orElseThrow());
    }

    @Test
    void resetCodeIsBurnedAfterFiveWrongGuesses() {
        String code = codes.createResetCode(9L);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertFalse(codes.consumeResetCode(9L, wrong));
        }
        assertFalse(codes.consumeResetCode(9L, code));
    }

    @Test
    void resetCodeWorksOnce() {
        String code = codes.createResetCode(9L);
        assertTrue(codes.consumeResetCode(9L, code));
        assertFalse(codes.consumeResetCode(9L, code));
    }
}
