package com.codeatlas.core;

import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.RepositoryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link RepositoryParser} implementations on the classpath via
 * {@link ServiceLoader}. This is the plugin mechanism: new languages and custom
 * formats are added by dropping a jar, with no change to the core.
 */
public final class ParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserRegistry.class);

    private final List<RepositoryParser> parsers;

    private ParserRegistry(List<RepositoryParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    /** Loads every parser advertised on the current thread's context classpath. */
    public static ParserRegistry discover() {
        List<RepositoryParser> found = new ArrayList<>();
        for (RepositoryParser p : ServiceLoader.load(RepositoryParser.class)) {
            found.add(p);
            log.info("Registered parser: {} ({})", p.displayName(), p.languageId());
        }
        if (found.isEmpty()) {
            log.warn("No parsers discovered on classpath");
        }
        return new ParserRegistry(found);
    }

    public static ParserRegistry of(RepositoryParser... parsers) {
        return new ParserRegistry(List.of(parsers));
    }

    /** The first parser that claims the file, if any. */
    public Optional<RepositoryParser> parserFor(ParseRequest request) {
        return parsers.stream().filter(p -> p.supports(request)).findFirst();
    }

    public List<RepositoryParser> all() {
        return parsers;
    }
}
