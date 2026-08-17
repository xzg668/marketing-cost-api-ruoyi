package com.sanhua.marketingcost.service.collaboration;

public class CollaborationOptimisticLockException extends CollaborationPersistenceException {

  public CollaborationOptimisticLockException(String aggregate, Long id, Integer expectedVersion) {
    super(aggregate + "已被其他操作更新，请刷新后重试：id=" + id + ", expectedVersion="
        + expectedVersion);
  }
}
