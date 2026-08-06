package com.sanhua.marketingcost.service.bomalternative;

import java.util.List;

/**
 * 替代组纯领域快照。
 *
 * <p>只表示结构已经校验通过的候选组，不保存默认/当前报价选择。
 */
public record BomAlternativeGroup(
    BomAlternativeGroupIdentity identity,
    String alternativeGroupKey,
    List<BomAlternativeCandidate> candidates) {

  public BomAlternativeGroup {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  /**
   * 返回结构中唯一的标准件。
   *
   * <p>异常组不能通过“取第一条”的方式偷偷产生默认值。
   */
  public BomAlternativeCandidate standardCandidate() {
    List<BomAlternativeCandidate> standards =
        candidates.stream()
            .filter(candidate -> candidate.childType() == BomChildType.STANDARD)
            .toList();
    if (standards.size() != 1) {
      throw new IllegalStateException(
          "替代组必须恰好一个标准件，当前数量=" + standards.size());
    }
    return standards.get(0);
  }

  /** 返回组内全部替代候选，顺序由解析器保证稳定。 */
  public List<BomAlternativeCandidate> alternativeCandidates() {
    return candidates.stream()
        .filter(candidate -> candidate.childType() == BomChildType.ALTERNATIVE)
        .toList();
  }
}
