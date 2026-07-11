package com.codeatlas.parser.api;

import com.codeatlas.model.Entity;
import com.codeatlas.model.Relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * The output of parsing one file: the entities and relationships extracted from it,
 * plus any issues encountered.
 *
 * <p>This replaces the spec's stateful {@code extractEntities()/extractRelationships()}
 * pair with a single immutable value, so parsers are stateless and safe to run in
 * parallel across a large repository.
 */
public final class ParseResult {

    private final String relativePath;
    private final String parserId;
    private final List<Entity> entities;
    private final List<Relationship> relationships;
    private final List<ParseIssue> issues;

    private ParseResult(Builder b) {
        this.relativePath = b.relativePath;
        this.parserId = b.parserId;
        this.entities = List.copyOf(b.entities);
        this.relationships = List.copyOf(b.relationships);
        this.issues = List.copyOf(b.issues);
    }

    public String relativePath() {
        return relativePath;
    }

    public String parserId() {
        return parserId;
    }

    public List<Entity> entities() {
        return entities;
    }

    public List<Relationship> relationships() {
        return relationships;
    }

    public List<ParseIssue> issues() {
        return issues;
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ParseIssue.Severity.ERROR);
    }

    public static Builder builder(String parserId, String relativePath) {
        return new Builder(parserId, relativePath);
    }

    /** An empty result, e.g. when a file could not be parsed at all. */
    public static ParseResult empty(String parserId, String relativePath) {
        return new Builder(parserId, relativePath).build();
    }

    public static final class Builder {
        private final String parserId;
        private final String relativePath;
        private final List<Entity> entities = new ArrayList<>();
        private final List<Relationship> relationships = new ArrayList<>();
        private final List<ParseIssue> issues = new ArrayList<>();

        private Builder(String parserId, String relativePath) {
            this.parserId = parserId;
            this.relativePath = relativePath;
        }

        public Builder entity(Entity entity) {
            entities.add(entity);
            return this;
        }

        public Builder entities(List<Entity> toAdd) {
            entities.addAll(toAdd);
            return this;
        }

        public Builder relationship(Relationship relationship) {
            relationships.add(relationship);
            return this;
        }

        public Builder relationships(List<Relationship> toAdd) {
            relationships.addAll(toAdd);
            return this;
        }

        public Builder issue(ParseIssue issue) {
            issues.add(issue);
            return this;
        }

        public ParseResult build() {
            return new ParseResult(this);
        }
    }
}
