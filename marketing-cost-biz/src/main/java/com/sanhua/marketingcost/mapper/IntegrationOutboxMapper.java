package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntegrationOutboxMapper extends BaseMapper<IntegrationOutbox> {

  @Insert("""
      INSERT INTO lp_integration_outbox (
        event_id, idempotency_key, destination, aggregate_type, aggregate_id,
        aggregate_version, event_type, event_version, payload_json, payload_hash,
        send_policy, send_status, retry_count, occurred_at
      ) VALUES (
        #{eventId}, #{idempotencyKey}, #{destination}, #{aggregateType}, #{aggregateId},
        #{aggregateVersion}, #{eventType}, #{eventVersion}, #{payloadJson}, #{payloadHash},
        #{sendPolicy}, #{sendStatus}, #{retryCount}, #{occurredAt}
      )
      ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
  int insertOrResolveId(IntegrationOutbox event);

  @Select("""
      SELECT * FROM lp_integration_outbox
      WHERE destination = #{destination} AND send_policy = 'AUTO'
        AND send_status = #{sendStatus}
        AND (next_retry_at IS NULL OR next_retry_at <= #{dueAt})
      ORDER BY occurred_at, id
      LIMIT #{limit}
      """)
  List<IntegrationOutbox> selectDispatchable(
      @Param("destination") String destination,
      @Param("sendStatus") String sendStatus,
      @Param("dueAt") LocalDateTime dueAt,
      @Param("limit") int limit);

  @Select("""
      SELECT * FROM lp_integration_outbox WHERE idempotency_key = #{idempotencyKey}
      """)
  IntegrationOutbox selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  @Select("""
      SELECT * FROM lp_integration_outbox
      WHERE aggregate_type = #{aggregateType} AND aggregate_id = #{aggregateId}
      ORDER BY occurred_at DESC, id DESC
      """)
  List<IntegrationOutbox> selectByAggregate(
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") Long aggregateId);
}
