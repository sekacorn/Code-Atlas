package com.codeatlas.index;

/** Thrown when the local index cannot be opened, read or written. */
public class IndexException extends RuntimeException {

    public IndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
