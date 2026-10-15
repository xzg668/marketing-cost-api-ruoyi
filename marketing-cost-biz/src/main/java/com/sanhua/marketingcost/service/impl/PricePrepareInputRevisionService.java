package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 将价格准备事务实际看到的源修订与批次绑定，后续成本不能重新贴上更晚的输入版本。 */
@Service
public class PricePrepareInputRevisionService {
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final PricePrepareBatchMapper batches;
  private final CostInputRevisionService revisions;

  public PricePrepareInputRevisionService(
      OaFormMapper forms,
      OaFormItemMapper items,
      PricePrepareBatchMapper batches,
      CostInputRevisionService revisions) {
    this.forms = forms;
    this.items = items;
    this.batches = batches;
    this.revisions = revisions;
  }

  public boolean isCurrent(String oaNo, Long itemId, String month, String sourceRevision) {
    if (sourceRevision == null) return false;
    var form = forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo));
    var item = items.selectById(itemId);
    return form != null && item != null && form.getId().equals(item.getOaFormId())
        && sourceRevision.equals(revisions.currentRevision(form, item, month));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String bind(
      String oaNo, Long itemId, String month, String oaPrepareNo, String financePrepareNo) {
    var form = forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo));
    var item = items.selectById(itemId);
    if (form == null || item == null || !form.getId().equals(item.getOaFormId()))
      throw new IllegalStateException("价格准备的报价产品归属无效");
    String revision = revisions.currentRevision(form, item, month);
    var source =
        batches.selectOne(
            Wrappers.<PricePrepareBatch>lambdaQuery()
                .eq(PricePrepareBatch::getPrepareNo, oaPrepareNo));
    if (source == null) throw new IllegalStateException("价格准备批次不存在：" + oaPrepareNo);
    if (source.getSourceRevision() != null && !revision.equals(source.getSourceRevision()))
      throw new org.springframework.dao.ConcurrencyFailureException("价格准备后上游输入已变化，请重新生成两侧价格");
    for (String no :
        financePrepareNo == null
            ? java.util.List.of(oaPrepareNo)
            : java.util.List.of(oaPrepareNo, financePrepareNo)) {
      int updated =
          batches.update(
              null,
              Wrappers.<PricePrepareBatch>lambdaUpdate()
                  .eq(PricePrepareBatch::getPrepareNo, no)
                  .set(PricePrepareBatch::getSourceRevision, revision));
      if (updated != 1) throw new IllegalStateException("绑定价格输入修订失败：" + no);
    }
    return revision;
  }
}
