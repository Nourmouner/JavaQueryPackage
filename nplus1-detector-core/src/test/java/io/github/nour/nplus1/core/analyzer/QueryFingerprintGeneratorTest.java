package io.github.nour.nplus1.core.analyzer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueryFingerprintGenerator")
class QueryFingerprintGeneratorTest {

    private QueryFingerprintGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new QueryFingerprintGenerator();
    }

    @Test
    @DisplayName("should normalize numeric literals to ?")
    void normalizeNumericLiterals() {
        String fp1 = generator.generate("SELECT * FROM users WHERE id = 42");
        String fp2 = generator.generate("SELECT * FROM users WHERE id = 99");

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).contains("?");
        assertThat(fp1).doesNotContain("42");
    }

    @Test
    @DisplayName("should normalize string literals to ?")
    void normalizeStringLiterals() {
        String fp1 = generator.generate("SELECT * FROM users WHERE name = 'Alice'");
        String fp2 = generator.generate("SELECT * FROM users WHERE name = 'Bob'");

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("should normalize IN clauses to IN (...)")
    void normalizeInClauses() {
        String fp1 = generator.generate("SELECT * FROM users WHERE id IN (1, 2, 3)");
        String fp2 = generator.generate("SELECT * FROM users WHERE id IN (4, 5, 6, 7, 8)");

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).contains("in (...)");
    }

    @Test
    @DisplayName("should collapse whitespace")
    void collapseWhitespace() {
        String fp1 = generator.generate("SELECT  *  FROM   users   WHERE  id = 1");
        String fp2 = generator.generate("SELECT * FROM users WHERE id = 1");

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("should lowercase SQL")
    void lowercaseSql() {
        String fp = generator.generate("SELECT * FROM USERS WHERE ID = 1");

        assertThat(fp).isEqualTo(fp.toLowerCase());
    }

    @Test
    @DisplayName("should remove block comments")
    void removeBlockComments() {
        String fp1 = generator.generate("/* HQL query */ SELECT * FROM users WHERE id = 1");
        String fp2 = generator.generate("SELECT * FROM users WHERE id = 1");

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("should handle null and blank input")
    void handleNullAndBlank() {
        assertThat(generator.generate(null)).isEmpty();
        assertThat(generator.generate("")).isEmpty();
        assertThat(generator.generate("   ")).isEmpty();
    }

    @Test
    @DisplayName("should extract table name from SELECT")
    void extractTableName() {
        assertThat(generator.extractTableName("SELECT * FROM users WHERE id = 1"))
                .isEqualTo("users");
        assertThat(generator.extractTableName("SELECT * FROM order_items WHERE order_id = 5"))
                .isEqualTo("order_items");
    }

    @Test
    @DisplayName("should produce different fingerprints for different queries")
    void differentQueriesProduceDifferentFingerprints() {
        String fp1 = generator.generate("SELECT * FROM users WHERE id = 1");
        String fp2 = generator.generate("SELECT * FROM orders WHERE id = 1");

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("should handle Hibernate-style queries with aliases")
    void handleHibernateAliases() {
        String fp1 = generator.generate("SELECT t0_.id, t0_.name FROM users t0_ WHERE t0_.id = 1");
        String fp2 = generator.generate("SELECT t1_.id, t1_.name FROM users t1_ WHERE t1_.id = 2");

        assertThat(fp1).isEqualTo(fp2);
    }
}
