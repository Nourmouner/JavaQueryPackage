package io.github.nour.nplus1.core.suggestion;

import io.github.nour.nplus1.core.analyzer.EntityRelationshipAnalyzer;
import io.github.nour.nplus1.core.analyzer.EntityRelationshipAnalyzer.RelationshipInfo;
import io.github.nour.nplus1.core.analyzer.EntityRelationshipAnalyzer.RelationshipType;
import io.github.nour.nplus1.core.analyzer.QueryPatternAnalyzer.DetectedPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Generates actionable fix suggestions based on detected N+1 patterns.
 *
 * <p>Analyzes the SQL to determine what kind of relationship is causing
 * the issue, uses the {@link EntityRelationshipAnalyzer} for context,
 * and recommends fixes in priority order (best fix first).
 */
public class SuggestionEngine {

    private final EntityRelationshipAnalyzer relationshipAnalyzer;

    public SuggestionEngine() {
        this.relationshipAnalyzer = new EntityRelationshipAnalyzer();
    }

    /**
     * Generate fix suggestions for a detected N+1 pattern.
     *
     * @param pattern the detected N+1 pattern
     * @return list of suggestions sorted by priority (best first)
     */
    public List<FixSuggestion> suggest(DetectedPattern pattern) {
        List<FixSuggestion> suggestions = new ArrayList<>();

        String sql = pattern.getSampleSql().toLowerCase(Locale.ROOT);
        String tableName = extractTableFromSelect(sql);
        String entityName = toPascalCase(tableName);
        String fieldName = toCamelCase(tableName);

        // Analyze relationship context
        RelationshipInfo relationship = RelationshipInfo.unknown();
        if (pattern.getInitialQuery() != null) {
            relationship = relationshipAnalyzer.analyze(
                    pattern.getInitialQuery().getSql(),
                    pattern.getSampleSql()
            );
        }

        // 1. JOIN FETCH — always the primary recommendation
        suggestions.add(new FixSuggestion(
                FixType.JOIN_FETCH,
                String.format(
                        "The query on '%s' is executed %d times. "
                        + "Use JOIN FETCH to load this association eagerly in a single query, "
                        + "eliminating all %d redundant queries.",
                        tableName, pattern.getOccurrences(), pattern.getOccurrences() - 1
                ),
                generateJoinFetchExample(entityName, fieldName, relationship),
                1
        ));

        // 2. @EntityGraph — declarative alternative, great for Spring Data
        suggestions.add(new FixSuggestion(
                FixType.ENTITY_GRAPH,
                String.format(
                        "Add @EntityGraph to your Spring Data repository method "
                        + "to eagerly fetch the '%s' association without modifying queries.",
                        fieldName
                ),
                generateEntityGraphExample(entityName, fieldName),
                2
        ));

        // 3. @BatchSize — practical when JOIN FETCH isn't feasible
        if (pattern.getOccurrences() > 5) {
            int batchSize = Math.min(50, pattern.getOccurrences());
            int batchQueries = (int) Math.ceil((double) pattern.getOccurrences() / batchSize);
            suggestions.add(new FixSuggestion(
                    FixType.BATCH_SIZE,
                    String.format(
                            "With %d queries, @BatchSize(size=%d) reduces them to ~%d batch queries. "
                            + "Less optimal than JOIN FETCH but requires minimal code change — "
                            + "just add the annotation to the association.",
                            pattern.getOccurrences(), batchSize, batchQueries
                    ),
                    generateBatchSizeExample(entityName, fieldName, batchSize, relationship),
                    3
            ));
        }

        // 4. SUBSELECT — ideal for very high N+1 counts
        if (pattern.getOccurrences() > 10) {
            suggestions.add(new FixSuggestion(
                    FixType.FETCH_MODE_SUBSELECT,
                    String.format(
                            "SUBSELECT reduces %d queries to exactly 2 queries total (1 for parents, "
                            + "1 subselect for all children). Best when every parent entity needs its children.",
                            pattern.getOccurrences()
                    ),
                    generateSubselectExample(entityName, fieldName, relationship),
                    4
            ));
        }

        // 5. DTO Projection — if full entities aren't needed
        suggestions.add(new FixSuggestion(
                FixType.DTO_PROJECTION,
                "If you don't need the full entity graph, use a DTO projection to fetch "
                + "only the data you need. This avoids lazy-loading traps entirely.",
                generateDTOExample(entityName, fieldName),
                5
        ));

        // 6. Caching — for hot/static data
        if (pattern.getOccurrences() > 20) {
            suggestions.add(new FixSuggestion(
                    FixType.CACHING,
                    String.format(
                            "Consider Hibernate second-level cache for '%s' if this data "
                            + "changes infrequently. Eliminates repeated fetches across requests.",
                            tableName
                    ),
                    generateCachingExample(entityName),
                    6
            ));
        }

        Collections.sort(suggestions);
        return suggestions;
    }

