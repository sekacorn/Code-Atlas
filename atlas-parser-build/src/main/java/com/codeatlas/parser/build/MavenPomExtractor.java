package com.codeatlas.parser.build;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Reads a Maven {@code pom.xml} into the facts the model cares about: the module's
 * coordinates and its declared dependencies.
 *
 * <p>Security: XML parsing is hardened against XXE exactly as the configuration
 * parser is — no DTDs, no external entities. Nothing is fetched or executed, and no
 * Maven resolution happens: only what the file literally declares is recorded.
 *
 * <p>Limits (honest by design): property interpolation ({@code ${...}}) is not
 * performed, inherited/parent and dependency-management coordinates are not merged,
 * and profiles are not evaluated. Unresolvable values are recorded verbatim.
 */
final class MavenPomExtractor {

    private MavenPomExtractor() {
    }

    /** One declared dependency coordinate. */
    record Dep(String groupId, String artifactId, String version, String scope) {
        String key() {
            return groupId + ":" + artifactId;
        }
    }

    /** What a pom declares. */
    record Pom(String groupId, String artifactId, String version, String packaging, List<Dep> dependencies) {
    }

    static Pom parse(String content) throws Exception {
        Handler handler = new Handler();
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
        parser.parse(new InputSource(new StringReader(content)), handler);
        return handler.toPom();
    }

    /**
     * Depth-aware SAX handler. Element paths matter: {@code project/groupId} is the
     * module's own coordinate, while {@code project/dependencies/dependency/groupId}
     * belongs to a dependency and {@code project/parent/groupId} to the parent.
     */
    private static final class Handler extends DefaultHandler {

        private final Deque<String> path = new ArrayDeque<>();
        private final StringBuilder text = new StringBuilder();
        private final List<Dep> deps = new ArrayList<>();

        private String groupId;
        private String artifactId;
        private String version;
        private String packaging = "jar";
        private String parentGroupId;
        private String parentVersion;

        private String depGroup;
        private String depArtifact;
        private String depVersion;
        private String depScope;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            path.addLast(qName);
            text.setLength(0);
            if (currentPath().equals("project/dependencies/dependency")) {
                depGroup = null;
                depArtifact = null;
                depVersion = null;
                depScope = null;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String value = text.toString().trim();
            switch (currentPath()) {
                case "project/groupId" -> groupId = value;
                case "project/artifactId" -> artifactId = value;
                case "project/version" -> version = value;
                case "project/packaging" -> packaging = value.isEmpty() ? "jar" : value;
                case "project/parent/groupId" -> parentGroupId = value;
                case "project/parent/version" -> parentVersion = value;
                case "project/dependencies/dependency/groupId" -> depGroup = value;
                case "project/dependencies/dependency/artifactId" -> depArtifact = value;
                case "project/dependencies/dependency/version" -> depVersion = value;
                case "project/dependencies/dependency/scope" -> depScope = value;
                case "project/dependencies/dependency" -> {
                    if (depArtifact != null && !depArtifact.isEmpty()) {
                        deps.add(new Dep(orEmpty(depGroup), depArtifact, orEmpty(depVersion),
                                depScope == null || depScope.isEmpty() ? "compile" : depScope));
                    }
                }
                default -> {
                }
            }
            text.setLength(0);
            path.pollLast();
        }

        private String currentPath() {
            return String.join("/", path);
        }

        Pom toPom() {
            // Maven inherits groupId/version from <parent> when omitted; that
            // inheritance is recorded, not resolved beyond the parent block.
            String g = groupId != null && !groupId.isEmpty() ? groupId : orEmpty(parentGroupId);
            String v = version != null && !version.isEmpty() ? version : orEmpty(parentVersion);
            return new Pom(g, orEmpty(artifactId), v, packaging, List.copyOf(deps));
        }

        private static String orEmpty(String s) {
            return s == null ? "" : s;
        }
    }
}
