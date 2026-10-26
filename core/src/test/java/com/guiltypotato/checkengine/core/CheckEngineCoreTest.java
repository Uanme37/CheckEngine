package com.guiltypotato.checkengine.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckEngineCoreTest {
    @Test
    void coreIsAlive() {
        assertTrue(CheckEngineCore.hello().contains("alive"));
    }
}
