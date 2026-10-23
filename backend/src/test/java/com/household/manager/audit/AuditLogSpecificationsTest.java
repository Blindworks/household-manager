package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogSpecificationsTest {

    @Mock
    private Root<AuditLog> root;
    @Mock
    private CriteriaQuery<?> query;
    @Mock
    private CriteriaBuilder cb;
    @Mock
    private Path<Object> path;
    @Mock
    private Expression<String> lowered;
    @Mock
    private Predicate predicate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(root.get(anyString())).thenReturn(path);
        when(cb.lower(any())).thenReturn(lowered);
        when(cb.like(any(), anyString(), anyChar())).thenReturn(predicate);
        when(cb.like(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.notLike(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.equal(any(), any(Object.class))).thenReturn(predicate);
        when(cb.notEqual(any(), any(Object.class))).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
        when(path.in(any(java.util.Collection.class))).thenReturn(predicate);
    }

    @Test
    void escape_usesExclamationMark_notBackslash() {
        assertThat(AuditLogSpecifications.escape("50%_a!b")).isEqualTo("50!%!_a!!b");
    }

    @Test
    void query_isLowercasedEscapedAndSearchesHintPatterns() {
        AuditSearchHints hints = new AuditSearchHints(List.of(), List.of("FLOW:13"), List.of("29"),
                List.of("29 -> "), List.of("deviceId=29,"));

        AuditLogSpecifications.of(new AuditLogFilter(null, null, null, null, "Ka_m"), hints)
                .toPredicate(root, query, cb);

        verify(cb, org.mockito.Mockito.times(3)).like(eq(lowered), eq("%ka!_m%"), eq('!'));
        verify(cb).like(any(), eq("29 -> %"), eq('!'));
        verify(cb).like(any(), eq("%deviceId=29,%"), eq('!'));
    }

    @Test
    void systemKind_excludesFlowsAndAgent() {
        AuditLogSpecifications.of(new AuditLogFilter(null, null, Set.of(AuditActorKind.SYSTEM), null, null),
                AuditSearchHints.NONE).toPredicate(root, query, cb);

        verify(cb).equal(path, AuditActorType.SYSTEM);
        verify(cb).notLike(any(Expression.class), eq("FLOW:%"));
        verify(cb).notEqual(path, "AGENT");
    }

    @Test
    void noFilter_addsNoTextOrKindPredicates() {
        AuditLogSpecifications.of(new AuditLogFilter(null, null, Set.of(), Set.of(), " "), AuditSearchHints.NONE)
                .toPredicate(root, query, cb);

        verify(cb, never()).like(any(), anyString(), anyChar());
        verify(cb, never()).or(any(Predicate[].class));
    }
}
