package com.zoro.legaloa.identity;

import java.util.UUID;

public record RequestActor(UUID userId, UUID organizationId, String username, String displayName) {}

