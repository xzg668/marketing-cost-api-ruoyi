package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.IntegrationInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntegrationInboxMapper extends BaseMapper<IntegrationInbox> {

  @Select("""
      SELECT * FROM lp_integration_inbox WHERE idempotency_key = #{idempotencyKey}
      """)
  IntegrationInbox selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  @Select("""
      SELECT * FROM lp_integration_inbox
      WHERE source_system = #{sourceSystem} AND callback_id = #{callbackId}
      """)
  IntegrationInbox selectByCallback(
      @Param("sourceSystem") String sourceSystem, @Param("callbackId") String callbackId);
}
