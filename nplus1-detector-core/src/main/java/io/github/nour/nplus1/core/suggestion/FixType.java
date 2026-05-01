package io.github.nour.nplus1.core.suggestion;

/**
 * Types of fixes that can resolve N+1 query issues.
 * Each type maps to a specific JPA/Hibernate optimization strategy.
 */
public enum FixType {

    JOIN_FETCH(
            "JOIN FETCH",
            "Use JOIN FETCH in your JPQL/HQL query to eagerly load the association in a single query"
    ),
    ENTITY_GRAPH(
            "@EntityGraph",
            "Use @EntityGraph annotation on the Spring Data repository method to declare eager fetching"
    ),
    BATCH_SIZE(
            "@BatchSize",
            "Add @BatchSize annotation to batch-load lazy collections, reducing N queries to N/batch_size"
    ),
    FETCH_MODE_SUBSELECT(
            "@Fetch(SUBSELECT)",
            "Use @Fetch(FetchMode.SUBSELECT) to load all lazy collections in a single subquery"
    ),
    DTO_PROJECTION(
            "DTO Projection",
            "Use a DTO projection (interface or class) to fetch only required fields without entity graph traversal"
    ),
    QUERY_OPTIMIZATION(
            "Query Rewrite",
            "Rewrite the query to load all data in a single efficient SQL statement"
    ),
    CACHING(
            "Second-Level Cache",
            "Enable Hibernate second-level cache for this frequently-accessed entity/association"
    );

    private final String shortName;
    private final String description;

    FixType(String shortName, String description) {
        this.shortName = shortName;
        this.description = description;
    }

    public String getShortName() { return shortName; }
    public String getDescription() { return description; }
}
