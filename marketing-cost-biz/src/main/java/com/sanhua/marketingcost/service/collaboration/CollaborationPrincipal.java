package com.sanhua.marketingcost.service.collaboration;

import java.util.Set;

public record CollaborationPrincipal(
    Long userId,
    String userName,
    Set<CollaborationRole> roles) {

  public CollaborationPrincipal {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public boolean has(CollaborationRole role) {
    return roles.contains(role);
  }

  public CollaborationActor actor() {
    return new CollaborationActor(userId, userName);
  }
}
