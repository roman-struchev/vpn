package com.vpn.android.api;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ApiHostRotationTest {

    @Test
    public void startsOnTheFirstHost() {
        ApiHostRotation rotation = new ApiHostRotation(List.of("https://a.example", "https://b.example"));
        assertEquals("https://a.example", rotation.current());
    }

    @Test
    public void advanceMovesToTheNextHost() {
        ApiHostRotation rotation = new ApiHostRotation(List.of("https://a.example", "https://b.example"));
        assertEquals("https://b.example", rotation.advance());
    }

    @Test
    public void advanceWrapsAroundAfterTheLastHost() {
        ApiHostRotation rotation = new ApiHostRotation(List.of("https://a.example", "https://b.example"));
        rotation.advance();
        assertEquals("https://a.example", rotation.advance());
    }

    @Test
    public void rejectsAnEmptyHostList() {
        assertThrows(IllegalArgumentException.class, () -> new ApiHostRotation(List.of()));
    }
}
