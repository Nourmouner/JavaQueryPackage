package io.github.nour.nplus1.core.analyzer;

import java.util.regex.Pattern;

public class QueryFingerprintGenerator {

    private static final Pattern STRING_LITERAL  = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern HEX_LITERAL     = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b");
    private static final Pattern BOOLEAN_LITERAL = Pattern.compile("\\b(TRUE|FALSE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_CLAUSE       = Pattern.compile("\\bIN\\s*\\([^)]+\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE      = Pattern.compile("\\s+");
    private static final Pattern BLOCK_COMMENT   = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT    = Pattern.compile("--[^\n]*");

    /**
     * Hibernate-generated aliases like " t0_." or "AS e1_" — we only normalize
     * ones followed by '.' or whitespace/end so we never touch real column names
     * such as "user_id_0_".
     */
    private static final Pattern HIBERNATE_ALIAS_DOT =
            Pattern.compile("\\b([a-z])\\d+_(?=\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIBERNATE_ALIAS_AS =
            Pattern.compile("\\bas\\s+([a-z])\\d+_\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIBERNATE_ALIAS_FROM =
            Pattern.compile("(\\bfrom\\s+\\w+\\s+)([a-z])\\d+_\\b", Pattern.CASE_INSENSITIVE);

    public String generate(String sql) {
        if (sql == null || sql.isBlank()) return "";
        String s = sql.trim();
        s = BLOCK_COMMENT.matcher(s).replaceAll("");
        s = LINE_COMMENT.matcher(s).replaceAll("");
        s = STRING_LITERAL.matcher(s).replaceAll("?");
        s = HEX_LITERAL.matcher(s).replaceAll("?");
        s = NUMERIC_LITERAL.matcher(s).replaceAll("?");
        s = BOOLEAN_LITERAL.matcher(s).replaceAll("?");
        s = IN_CLAUSE.matcher(s).replaceAll("IN (...)");
        s = HIBERNATE_ALIAS_DOT.matcher(s).replaceAll("$1_");
        s = HIBERNATE_ALIAS_AS.matcher(s).replaceAll("as $1_");
        s = HIBERNATE_ALIAS_FROM.matcher(s).replaceAll("$1$2_");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s.toLowerCase().trim();
    }

    public String extractTableName(String sql) {
        if (sql == null) return "unknown";
        String upper = BLOCK_COMMENT.matcher(sql.trim().toUpperCase()).replaceAll("").trim();
        int tableStart;
        if (upper.startsWith("SELECT") || upper.startsWith("DELETE")) {
            int i = upper.indexOf(" FROM "); if (i < 0) return "unknown";
            tableStart = i + 6;
        } else if (upper.startsWith("INSERT")) {
            int i = upper.indexOf(" INTO "); if (i < 0) return "unknown";
            tableStart = i + 6;
        } else if (upper.startsWith("UPDATE")) {
            tableStart = 7;
        } else return "unknown";
        String[] parts = sql.substring(tableStart).trim().split("[\\s(,]+");
        return parts.length > 0 ? parts[0].replaceAll("[^a-zA-Z0-9_.]", "") : "unknown";
    }
}