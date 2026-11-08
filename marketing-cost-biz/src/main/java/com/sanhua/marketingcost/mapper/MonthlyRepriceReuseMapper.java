package com.sanhua.marketingcost.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import com.sanhua.marketingcost.mapper.typehandler.MonthlyRepriceGroupInputTypeHandler;

/** Minimum storage contract for customer objects and immutable calculation groups. */
@Mapper
public interface MonthlyRepriceReuseMapper {
  record Batch(long id, String businessUnitType, String pricingMonth, String status) {}
  record Group(long id, long batchId, String productCode, String inputDigest,
      String inputDocument, int inputSchemaVersion, String algorithmVersion,
      String feePolicyVersion, String status, int executionNo) {}
  record Ownership(long groupId, String workerId, int executionNo, String inputDigest,
      int inputSchemaVersion, String algorithmVersion, String feePolicyVersion) {}

  @Select("""
      SELECT id,business_unit_type,pricing_month,status FROM lp_monthly_reprice_batch FORCE INDEX (uk_monthly_reprice_no)
      WHERE reprice_no=#{no} AND contract_version=1 FOR UPDATE
      """)
  Batch lockBatch(String no);

  // Registration holds the batch lock in its own READ_COMMITTED transaction. Immutable
  // inputs need no group lock; this statement sees registrations committed before that lock.
  @Select("""
      SELECT id,batch_id,product_code,input_digest,input_document,input_schema_version,
        algorithm_version,fee_policy_version,status,execution_no
      FROM lp_monthly_reprice_compute_group
      WHERE batch_id=#{batch} AND input_digest=#{digest} ORDER BY id
      """)
  @ResultMap("repriceComputeGroup")
  List<Group> candidates(@Param("batch") long batch, @Param("digest") String digest);

  final class NewGroup {
    public Long id;
    public final long batchId;
    public final String productCode;
    public final int schemaVersion;
    public final String digest;
    public final String document;
    public final String algorithmVersion;
    public final String feePolicyVersion;

    public NewGroup(long batchId, String productCode, int schemaVersion, String digest,
        String document, String algorithmVersion, String feePolicyVersion) {
      this.batchId = batchId;
      this.productCode = productCode;
      this.schemaVersion = schemaVersion;
      this.digest = digest;
      this.document = document;
      this.algorithmVersion = algorithmVersion;
      this.feePolicyVersion = feePolicyVersion;
    }
  }

