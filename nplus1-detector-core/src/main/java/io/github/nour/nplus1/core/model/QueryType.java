package io.github.nour.nplus1.core.model;

import java.util.Locale;

/**
 * Categorizes SQL statements by their operation type.
 * N+1 detection primarily focuses on SELECT queries, but tracking
 * all types provides complete visibility.
 */
public enum QueryType {

    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    OTHER;

    /**
     * Parses the SQL operation type from the raw SQL string.
     * Handles both standard and Hibernate-generated SQL.
     */
    public static QueryType fromSql(String sql) {
        if (sql == null || sql.isBlank()) return OTHER;

        String trimmed = sql.trim().toUpperCase(Locale.ROOT);

        // Strip leading comments (Hibernate often prepends /* ... */)
        if (trimmed.startsWith("/*")) {
            int end = trimmed.indexOf("*/");
            if (end != -1 && end + 2 < trimmed.length()) {
                trimmed = trimmed.substring(end + 2).trim();
            }
        }

        if (trimmed.startsWith("SELECT") || trimmed.startsWith("WITH")) return SELECT;
        if (trimmed.startsWith("INSERT")) return INSERT;
        if (trimmed.startsWith("UPDATE")) return UPDATE;
        if (trimmed.startsWith("DELETE")) return DELETE;
        return OTHER;
    }
}
