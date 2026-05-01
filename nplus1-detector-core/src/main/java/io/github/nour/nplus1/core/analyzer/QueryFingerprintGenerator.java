package io.github.nour.nplus1.core.analyzer;

import java.util.regex.Pattern;

/**
 * Normalizes SQL queries into "fingerprints" so that structurally
 * identical queries (differing only in parameter values) map to
 * the same fingerprint.
 *
 * <p>Example:
 * <pre>
 *   "SELECT * FROM orders WHERE user_id = 42"
 *   "SELECT * FROM orders WHERE user_id = 99"
 *   Both produce: "select * from orders where user_id = ?"
 * </pre>
 *
 * <p>This is the foundation of N+1 detection — if many queries share
 * the same fingerprint within a single context, it's a strong signal.
 */
public class QueryFingerprintGenerator {

    // Matches string literals: 'anything' (including escaped quotes '')
    private static final Pattern STRING_LITERAL =
            Pattern.compile("'(?:[^']|'')*'");

    // Matches numeric literals: integers, decimals, scientific notation
    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile("\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b");

    // Matches hex literals: 0x1A2B
    private static final Pattern HEX_LITERAL =
            Pattern.compile("0x[0-9a-fA-F]+");

    // Matches boolean literals
    private static final Pattern BOOLEAN_LITERAL =
            Pattern.compile("\\b(TRUE|FALSE)\\b", Pattern.CASE_INSENSITIVE);

    // Matches IN clauses: IN (?, ?, ?) → IN (...)
    private static final Pattern IN_CLAUSE =
            Pattern.compile("\\bIN\\s*\\([^)]+\\)", Pattern.CASE_INSENSITIVE);

    // Collapses multiple whitespace into single space
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Matches LIMIT/OFFSET values
    private static final Pattern LIMIT_OFFSET =
            Pattern.compile("(LIMIT|OFFSET)\\s+\\?", Pattern.CASE_INSENSITIVE);

    // Matches Hibernate-style comments: /* ... */
    private static final Pattern BLOCK_COMMENT =
            Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    // Matches line comments: -- ...
    private static final Pattern LINE_COMMENT =
            Pattern.compile("--[^\n]*");

    // Matches alias variations like t0_, t1_, e0_, e1_ (Hibernate aliases)
    private static final Pattern HIBERNATE_ALIAS =
            Pattern.compile("\\b([a-z])\\d+_", Pattern.CASE_INSENSITIVE);

    /**
     * Generate a normalized fingerprint from a raw SQL query.
     * The fingerprint is lowercase with all literal values replaced by '?'.
     */
    public String generate(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }

        String normalized = sql.trim();

        // 1. Remove comments
        normalized = BLOCK_COMMENT.matcher(normalized).replaceAll("");
        normalized = LINE_COMMENT.matcher(normalized).replaceAll("");

        // 2. Normalize literals (order matters: strings first to avoid partial matches)
        normalized = STRING_LITERAL.matcher(normalized).replaceAll("?");
        normalized = HEX_LITERAL.matcher(normalized).replaceAll("?");
        normalized = NUMERIC_LITERAL.matcher(normalized).replaceAll("?");
        normalized = BOOLEAN_LITERAL.matcher(normalized).replaceAll("?");

        // 3. Normalize IN clauses
        normalized = IN_CLAUSE.matcher(normalized).replaceAll("IN (...)");

        // 4. Normalize Hibernate-generated aliases for better grouping
        normalized = HIBERNATE_ALIAS.matcher(normalized).replaceAll("$1_");

        // 5. Collapse whitespace and lowercase
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        normalized = normalized.toLowerCase().trim();

        return normalized;
    }

    /**
     * Extracts the primary table name from a SQL query.
     * Handles SELECT ... FROM table, INSERT INTO table, UPDATE table, DELETE FROM table.
     */
    public String extractTableName(String sql) {
        if (sql == null) return "unknown";

        String upper = sql.trim().toUpperCase();

        // Strip block comments
        upper = BLOCK_COMMENT.matcher(upper).replaceAll("").trim();

        int tableStart;
        if (upper.startsWith("SELECT") || upper.startsWith("DELETE")) {
            int fromIndex = upper.indexOf(" FROM ");
            if (fromIndex == -1) return "unknown";
            tableStart = fromIndex + 6;
        } else if (upper.startsWith("INSERT")) {
            int intoIndex = upper.indexOf(" INTO ");
            if (intoIndex == -1) return "unknown";
            tableStart = intoIndex + 6;
        } else if (upper.startsWith("UPDATE")) {
            tableStart = 7;
        } else {
            return "unknown";
        }

        String afterKeyword = sql.substring(tableStart).trim();
        String[] parts = afterKeyword.split("[\\s(,]+");
        return parts.length > 0 ? parts[0].replaceAll("[^a-zA-Z0-9_.]", "") : "unknown";
    }
}
