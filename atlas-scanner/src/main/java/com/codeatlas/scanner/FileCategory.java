package com.codeatlas.scanner;

/**
 * Coarse classification of a scanned file, independent of its specific language.
 * Drives which family of parser is dispatched and how the file is reported.
 */
public enum FileCategory {
    SOURCE,
    CONFIG,
    DATABASE,
    BUILD,
    DOCUMENTATION,
    OTHER
}
