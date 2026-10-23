package com.finstep.identity;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record UserPrincipal(UUID id, int authVersion) implements Principal, Serializable {
    @Override public String getName() { return id.toString(); }
}
