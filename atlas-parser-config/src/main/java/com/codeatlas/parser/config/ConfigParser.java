package com.codeatlas.parser.config;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseIssue;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parses configuration files (Spring/JEE XML, {@code .properties}, YAML) into
 * {@code CONFIGURATION} entities and {@code CONFIGURES} references to the code they
 * wire up. Those references let dead-code detection know a framework-invoked class
 * is <em>not</em> dead, and answer "which configuration references this class?".
 *
 * <p>Security: raw configuration values are never stored, likely-secret keys are
 * counted and masked (see {@link ConfigSecrets}), and XML parsing is hardened
 * against XXE (no DTDs, no external entities) — nothing is executed or fetched.
 */
public final class ConfigParser implements RepositoryParser {

    static final String LANGUAGE = "config";
    static final String VERSION = "1.0.0";

    @Override
    public String languageId() {
        return LANGUAGE;
    }

    @Override
    public String displayName() {
        return "Configuration (XML / properties / YAML)";
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(ParseRequest request) {
        // Build descriptors are owned by the build parser, which extracts modules,
        // dependencies and declared mains from them. The pipeline gives each file to
        // exactly one parser, so this boundary is explicit rather than left to
        // classpath order. (Checked here by name to keep the two parsers independent.)
        if (isBuildFile(request.relativePath())) {
            return false;
        }
        return switch (request.extension()) {
            case "xml", "properties", "yaml", "yml" -> true;
            default -> false;
        };
    }

    /** Mirrors {@code atlas-parser-build}'s ownership; kept local to avoid a module dependency. */
    private static boolean isBuildFile(String relativePath) {
        String name = fileName(relativePath).toLowerCase(java.util.Locale.ROOT);
        return name.equals("pom.xml") || name.startsWith("build.gradle")
                || name.startsWith("settings.gradle") || name.endsWith(".gpr");
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String file = request.relativePath();
        ParseResult.Builder out = ParseResult.builder(LANGUAGE, file);
        String ext = request.extension();

        Entity config = Entity.builder(EntityKind.CONFIGURATION, fileName(file))
                .qualifiedName(file)
                .language(LANGUAGE)
                .location(SourceLocation.ofFile(file))
                .attribute("format", ext)
                .build();

        Emitter emitter = new Emitter(out, config.id(), file);
        try {
            switch (ext) {
                case "properties" -> parseProperties(request.content(), emitter);
                case "yaml", "yml" -> parseYaml(request.content(), emitter);
                case "xml" -> parseXml(request.content(), emitter, out);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            out.issue(ParseIssue.warning("Configuration parse incomplete: " + e.getMessage(), 0));
        }

        // Finalise the config entity with what we found (no raw values retained).
        Entity finalConfig = config.toBuilder()
                .attribute("references", emitter.references)
                .attribute("secretKeysMasked", emitter.secretsMasked)
                .build();
        out.entity(finalConfig);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, "file:" + file, finalConfig.id())
                .build());
        return out.build();
    }

    /** Collects CONFIGURES edges and counters while a format handler walks the file. */
    private static final class Emitter {
        private final ParseResult.Builder out;
        private final String configId;
        private final String file;
        private final Set<String> emittedTargets = new LinkedHashSet<>();
        int references;
        int secretsMasked;

        Emitter(ParseResult.Builder out, String configId, String file) {
            this.out = out;
            this.configId = configId;
            this.file = file;
        }

        /** Handles one key/value pair from a config source. */
        void keyValue(String key, String value, int line) {
            if (ConfigSecrets.isSecretKey(key)) {
                secretsMasked++;
                return; // value is a secret: never stored or inspected further
            }
            configureIfClass(key, value, line);
        }

        /** Emits a CONFIGURES reference when {@code value} names a class. */
        void configureIfClass(String key, String value, int line) {
            String className = ClassNames.firstClassName(value);
            if (className == null) {
                return;
            }
            // De-dupe (config → class) pairs so repeated wiring counts once.
            if (!emittedTargets.add(className + "|" + key)) {
                return;
            }
            references++;
            out.relationship(Relationship.builder(RelationshipKind.CONFIGURES, configId, className)
                    .resolved(false)
                    .status(ResolutionStatus.UNRESOLVED)
                    .location(new SourceLocation(file, Math.max(line, 0), Math.max(line, 0), 0, 0))
                    .attribute(EvidenceKeys.TYPE_NAME, className)
                    .attribute("configKey", key)
                    .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + VERSION)
                    .build());
        }
    }

    // ---- format handlers ----

    private void parseProperties(String content, Emitter emitter) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int sep = firstSeparator(line);
            if (sep < 0) {
                continue;
            }
            String key = line.substring(0, sep).strip();
            String value = line.substring(sep + 1).strip();
            emitter.keyValue(key, value, i + 1);
        }
    }

    /**
     * A minimal YAML reader: scalar {@code key: value} pairs, tracking the nesting
     * path for the key. It does not evaluate anchors, block scalars or flow
     * collections — enough to spot class-name values without a YAML dependency.
     */
    private void parseYaml(String content, Emitter emitter) {
        String[] lines = content.split("\n", -1);
        java.util.Deque<int[]> indentStack = new java.util.ArrayDeque<>();
        java.util.Deque<String> keyStack = new java.util.ArrayDeque<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = stripYamlComment(raw).stripTrailing();
            if (line.isBlank() || line.strip().startsWith("-")) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.strip();
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            while (!indentStack.isEmpty() && indentStack.peek()[0] >= indent) {
                indentStack.pop();
                keyStack.pop();
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            String fullKey = keyStack.isEmpty() ? key : String.join(".", reversed(keyStack)) + "." + key;
            if (value.isEmpty()) {
                indentStack.push(new int[]{indent});
                keyStack.push(key);
            } else {
                emitter.keyValue(fullKey, unquote(value), i + 1);
            }
        }
    }

    private void parseXml(String content, Emitter emitter, ParseResult.Builder out) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // XXE hardening: no DTDs, no external entities, no code execution.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setNamespaceAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(new InputSource(new StringReader(content)), new XmlHandler(emitter));
        } catch (Exception e) {
            out.issue(ParseIssue.warning("XML not parsed: " + e.getMessage(), 0));
        }
    }

    /** SAX handler: class-name attribute values and element text become references. */
    private static final class XmlHandler extends DefaultHandler {
        private final Emitter emitter;
        private Locator locator;
        private String currentElement;
        private final StringBuilder text = new StringBuilder();

        XmlHandler(Emitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            currentElement = qName;
            text.setLength(0);
            for (int i = 0; i < attributes.getLength(); i++) {
                String attr = attributes.getQName(i);
                String value = attributes.getValue(i);
                if (ConfigSecrets.isSecretKey(qName + "." + attr) || ConfigSecrets.isSecretKey(attr)) {
                    emitter.secretsMasked++;
                    continue;
                }
                emitter.configureIfClass(qName + "@" + attr, value, line());
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String content = text.toString().strip();
            if (!content.isEmpty() && qName.equals(currentElement)) {
                emitter.configureIfClass(qName, content, line());
            }
            text.setLength(0);
        }

        private int line() {
            return locator != null ? locator.getLineNumber() : 0;
        }
    }

    // ---- small helpers ----

    private static int firstSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static String stripYamlComment(String line) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                && v.charAt(v.length() - 1) == v.charAt(0)) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static Iterable<String> reversed(java.util.Deque<String> stack) {
        java.util.List<String> list = new java.util.ArrayList<>(stack);
        java.util.Collections.reverse(list);
        return list;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
