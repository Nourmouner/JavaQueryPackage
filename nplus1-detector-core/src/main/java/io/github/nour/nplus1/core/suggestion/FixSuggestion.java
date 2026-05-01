package io.github.nour.nplus1.core.suggestion;

import java.util.Objects;

/**
 * A concrete fix suggestion with an explanation and code example.
 *
 * <p>Suggestions are sorted by priority — lower priority number = better/preferred fix.
 */
public class FixSuggestion implements Comparable<FixSuggestion> {

    private final FixType type;
    private final String explanation;
    private final String codeExample;
    private final int priority;

    public FixSuggestion(FixType type, String explanation, String codeExample, int priority) {
        this.type = Objects.requireNonNull(type);
        this.explanation = Objects.requireNonNull(explanation);
        this.codeExample = Objects.requireNonNull(codeExample);
        this.priority = priority;
    }

    public FixType getType() { return type; }
    public String getExplanation() { return explanation; }
    public String getCodeExample() { return codeExample; }
    public int getPriority() { return priority; }

    @Override
    public int compareTo(FixSuggestion other) {
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s\n  Example:\n%s", type.getShortName(), explanation, codeExample);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FixSuggestion that = (FixSuggestion) o;
        return type == that.type && Objects.equals(explanation, that.explanation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, explanation);
    }
}
