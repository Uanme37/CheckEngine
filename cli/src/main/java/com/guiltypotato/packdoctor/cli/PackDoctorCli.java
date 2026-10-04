package com.guiltypotato.packdoctor.cli;

import com.guiltypotato.packdoctor.core.PackDoctorCore;

/** CLI entry point. Phase 3+: crash translator, server pack builder. */
public final class PackDoctorCli {
    public static void main(String[] args) {
        System.out.println(PackDoctorCore.hello() + " (CLI)");
    }
}
