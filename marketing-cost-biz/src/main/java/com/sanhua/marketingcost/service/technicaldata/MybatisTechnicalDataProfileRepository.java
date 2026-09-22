package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTechnicalDataProfileRepository implements TechnicalDataProfileRepository {
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechProductMapper productMapper;
  private final QuoteTechModuleMapper moduleMapper;
  private final QuoteTechDataVersionMapper versionMapper;

  public MybatisTechnicalDataProfileRepository(
      QuoteTechTaskMapper taskMapper,
      QuoteTechProductMapper productMapper,
      QuoteTechModuleMapper moduleMapper,
      QuoteTechDataVersionMapper versionMapper) {
    this.taskMapper = taskMapper;
    this.productMapper = productMapper;
    this.moduleMapper = moduleMapper;
    this.versionMapper = versionMapper;
  }

  @Override
  public Optional<QuoteTechProduct> findProduct(Long productId) {
    return Optional.ofNullable(productMapper.selectById(productId));
  }

  @Override
  public Optional<QuoteTechProduct> lockProduct(Long productId) {
    return Optional.ofNullable(productMapper.selectByIdForUpdate(productId));
  }

  @Override
  public Optional<QuoteTechTask> lockTask(Long taskId) {
    return Optional.ofNullable(taskMapper.selectByIdForUpdate(taskId));
  }

  @Override
  public Optional<QuoteTechModule> lockProfileModule(Long productId) {
    return Optional.ofNullable(moduleMapper.selectProfileForUpdate(productId));
  }

  @Override
  public Optional<QuoteTechDataVersion> lockVersion(Long versionId) {
    return Optional.ofNullable(versionMapper.selectByIdForUpdate(versionId));
  }

  @Override
  public QuoteTechDataVersion insertVersion(QuoteTechDataVersion version) {
    if (versionMapper.insert(version) != 1 || version.getId() == null) {
      throw new IllegalStateException("产品技术资料草稿创建失败");
    }
    return version;
  }

  @Override
  public int updateDraftProfile(
      QuoteTechDataVersion version, int expectedVersion, LocalDateTime updatedAt) {
    return versionMapper.updateDraftWithVersion(version, expectedVersion, updatedAt);
  }

  @Override
  public int updateProductProfilePointer(
      Long productId,
      Long versionId,
      int expectedVersion,
      LocalDateTime updatedAt) {
    return productMapper.updateProfileDraftWithVersion(
        productId, versionId, expectedVersion, updatedAt);
  }

  @Override
  public int updateProfileModule(
      Long moduleId,
      Long versionId,
      int expectedVersion,
      LocalDateTime updatedAt) {
    return moduleMapper.updateProfileReadyWithVersion(
        moduleId, versionId, expectedVersion, updatedAt);
  }

  @Override
  public int markTaskInProgress(Long taskId, Long actorId, LocalDateTime updatedAt) {
    return taskMapper.markInProgress(taskId, actorId, updatedAt);
  }
}
