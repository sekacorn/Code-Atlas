package com.codeatlas.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasCliTest {

    @Test
    void hardenedModeRefusesToStartTheExplorer() {
        int exit = new CommandLine(new AtlasCli()).execute("--hardened", "serve", "--port", "0");

        assertEquals(4, exit);
    }

    @Test
    void hardenedModeRejectsLimitsAboveItsMaximum() {
        int exit = new CommandLine(new AtlasCli()).execute("--hardened", "scan", ".",
                "--threads", "9", "--in-memory");

        assertEquals(2, exit);
    }
}
