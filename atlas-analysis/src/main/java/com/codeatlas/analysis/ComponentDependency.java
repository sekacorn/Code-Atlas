package com.codeatlas.analysis;

import java.util.List;

/**
 * Coupling summary for one component (a package/namespace): how many other
 * components it depends on, how many depend on it, and a risk band derived from
 * the combination. High fan-in <em>and</em> fan-out marks an architectural hub.
 */
public record ComponentDependency(String name,
                                  int dependencies,
                                  int dependents,
                                  List<String> dependsOn,
                                  Risk risk) {

    public enum Risk {
        LOW, MEDIUM, HIGH
    }

    static Risk riskOf(int dependencies, int dependents) {
        int coupling = dependencies + dependents;
        if (dependents >= 10 || coupling >= 20) {
            return Risk.HIGH;
        }
        if (dependents >= 4 || coupling >= 8) {
            return Risk.MEDIUM;
        }
        return Risk.LOW;
    }
}
