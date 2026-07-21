package com.codeatlas.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvReporterTest {

    @Test
    void neutralizesSpreadsheetFormulaPrefixes() {
        assertEquals("'=2+2", CsvReporter.csv("=2+2"));
        assertEquals("'+cmd", CsvReporter.csv("+cmd"));
        assertEquals("'-1", CsvReporter.csv("-1"));
        assertEquals("'@SUM(A1:A2)", CsvReporter.csv("@SUM(A1:A2)"));
        assertEquals("\"'\tformula,tail\"", CsvReporter.csv("\tformula,tail"));
    }

    @Test
    void preservesOrdinaryCsvEscaping() {
        assertEquals("plain", CsvReporter.csv("plain"));
        assertEquals("\"a,b\"", CsvReporter.csv("a,b"));
        assertEquals("\"a\"\"b\"", CsvReporter.csv("a\"b"));
        assertEquals("\"line one\rline two\"", CsvReporter.csv("line one\rline two"));
    }
}
