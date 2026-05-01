package io.github.nour.nplus1.core.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes SQL queries to infer entity relationships involved in N+1 patterns.
 *
 * <p>By examining foreign key references and JOIN structures in the SQL,
 * this analyzer can suggest which JPA associations are causing the problem
 * and provide more targeted fix suggestions.
 */
public class EntityRelationshipAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(EntityRelationshipAnalyzer.class);

    // Matches foreign key patterns like: table.column_id = other_table.id
    private static final Pattern FK_PATTERN = Pattern.compile(
            "(\\w+)\\.(\\w+_id)\\s*=\\s*(\\w+)\\.(\\w+)",
            Pattern.CASE_INSENSITIVE
    );

    // Matches JOIN patterns
    private static final Pattern JOIN_PATTERN = Pattern.compile(
            "(?:LEFT|RIGHT|INNER|OUTER|CROSS)?\\s*JOIN\\s+(\\w+)\\s+(?:AS\\s+)?(\\w+)?\\s+ON\\s+([^\\s]+)\\s*=\\s*([^\\s,)]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Matches WHERE clause foreign key conditions
    private static final Pattern WHERE_FK_PATTERN = Pattern.compile(
            "WHERE\\s+.*?(\\w+)\\.(\\w+)\\s*=\\s*\\?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Analyzes a repeated query and the initial query to determine
     * the relationship between parent and child entities.
     *
     * @param initialSql       the "1" query (parent entity load)
     * @param repeatedSql      the "N" query (child entity loads)
     * @return inferred relationship info
     */
    public RelationshipInfo analyze(String initialSql, String repeatedSql) {
        if (initialSql == null || repeatedSql == null) {
            return RelationshipInfo.unknown();
        }

        try {
            String parentTable = extractPrimaryTable(initialSql);
            String childTable = extractPrimaryTable(repeatedSql);
            String foreignKeyColumn = extractForeignKeyColumn(repeatedSql);
            RelationshipType type = inferRelationshipType(initialSql, repeatedSql);

            return new RelationshipInfo(parentTable, childTable, foreignKeyColumn, type);
        } catch (Exception e) {
            log.debug("Could not analyze entity relationship: {}", e.getMessage());
            return RelationshipInfo.unknown();
        }
    }

    private String extractPrimaryTable(String sql) {
        String upper = sql.toUpperCase().trim();

        // Handle subqueries by looking only at the outermost FROM
        int fromIndex = upper.indexOf(" FROM ");
        if (fromIndex == -1) return "unknown";

        String afterFrom = sql.substring(fromIndex + 6).trim();

        // Remove parenthetical subqueries for primary table extraction
        if (afterFrom.startsWith("(")) return "subquery";

        String[] parts = afterFrom.split("[\\s,]+");
        return parts.length > 0 ? parts[0].replaceAll("[^a-zA-Z0-9_]", "") : "unknown";
    }

    private String extractForeignKeyColumn(String sql) {
        // Look for WHERE ... column = ? pattern (common in N+1 child queries)
        Matcher matcher = WHERE_FK_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(2);
        }

        // Fallback: look for any _id column in conditions
        Pattern idColumn = Pattern.compile("(\\w+_id)\\s*=", Pattern.CASE_INSENSITIVE);
        Matcher idMatcher = idColumn.matcher(sql);
        if (idMatcher.find()) {
            return idMatcher.group(1);
        }

        return "unknown_fk";
    }

    private RelationshipType inferRelationshipType(String initialSql, String repeatedSql) {
        String repeatedUpper = repeatedSql.toUpperCase();

        // If the repeated query selects from a join table, it's likely ManyToMany
        if (repeatedUpper.contains(" JOIN ") && countTables(repeatedSql) >= 2) {
            return RelationshipType.MANY_TO_MANY;
        }

        // If the repeated query has a single FK condition, it's likely OneToMany
        if (repeatedUpper.contains("_ID") && repeatedUpper.contains("WHERE")) {
            return RelationshipType.ONE_TO_MANY;
        }

        // If the repeated query fetches a single row by PK, it's likely ManyToOne
        if (repeatedUpper.contains("WHERE") && repeatedUpper.contains(".ID")) {
            return RelationshipType.MANY_TO_ONE;
        }

        return RelationshipType.UNKNOWN;
    }

    private int countTables(String sql) {
        Set<String> tables = new HashSet<>();

        Matcher joinMatcher = JOIN_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            tables.add(joinMatcher.group(1).toLowerCase());
        }

        String primary = extractPrimaryTable(sql);
        if (!"unknown".equals(primary)) tables.add(primary.toLowerCase());

        return tables.size();
    }

    /**
     * The type of JPA relationship inferred from SQL analysis.
     */
    public enum RelationshipType {
        ONE_TO_MANY("@OneToMany"),
        MANY_TO_ONE("@ManyToOne"),
        MANY_TO_MANY("@ManyToMany"),
        ONE_TO_ONE("@OneToOne"),
        UNKNOWN("Unknown");

        private final String annotation;

        RelationshipType(String annotation) {
            this.annotation = annotation;
        }

        public String getAnnotation() {
            return annotation;
        }
    }

    /**
     * Information about an inferred entity relationship.
     */
    public record RelationshipInfo(
            String parentTable,
            String childTable,
            String foreignKeyColumn,
            RelationshipType type
    ) {
        public static RelationshipInfo unknown() {
            return new RelationshipInfo("unknown", "unknown", "unknown", RelationshipType.UNKNOWN);
        }

        public boolean isKnown() {
            return type != RelationshipType.UNKNOWN;
        }
    }
}