  @Insert("""
      INSERT INTO lp_monthly_reprice_compute_group(batch_id,product_code,input_schema_version,
        input_digest,input_document,algorithm_version,fee_policy_version)
      VALUES(#{batchId},#{productCode},#{schemaVersion},#{digest},
        #{document,typeHandler=com.sanhua.marketingcost.mapper.typehandler.MonthlyRepriceGroupInputTypeHandler},
        #{algorithmVersion},#{feePolicyVersion})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertGroup(NewGroup group);

  @Select("""
      SELECT id,batch_id,product_code,input_digest,input_document,input_schema_version,
        algorithm_version,fee_policy_version,status,execution_no
      FROM lp_monthly_reprice_compute_group WHERE id=#{id}
      """)
  @Results(id="repriceComputeGroup")
  @ConstructorArgs({
      @Arg(column="id",javaType=long.class,id=true),
      @Arg(column="batch_id",javaType=long.class),
      @Arg(column="product_code",javaType=String.class),
      @Arg(column="input_digest",javaType=String.class),
      @Arg(column="input_document",javaType=String.class,typeHandler=MonthlyRepriceGroupInputTypeHandler.class),
      @Arg(column="input_schema_version",javaType=int.class),
      @Arg(column="algorithm_version",javaType=String.class),
      @Arg(column="fee_policy_version",javaType=String.class),
      @Arg(column="status",javaType=String.class),
      @Arg(column="execution_no",javaType=int.class)
  })
  Group readGroup(long id);

  @Select("""
      SELECT id,batch_id,product_code,input_digest,input_document,input_schema_version,
        algorithm_version,fee_policy_version,status,execution_no
      FROM lp_monthly_reprice_compute_group WHERE id=#{id} FOR UPDATE
      """)
  @ResultMap("repriceComputeGroup")
  Group lockGroup(long id);

  @Select("SELECT result_document FROM lp_monthly_reprice_compute_group WHERE id=#{id}")
  String readResult(long id);

  @Update("""
      UPDATE lp_monthly_reprice_compute_group
      SET status='RUNNING',worker_id=#{worker},execution_no=execution_no+1,
        attempt_count=attempt_count+1,lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),
        error_message=NULL
      WHERE id=#{id} AND result_document IS NULL
        AND (status IN ('PENDING','FAILED') OR (status='RUNNING' AND lease_expires_at<=CURRENT_TIMESTAMP(6)))
      """)
  int claim(@Param("id") long id, @Param("worker") String worker, @Param("seconds") int seconds);

  @Update("""
      UPDATE lp_monthly_reprice_compute_group
      SET status='SUCCESS',result_document=#{document},published_at=CURRENT_TIMESTAMP(6),
        worker_id=NULL,lease_expires_at=NULL,error_message=NULL
      WHERE id=#{owner.groupId} AND status='RUNNING' AND result_document IS NULL
        AND worker_id=#{owner.workerId} AND execution_no=#{owner.executionNo}
        AND lease_expires_at>CURRENT_TIMESTAMP(6)
        AND input_digest=#{owner.inputDigest} AND input_schema_version=#{owner.inputSchemaVersion}
        AND algorithm_version=#{owner.algorithmVersion} AND fee_policy_version=#{owner.feePolicyVersion}
      """)
  int publish(@Param("owner") Ownership owner, @Param("document") String document);

  @Update("""
      UPDATE lp_monthly_reprice_compute_group
      SET status='FAILED',worker_id=NULL,lease_expires_at=NULL,error_message=#{message}
      WHERE id=#{owner.groupId} AND status='RUNNING' AND result_document IS NULL
        AND worker_id=#{owner.workerId} AND execution_no=#{owner.executionNo}
        AND lease_expires_at>CURRENT_TIMESTAMP(6)
        AND input_digest=#{owner.inputDigest} AND input_schema_version=#{owner.inputSchemaVersion}
        AND algorithm_version=#{owner.algorithmVersion} AND fee_policy_version=#{owner.feePolicyVersion}
      """)
  int fail(@Param("owner") Ownership owner, @Param("message") String message);

  @Update("""
      UPDATE lp_monthly_reprice_result r JOIN lp_monthly_reprice_compute_group g
        ON g.id=#{groupId} AND g.batch_id=r.reuse_batch_id AND g.product_code=r.product_code
      SET r.compute_group_id=g.id
      WHERE r.reprice_no=#{no} AND r.calc_object_key=#{objectKey} AND r.contract_version=1
        AND g.status='SUCCESS' AND r.calc_status<>'SUCCESS'
        AND (r.compute_group_id IS NULL OR r.compute_group_id=g.id)
      """)
  int bindGroup(@Param("no") String no, @Param("objectKey") String objectKey, @Param("groupId") long groupId);

  @Select("""
      SELECT EXISTS(
        SELECT 1 FROM lp_monthly_reprice_result r JOIN lp_monthly_reprice_compute_group g
          ON g.id=r.compute_group_id AND g.batch_id=r.reuse_batch_id AND g.product_code=r.product_code
        WHERE r.reprice_no=#{no} AND r.calc_object_key=#{objectKey} AND r.contract_version=1
          AND g.id=#{groupId} AND g.status='SUCCESS')
      """)
  boolean alreadyBound(@Param("no") String no, @Param("objectKey") String objectKey, @Param("groupId") long groupId);
}
