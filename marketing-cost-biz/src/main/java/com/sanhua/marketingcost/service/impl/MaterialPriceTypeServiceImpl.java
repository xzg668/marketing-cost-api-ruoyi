package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MaterialPriceTypeServiceImpl implements MaterialPriceTypeService {
  private final MaterialPriceTypeMapper mapper;
  private final MaterialPricePriorityService priorities;
  private final SysUserService users;
  private final JdbcTemplate jdbc;

  public MaterialPriceTypeServiceImpl(MaterialPriceTypeMapper mapper, MaterialPricePriorityService priorities,
      SysUserService users, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.priorities = priorities;
    this.users = users;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public MaterialPriceTypePageResponse page(String materialCode, String period, String priceOrgCode,
      int page, int pageSize) {
    String businessUnit = businessUnit();
    String month = FactorPriceScope.month(period);
    var query = Wrappers.lambdaQuery(MaterialPriceType.class)
        .eq(MaterialPriceType::getBusinessUnitType, businessUnit)
        .eq(MaterialPriceType::getPeriod, month)
        .eq(MaterialPriceType::getPriceType, "LINKED")
        .like(StringUtils.hasText(materialCode), MaterialPriceType::getMaterialCode,
            materialCode == null ? null : materialCode.trim())
        .eq(StringUtils.hasText(priceOrgCode), MaterialPriceType::getPriceOrgCode,
            priceOrgCode == null ? null : priceOrgCode.trim())
        .orderByAsc(MaterialPriceType::getMaterialCode).orderByAsc(MaterialPriceType::getPriceOrgCode);
    var result = mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, pageSize))), query);
    return new MaterialPriceTypePageResponse(result.getTotal(), result.getRecords().stream().map(this::view).toList());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MaterialPriceTypePageResponse.Row update(Long id, MaterialPriceTypeRequest request) {
    if (request == null || !StringUtils.hasText(request.expectedRevision())
        || (request.restoreDefault() ? request.order() != null : request.order() == null)) {
      throw new IllegalArgumentException("请提交完整取价顺序或明确恢复默认，并携带当前配置版本");
    }
    var row = mapper.selectOne(Wrappers.lambdaQuery(MaterialPriceType.class)
        .eq(MaterialPriceType::getId, id).eq(MaterialPriceType::getBusinessUnitType, businessUnit()));
    if (row == null) throw new IllegalArgumentException("取价对照不存在或无权修改");
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var user = auth == null ? null : users.findByUsername(auth.getName());
    if (user == null) throw new AccessDeniedException("请重新登录");
    priorities.change(scope(row), request.restoreDefault() ? null : request.order(), request.expectedRevision(), user.getUserId());
    return view(mapper.selectById(id));
  }

  private MaterialPriceTypePageResponse.Row view(MaterialPriceType row) {
    var config = priorities.read(scope(row));
    var latest = jdbc.query("""
        SELECT i.price_source,i.oa_no,i.created_at,
          JSON_UNQUOTE(JSON_EXTRACT(i.input_snapshot_json,'$.pricePriority.revision')) priority_revision
        FROM lp_price_prepare_item i JOIN lp_price_prepare_batch b ON b.prepare_no=i.prepare_no
        WHERE i.business_unit_type=? AND i.period_month=? AND i.price_org_code=? AND i.material_code=?
          AND i.status='READY' AND i.current_flag=1 AND b.scenario_type='OA_LOCKED'
        ORDER BY i.id DESC LIMIT 1
        """, (rs, n) -> new Latest(rs.getString("price_source"), rs.getString("oa_no"),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("priority_revision")),
        row.getBusinessUnitType(), row.getPeriod(), row.getPriceOrgCode(), row.getMaterialCode());
    var hit = latest.isEmpty() ? null : latest.getFirst();
    return new MaterialPriceTypePageResponse.Row(row.getId(), row.getMaterialName(), config,
        hit == null ? null : hit.source(), hit == null ? null : hit.oaNo(), hit == null ? null : hit.time(),
        hit != null && config.revision().equals(hit.revision()), row.getPriorityUpdatedBy(), row.getPriorityUpdatedAt());
  }

  private record Latest(String source, String oaNo, java.time.LocalDateTime time, String revision) {}

  private Scope scope(MaterialPriceType row) {
    return new Scope(row.getBusinessUnitType(), row.getPeriod(), row.getPriceOrgCode(), row.getMaterialCode());
  }

  private String businessUnit() {
    String businessUnit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!StringUtils.hasText(businessUnit)) throw new AccessDeniedException("请先选择业务单元");
    return businessUnit.trim();
  }
}
