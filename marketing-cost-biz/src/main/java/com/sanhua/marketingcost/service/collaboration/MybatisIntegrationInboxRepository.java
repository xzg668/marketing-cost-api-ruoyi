package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.IntegrationInbox;
import com.sanhua.marketingcost.mapper.IntegrationInboxMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisIntegrationInboxRepository implements IntegrationInboxRepository {

  private final IntegrationInboxMapper mapper;

  public MybatisIntegrationInboxRepository(IntegrationInboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public IntegrationInbox save(IntegrationInbox callback) {
    if (callback == null) {
      throw new IllegalArgumentException("收件箱回调不能为空");
    }
    if (mapper.insert(callback) != 1) {
      throw new CollaborationPersistenceException("保存收件箱回调失败");
    }
    return callback;
  }

  @Override
  public Optional<IntegrationInbox> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectByIdempotencyKey(
        CollaborationScope.requireText(idempotencyKey, "收件箱幂等键")));
  }

  @Override
  public Optional<IntegrationInbox> findByCallback(String sourceSystem, String callbackId) {
    return Optional.ofNullable(mapper.selectByCallback(
        CollaborationScope.requireText(sourceSystem, "来源系统"),
        CollaborationScope.requireText(callbackId, "回调ID")));
  }
}
