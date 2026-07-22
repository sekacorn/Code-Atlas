package com.codeatlas.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Root command for the Code Atlas CLI.
 *
 * <p>Offline by design: everything runs locally with no network access and no
 * administrator privileges. {@code atlas scan <repo>} builds the software model,
 * runs the analysis and writes reports.
 */
@Command(name = "atlas",
        mixinStandardHelpOptions = true,
        version = "Code Atlas 0.3.0",
        description = "Offline software intelligence and static analysis.",
        subcommands = {ScanCommand.class, LineageCommand.class, ToolCommand.class,
                OrientCommand.class, SummarizeCommand.class, InvestigateCommand.class,
                GraphCommand.class, OnboardCommand.class, ServeCommand.class})
public final class AtlasCli implements Runnable {

    @Option(names = "--hardened", scope = ScopeType.INHERIT,
            description = "Disable listeners and apply conservative resource limits.")
    private boolean hardened = configuredHardenedMode();

    boolean hardened() {
        return hardened;
    }

    @Override
    public void run() {
        // No subcommand: show usage.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        // Keep SLF4J-simple quiet unless the user asks otherwise.
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        int exit = new CommandLine(new AtlasCli()).execute(args);
        System.exit(exit);
    }

    private static boolean configuredHardenedMode() {
        return Boolean.getBoolean("codeatlas.hardened")
                || "true".equalsIgnoreCase(System.getenv("ATLAS_HARDENED"))
                || "1".equals(System.getenv("ATLAS_HARDENED"));
    }
}
