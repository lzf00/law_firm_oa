package com.zoro.legaloa.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.security.Principal;
import java.util.UUID;

public record SessionSubject(
        int version,
        UUID userId,
        UUID organizationId,
        String username
) implements Principal {
    public static final int CURRENT_VERSION = 1;

    public SessionSubject {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported session subject version");
        }
        if (userId == null || organizationId == null || username == null || username.isBlank()) {
            throw new IllegalArgumentException("Session subject is incomplete");
        }
    }

    public static SessionSubject from(RequestActor actor) {
        return new SessionSubject(
                CURRENT_VERSION,
                actor.userId(),
                actor.organizationId(),
                actor.username()
        );
    }

    @Override
    @JsonIgnore
    public String getName() {
        return username;
    }
}
