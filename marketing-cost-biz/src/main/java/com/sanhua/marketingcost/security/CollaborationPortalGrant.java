package com.sanhua.marketingcost.security;

import java.util.Set;

/** 一个外部协作链接的最小授权范围。 */
public record CollaborationPortalGrant(
    int version,
    Long collaborationId,
    Set<CollaborationPortalModule> modules) {

  public CollaborationPortalGrant {
    modules = modules == null ? Set.of() : Set.copyOf(modules);
  }
}
