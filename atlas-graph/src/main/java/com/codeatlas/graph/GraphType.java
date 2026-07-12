package com.codeatlas.graph;

/** The graph views Code Atlas can export. */
public enum GraphType {
    /** Package-to-package coupling, coloured by risk. */
    DEPENDENCY,
    /** Method / subprogram call graph (resolved calls only). */
    CALL,
    /** Active vs. probable-dead artifacts. */
    DEAD_CODE,
    /** Role layers: endpoint → controller → service → mapper → repository → table, plus Ada I/O and state. */
    ARCHITECTURE;

    public static GraphType from(String s) {
        return switch (s.toLowerCase().replace('-', '_')) {
            case "dependency", "deps", "dependencies" -> DEPENDENCY;
            case "call", "calls", "callgraph" -> CALL;
            case "dead_code", "deadcode", "dead" -> DEAD_CODE;
            case "architecture", "arch", "layers" -> ARCHITECTURE;
            default -> throw new IllegalArgumentException("Unknown graph type '" + s
                    + "'. Use: dependency, call, dead-code, architecture.");
        };
    }
}
