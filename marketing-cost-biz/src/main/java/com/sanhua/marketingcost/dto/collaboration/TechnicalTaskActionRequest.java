package com.sanhua.marketingcost.dto.collaboration;

/** 技术任务写操作只提交乐观锁版本，业务范围由服务端从登录人和任务中解析。 */
public record TechnicalTaskActionRequest(Integer expectedVersion) {}
