package com.sanhua.marketingcost.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** 把协作范围写入现有令牌备注字段，避免为权限范围重复新增数据表。 */
public class CollaborationPortalGrantCodec {
  public static final int CURRENT_VERSION = 1;

  private final ObjectMapper objectMapper;

  public CollaborationPortalGrantCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String encode(Long collaborationId, Set<CollaborationPortalModule> modules) {
    CollaborationPortalGrant grant = validate(
        new CollaborationPortalGrant(CURRENT_VERSION, collaborationId, modules));
    try {
      return objectMapper.writeValueAsString(grant);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("协作权限范围序列化失败", exception);
    }
  }

  public CollaborationPortalGrant decode(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("协作令牌缺少权限范围");
    }
    try {
      return validate(objectMapper.readValue(value, CollaborationPortalGrant.class));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("协作令牌权限范围格式无效", exception);
    }
  }

  private static CollaborationPortalGrant validate(CollaborationPortalGrant grant) {
    if (grant == null || grant.version() != CURRENT_VERSION) {
      throw new IllegalArgumentException("协作令牌权限范围版本无效");
    }
    if (grant.collaborationId() == null || grant.collaborationId() <= 0) {
      throw new IllegalArgumentException("协作令牌未绑定有效任务");
    }
    Set<CollaborationPortalModule> modules = grant.modules();
    if (modules == null || modules.isEmpty() || modules.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("协作令牌未配置可处理模块");
    }
    return new CollaborationPortalGrant(
        CURRENT_VERSION, grant.collaborationId(), EnumSet.copyOf(modules));
  }
}