    // ─── Code Example Generators ────────────────────────────────

    private String generateJoinFetchExample(String entity, String field, RelationshipInfo rel) {
        String parentEntity = rel.isKnown() ? toPascalCase(rel.parentTable()) : "Parent";
        return String.format(
                """
                // In your repository or service:
                @Query("SELECT p FROM %s p JOIN FETCH p.%s")
                List<%s> findAllWith%s();
                
                // Or in JPQL:
                entityManager.createQuery(
                    "SELECT p FROM %s p JOIN FETCH p.%s", %s.class
                ).getResultList();""",
                parentEntity, field, parentEntity, entity,
                parentEntity, field, parentEntity
        );
    }

    private String generateEntityGraphExample(String entity, String field) {
        return String.format(
                """
                // On your Spring Data repository method:
                @EntityGraph(attributePaths = {"%s"})
                List<Parent> findAll();
                
                // For nested associations:
                @EntityGraph(attributePaths = {"%s", "%s.nestedField"})
                List<Parent> findAll();""",
                field, field, field
        );
    }

    private String generateBatchSizeExample(String entity, String field, int batchSize,
                                              RelationshipInfo rel) {
        String annotation = rel.isKnown() ? rel.type().getAnnotation() : "@OneToMany";
        return String.format(
                """
                // On the entity association field:
                %s(mappedBy = "parent")
                @BatchSize(size = %d)
                private List<%s> %s;
                
                // Or globally in application.properties:
                spring.jpa.properties.hibernate.default_batch_fetch_size=%d""",
                annotation, batchSize, entity, field, batchSize
        );
    }

    private String generateSubselectExample(String entity, String field, RelationshipInfo rel) {
        String annotation = rel.isKnown() ? rel.type().getAnnotation() : "@OneToMany";
        return String.format(
                """
                // On the entity association field:
                %s(mappedBy = "parent")
                @Fetch(FetchMode.SUBSELECT)
                private List<%s> %s;
                
                // This generates a single SQL like:
                // SELECT * FROM %s WHERE parent_id IN (SELECT id FROM parent)""",
                annotation, entity, field,
                field.toLowerCase()
        );
    }

    private String generateDTOExample(String entity, String field) {
        return String.format(
                """
                // Define a projection interface:
                public interface %sSummary {
                    Long getId();
                    String getName();
                    int get%sCount(); // Use @Value("#{target.%s.size()}")
                }
                
                // Use in repository:
                List<%sSummary> findAllProjectedBy();
                
                // Or use a constructor expression:
                @Query("SELECT new com.example.dto.%sDTO(p.id, p.name, SIZE(p.%s)) "
                     + "FROM Parent p GROUP BY p.id, p.name")
                List<%sDTO> findAllAsDTO();""",
                entity, entity, field, entity, entity, field, entity
        );
    }

    private String generateCachingExample(String entity) {
        return String.format(
                """
                // Add caching annotations to the entity:
                @Entity
                @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
                public class %s {
                    // ...
                    
                    @OneToMany(mappedBy = "parent")
                    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
                    private List<Child> children;
                }
                
                // Enable in application.properties:
                spring.jpa.properties.hibernate.cache.use_second_level_cache=true
                spring.jpa.properties.hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory""",
                entity
        );
    }

    // ─── String Utilities ────────────────────────────────────────

    private String extractTableFromSelect(String sql) {
        int fromIdx = sql.indexOf("from");
        if (fromIdx == -1) return "entity";
        String afterFrom = sql.substring(fromIdx + 4).trim();
        String[] parts = afterFrom.split("[\\s,]+");
        return parts.length > 0 ? parts[0].replaceAll("[^a-zA-Z0-9_]", "") : "entity";
    }

    private String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return "items";
        String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    private String toPascalCase(String s) {
        String camel = toCamelCase(s);
        if (camel.isEmpty()) return "Entity";
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }
}
