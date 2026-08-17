package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.IntegrationInbox;
import java.util.Optional;

public interface IntegrationInboxRepository {

  IntegrationInbox save(IntegrationInbox callback);

  Optional<IntegrationInbox> findByIdempotencyKey(String idempotencyKey);

  Optional<IntegrationInbox> findByCallback(String sourceSystem, String callbackId);
}
