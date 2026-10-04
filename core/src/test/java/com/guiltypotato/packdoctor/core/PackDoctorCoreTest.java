package com.guiltypotato.packdoctor.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackDoctorCoreTest {
    @Test
    void coreIsAlive() {
        assertTrue(PackDoctorCore.hello().contains("alive"));
    }
}
