package com.example.retail.agent.port;

import com.example.retail.agent.domain.ActionInvocation;
import com.example.retail.agent.domain.ActionOutcome;

/** Execution boundary. Implementations own authorization re-checks, idempotency and effect verification. */
public interface ActionGateway {
    ActionOutcome execute(ActionInvocation invocation);
}
