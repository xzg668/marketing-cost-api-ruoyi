package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.IntegrationOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IntegrationOutboxRepository {

  IntegrationOutbox save(IntegrationOutbox event);

  /** 并发同键时返回已经存在的事件，不覆盖首次payload。 */
  IntegrationOutbox saveOrGet(IntegrationOutbox event);

  Optional<IntegrationOutbox> findByIdempotencyKey(String idempotencyKey);

  List<IntegrationOutbox> findDispatchable(
      String destination, String sendStatus, LocalDateTime dueAt, int limit);
}
