package sotaos.domain.relation

import sotaos.domain.rights.covers

/** Every edge preserves provenance, accountability, scope and the complete validity window. */
fun Authority.coversDelegation(child: Authority): Boolean =
    subject == child.issuer && context == child.context && accountabilityTarget == child.accountabilityTarget &&
        scope.covers(child.scope) && !child.validity.from.isBefore(validity.from) &&
        (child.validity.until == null || child.validity.until.isAfter(child.validity.from)) &&
        (validity.until == null ||
            (child.validity.until != null && !child.validity.until.isAfter(validity.until)))
