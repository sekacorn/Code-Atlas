package com.codeatlas.parser.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a GNAT project file ({@code .gpr}) with a deterministic line scanner.
 *
 * <p>The high-value fact here is {@code for Main use ("x.adb", ...)}: a GNAT project
 * <em>declares</em> its executable entry points, which is far stronger evidence than
 * any naming or shape heuristic. Also extracted: the project name, {@code with}ed
 * projects, and source directories.
 *
 * <p>Limits (honest by design): variables, {@code package} blocks, scenario
 * variables ({@code external(...)}), renamings and expressions are not evaluated —
 * only literal declarations are recorded.
 */
final class GnatProjectExtractor {

    private static final Pattern PROJECT =
            Pattern.compile("^\\s*(?:abstract\\s+)?project\\s+([\\w.]+)\\s+is", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH =
            Pattern.compile("^\\s*with\\s+\"([^\"]+)\"\\s*;", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_MAIN =
            Pattern.compile("\\bfor\\s+Main\\s+use\\s*\\((.*?)\\)\\s*;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FOR_SOURCE_DIRS =
            Pattern.compile("\\bfor\\s+Source_Dirs\\s+use\\s*\\((.*?)\\)\\s*;",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private GnatProjectExtractor() {
    }

    /** A declared main unit and the line that declares it. */
    record Main(String unit, int line) {
    }

    /** What a GNAT project declares. */
    record GnatProject(String name, List<Main> mains, Set<String> withedProjects, Set<String> sourceDirs) {
    }

    static GnatProject parse(String content) {
        String name = null;
        Set<String> withed = new LinkedHashSet<>();
        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]);
            Matcher m;
            if (name == null && (m = PROJECT.matcher(line)).find()) {
                name = m.group(1);
            }
            if ((m = WITH.matcher(line)).find()) {
                withed.add(projectNameOf(m.group(1)));
            }
        }

        // Main/Source_Dirs lists may span lines, so match over the whole (comment-
        // stripped) text and map each hit back to its line for evidence.
        String stripped = stripComments(content);
        List<Main> mains = new ArrayList<>();
        Matcher mainMatcher = FOR_MAIN.matcher(stripped);
        while (mainMatcher.find()) {
            int lineNo = lineOf(stripped, mainMatcher.start());
            for (String unit : quoted(mainMatcher.group(1))) {
                mains.add(new Main(unitName(unit), lineNo));
            }
        }
        Set<String> sourceDirs = new LinkedHashSet<>();
        Matcher dirs = FOR_SOURCE_DIRS.matcher(stripped);
        while (dirs.find()) {
            sourceDirs.addAll(quoted(dirs.group(1)));
        }
        return new GnatProject(name, List.copyOf(mains), withed, sourceDirs);
    }

    /** "mission_main.adb" -> "mission_main" (the Ada unit the file defines). */
    private static String unitName(String file) {
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        return slash >= 0 ? base.substring(slash + 1) : base;
    }

    /** "core.gpr" / "../lib/core.gpr" -> "core". */
    private static String projectNameOf(String withPath) {
        return unitName(withPath);
    }

    private static List<String> quoted(String list) {
        List<String> out = new ArrayList<>();
        Matcher q = QUOTED.matcher(list);
        while (q.find()) {
            out.add(q.group(1).trim());
        }
        return out;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Removes "--" comments while preserving line structure (offsets stay valid). */
    private static String stripComments(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : content.split("\r?\n", -1)) {
            sb.append(stripComment(line)).append('\n');
        }
        return sb.toString();
    }

    private static String stripComment(String line) {
        int dash = line.indexOf("--");
        return dash >= 0 ? line.substring(0, dash) : line;
    }
}
