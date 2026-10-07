package com.fc.v2.service;

import com.fc.v2.model.auto.TNwfmSettleRule;

/**
 * 清算赔付判定规则 Service接口（approval-chain 形状：多阶段签批，无增删改查入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITNwfmSettleRuleService {

    /** 按主键回查单据 */
    TNwfmSettleRule selectTNwfmSettleRuleById(Long id);

    /** 签批一票：返回更新后的单据；被拒返回 null */
    TNwfmSettleRule approve(Long id, String approver, String comment);

    /** 否决：返回更新后的单据；被拒返回 null */
    TNwfmSettleRule reject(Long id, String approver, String comment);

    /** 退回上一环节：返回更新后的单据；被拒返回 null */
    TNwfmSettleRule rollback(Long id, String comment);
}
