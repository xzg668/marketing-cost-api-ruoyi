package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.mapper.IntegrationOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisIntegrationOutboxRepository implements IntegrationOutboxRepository {

  private final IntegrationOutboxMapper mapper;

  public MybatisIntegrationOutboxRepository(IntegrationOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public IntegrationOutbox save(IntegrationOutbox event) {
    if (event == null) {
      throw new IllegalArgumentException("发件箱事件不能为空");
    }
    if (mapper.insert(event) != 1) {
      throw new CollaborationPersistenceException("保存发件箱事件失败");
    }
    return event;
  }

  @Override
  public IntegrationOutbox saveOrGet(IntegrationOutbox event) {
    if (event == null) {
      throw new IllegalArgumentException("发件箱事件不能为空");
    }
    mapper.insertOrResolveId(event);
    if (event.getId() == null) {
      throw new CollaborationPersistenceException("保存或读取发件箱事件失败");
    }
    IntegrationOutbox persisted = mapper.selectById(event.getId());
    if (persisted == null) {
      throw new CollaborationPersistenceException("发件箱事件保存后无法读取");
    }
    return persisted;
  }

  @Override
  public Optional<IntegrationOutbox> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectByIdempotencyKey(
        CollaborationScope.requireText(idempotencyKey, "发件箱幂等键")));
  }

  @Override
  public List<IntegrationOutbox> findDispatchable(
      String destination, String sendStatus, LocalDateTime dueAt, int limit) {
    if (dueAt == null) {
      throw new IllegalArgumentException("调度截止时间不能为空");
    }
    if (limit <= 0 || limit > 1000) {
      throw new IllegalArgumentException("调度批量必须在1到1000之间");
    }
    return mapper.selectDispatchable(
        CollaborationScope.requireText(destination, "目标系统"),
        CollaborationScope.requireText(sendStatus, "发送状态"), dueAt, limit);
  }
}
