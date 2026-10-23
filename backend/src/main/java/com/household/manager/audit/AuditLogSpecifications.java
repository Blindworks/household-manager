package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Übersetzt einen {@link AuditLogFilter} in eine JPA-Abfrage. LIKE-Muster escapen mit {@code !}
 * statt Backslash — MariaDB wertet den Backslash im Literal selbst aus (Lehre aus dem Systemprotokoll).
 */
final class AuditLogSpecifications {

    private static final char ESCAPE = '!';
    private static final String FLOW_ACTOR_PREFIX = "FLOW:";
    private static final String AGENT_ACTOR = "AGENT";

    private AuditLogSpecifications() {
    }

    static Specification<AuditLog> of(AuditLogFilter filter, AuditSearchHints hints) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), filter.to()));
            }
            if (filter.actorKinds() != null && !filter.actorKinds().isEmpty()) {
                predicates.add(cb.or(filter.actorKinds().stream()
                        .map(kind -> actorKind(kind, root, cb)).toArray(Predicate[]::new)));
            }
            if (filter.actions() != null && !filter.actions().isEmpty()) {
                predicates.add(root.get("action").in(filter.actions()));
            }
            if (filter.query() != null && !filter.query().isBlank()) {
                predicates.add(text(filter.query().trim(), hints, root, cb));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate actorKind(AuditActorKind kind, Root<AuditLog> root, CriteriaBuilder cb) {
        Expression<String> actor = root.get("actor");
        return switch (kind) {
            case PERSON -> cb.equal(root.get("actorType"), AuditActorType.USER);
            case SERVICE -> cb.equal(root.get("actorType"), AuditActorType.SERVICE);
            case TELEGRAM -> cb.equal(root.get("actorType"), AuditActorType.TELEGRAM);
            case FLOW -> cb.like(actor, FLOW_ACTOR_PREFIX + "%");
            case AGENT -> cb.equal(actor, AGENT_ACTOR);
            case SYSTEM -> cb.and(cb.equal(root.get("actorType"), AuditActorType.SYSTEM),
                    cb.notLike(actor, FLOW_ACTOR_PREFIX + "%"), cb.notEqual(actor, AGENT_ACTOR));
        };
    }

    private static Predicate text(String query, AuditSearchHints hints, Root<AuditLog> root, CriteriaBuilder cb) {
        String pattern = "%" + escape(query.toLowerCase(Locale.ROOT)) + "%";
        Expression<String> actor = root.get("actor");
        Expression<String> action = root.get("action");
        Expression<String> detail = root.get("detail");
        List<Predicate> any = new ArrayList<>(List.of(
                cb.like(cb.lower(actor), pattern, ESCAPE),
                cb.like(cb.lower(action), pattern, ESCAPE),
                cb.like(cb.lower(detail), pattern, ESCAPE)));
        if (!hints.actions().isEmpty()) {
            any.add(action.in(hints.actions()));
        }
        if (!hints.actors().isEmpty()) {
            any.add(actor.in(hints.actors()));
        }
        if (!hints.detailsExact().isEmpty()) {
            any.add(detail.in(hints.detailsExact()));
        }
        hints.detailsPrefix().forEach(prefix -> any.add(cb.like(detail, escape(prefix) + "%", ESCAPE)));
        hints.detailsContain().forEach(part -> any.add(cb.like(detail, "%" + escape(part) + "%", ESCAPE)));
        return cb.or(any.toArray(Predicate[]::new));
    }

    static String escape(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
