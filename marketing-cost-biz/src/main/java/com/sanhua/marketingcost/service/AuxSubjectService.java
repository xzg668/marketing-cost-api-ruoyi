package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxSubjectImportRequest;
import com.sanhua.marketingcost.dto.AuxSubjectRequest;
import com.sanhua.marketingcost.entity.AuxSubject;
import java.util.List;

public interface AuxSubjectService {
  Page<AuxSubject> page(String materialCode, String auxSubjectCode, String period,
      int page, int pageSize);

  AuxSubject create(AuxSubjectRequest request);

  AuxSubject update(Long id, AuxSubjectRequest request);

  boolean delete(Long id);

  List<AuxSubject> importItems(AuxSubjectImportRequest request);

  java.math.BigDecimal quoteUnitPrice(String refMaterialCode, String auxSubjectCode,
      String period);
}
