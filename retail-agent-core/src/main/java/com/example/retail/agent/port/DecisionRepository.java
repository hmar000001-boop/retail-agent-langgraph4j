package com.example.retail.agent.port;

import com.example.retail.agent.domain.Decision;

/** Append-only audit boundary for decisions and authority attribution. */
public interface DecisionRepository {
    void append(String subjectId, Decision decision);
}
