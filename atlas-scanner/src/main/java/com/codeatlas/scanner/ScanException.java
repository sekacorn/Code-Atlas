package com.codeatlas.scanner;

/** Thrown when a repository cannot be scanned (e.g. the root is unreadable). */
public class ScanException extends RuntimeException {

    public ScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
