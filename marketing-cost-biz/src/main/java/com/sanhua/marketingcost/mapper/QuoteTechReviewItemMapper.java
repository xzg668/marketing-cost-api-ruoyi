package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechReviewItem;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechReviewItemMapper extends BaseMapper<QuoteTechReviewItem> {

  @Select("""
      SELECT * FROM lp_quote_tech_review_item
       WHERE task_id=#{taskId} AND review_round=#{reviewRound}
       ORDER BY product_id,module_type,id
      """)
  List<QuoteTechReviewItem> selectByTaskRound(
      @Param("taskId") Long taskId, @Param("reviewRound") int reviewRound);

  @Select("SELECT * FROM lp_quote_tech_review_item WHERE id=#{itemId} FOR UPDATE")
  QuoteTechReviewItem selectByIdForUpdate(@Param("itemId") Long itemId);

  @Update("""
      UPDATE lp_quote_tech_review_item
         SET decision=#{decision},decision_reason=#{reason},decided_by=#{actorId},
             decided_by_name=#{actorName},decided_at=#{decidedAt},
             row_version=row_version+1,updated_at=#{decidedAt}
       WHERE id=#{itemId} AND task_id=#{taskId} AND review_round=#{reviewRound}
         AND product_id=#{productId} AND submitted_version_id=#{submittedVersionId}
         AND decision='PENDING' AND row_version=#{expectedVersion}
      """)
  int decidePending(
      @Param("itemId") Long itemId,
      @Param("taskId") Long taskId,
      @Param("reviewRound") int reviewRound,
      @Param("productId") Long productId,
      @Param("submittedVersionId") Long submittedVersionId,
      @Param("expectedVersion") int expectedVersion,
      @Param("decision") String decision,
      @Param("reason") String reason,
      @Param("actorId") Long actorId,
      @Param("actorName") String actorName,
      @Param("decidedAt") LocalDateTime decidedAt);
}
