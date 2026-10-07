package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.mapper.auto.TNwfmDeviceRuleMapper;
import com.fc.v2.mapper.auto.TNwfmJobMapper;
import com.fc.v2.mapper.auto.TNwfmJobTrailMapper;
import com.fc.v2.mapper.auto.TNwfmSettleFlowMapper;
import com.fc.v2.mapper.custom.NwfmJobLockDao;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.model.auto.TNwfmDeviceRule;
import com.fc.v2.model.auto.TNwfmJob;
import com.fc.v2.model.auto.TNwfmJobTrail;
import com.fc.v2.model.auto.TNwfmSettleFlow;

/**
 * 核验作业“单行”落账处理器。
 * 每一行一个独立事务（REQUIRES_NEW）：器具册、结算流水、留痕三处同成同败，
 * 一行没落成只回滚这一行，不连累整叠；成败结论由调用方在另一个事务里落账。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Component
public class NwfmJobRowProcessor {

    /** 行状态：候核 / 已核 / 未成（资料不齐·判据状况·落账失败）/ 已冲回 */
    public static final int ROW_WAIT = 0;
    public static final int ROW_DONE = 1;
    public static final int ROW_FAIL = 2;
    public static final int ROW_ROLLED = 6;

    /** 留痕段、动作 */
    private static final int STAGE_EFFECT = 2;
    private static final int STAGE_ROLLBACK = 3;

    @Autowired
    private TNwfmJobMapper jobMapper;
    @Autowired
    private TNwfmCheckRowMapper checkRowMapper;
    @Autowired
    private TNwfmSettleFlowMapper settleFlowMapper;
    @Autowired
    private TNwfmJobTrailMapper trailMapper;
    @Autowired
    private TNwfmDeviceRuleMapper deviceRuleMapper;
    @Autowired
    private NwfmJobLockDao lockDao;

    /** 判据状况：动手前先看这行指着的判据还在不在 */
    public static class RuleException extends RuntimeException {
        public RuleException(String msg) {
            super(msg);
        }
    }

    /**
     * 核验一行。三处任一没落成功，整行事务回滚（由调用方记“没落成”）。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void effectOne(String jobNo, TNwfmJob row, Date at, String actor) {
        // ① 先验判据（不齐 / 撤了 / 过期 / 册上没有 都在这一趟核不成）
        TNwfmDeviceRule rule = resolveRule(row, at);

        // ② 锁器具册上这一号器具的区段：同一处撞车，先到先得、后到等着，不互相覆盖
        List<TNwfmCheckRow> locked = lockDao.lockDeviceRows(row.getDeviceNo());
        boolean alreadyWritten = false;
        for (TNwfmCheckRow c : locked) {
            if (jobNo.equals(c.getBatchNo()) && row.getRowNo() != null
                    && row.getRowNo().equals(c.getRowNo())) {
                alreadyWritten = true;
                break;
            }
        }

        // ③ 三本账一起落
        // 账一：器具册
        if (!alreadyWritten) {
            TNwfmCheckRow reg = new TNwfmCheckRow();
            reg.setBatchNo(jobNo);
            reg.setRowNo(row.getRowNo());
            reg.setItemCode(row.getDeviceNo());
            reg.setQty(row.getJobQty() == null ? null : new BigDecimal(row.getJobQty().intValue()));
            reg.setStallName(row.getJobName());
            reg.setStatus(Integer.valueOf(1));
            reg.setDelFlag(0);
            reg.setRemark("核验落账 判据=" + rule.getRuleCode());
            checkRowMapper.insert(reg);
        }

        // 账二：结算流水（唯一键兜底：同一行结算只一道，重复落不覆盖、报冲突）
        TNwfmSettleFlow flow = new TNwfmSettleFlow();
        flow.setJobNo(jobNo);
        flow.setRowNo(row.getRowNo());
        flow.setDeviceNo(row.getDeviceNo());
        flow.setAmount(row.getAmount());
        flow.setFlowType(TNwfmSettleFlow.TYPE_SETTLE);
        flow.setStatus(Integer.valueOf(1));
        flow.setDelFlag(0);
        try {
            settleFlowMapper.insert(flow);
        } catch (DuplicateKeyException dup) {
            // 并发下后到者：先前已落，照面，不覆盖
            TNwfmSettleFlow exist = settleFlowMapper.selectOne(new QueryWrapper<TNwfmSettleFlow>()
                    .eq("job_no", jobNo).eq("row_no", row.getRowNo())
                    .eq("flow_type", TNwfmSettleFlow.TYPE_SETTLE).last("limit 1"));
            if (exist != null) {
                flow = exist;
            }
        }

        // 账三：行本身落账标记
        row.setWritebackFlag(Integer.valueOf(1));
        row.setVersionStatus(Integer.valueOf(1));
        row.setVersion(rule.getRuleCode());
        row.setStatus(Integer.valueOf(ROW_DONE));
        row.setFailReason(null);
        row.setUpdateBy(actor);
        row.setUpdateTime(at);
        jobMapper.updateById(row);

        // 留痕（也在同一事务里：三处对齐才算落过）
        trail(jobNo, row.getRowNo(), STAGE_EFFECT, "EFFECT_OK", 1,
                "核成 判据=" + rule.getRuleCode() + " 流水=" + flow.getId(), at, actor);
    }

    /**
     * 冲回一行：把这一趟在器具册、结算流水落过的一处一处冲回来。
     * 同一行只冲一次（已冲则照面跳过，绝不重复写冲账流水）。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean rollbackOne(String jobNo, TNwfmJob row, Date at, String actor) {
        if (row.getCompensated() != null && row.getCompensated().intValue() == 1) {
            return false;
        }

        // 冲结算流水：原结算流水置“已冲回”，补一道冲账流水（唯一键防重）
        TNwfmSettleFlow settle = settleFlowMapper.selectOne(new QueryWrapper<TNwfmSettleFlow>()
                .eq("job_no", jobNo).eq("row_no", row.getRowNo())
                .eq("flow_type", TNwfmSettleFlow.TYPE_SETTLE)
                .eq("status", 1).last("limit 1"));
        if (settle != null) {
            settle.setStatus(Integer.valueOf(0));
            settleFlowMapper.updateById(settle);

            TNwfmSettleFlow comp = new TNwfmSettleFlow();
            comp.setJobNo(jobNo);
            comp.setRowNo(row.getRowNo());
            comp.setDeviceNo(row.getDeviceNo());
            comp.setAmount(settle.getAmount());
            comp.setFlowType(TNwfmSettleFlow.TYPE_COMPENSATE);
            comp.setRefFlowId(settle.getId());
            comp.setStatus(Integer.valueOf(1));
            comp.setDelFlag(0);
            try {
                settleFlowMapper.insert(comp);
            } catch (DuplicateKeyException already) {
                // 冲账流水已在：照面，不重复
                return false;
            }
        }

        // 冲器具册：这一趟核成记下的行改回“已处理（已冲）”
        List<TNwfmCheckRow> regs = checkRowMapper.selectList(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", jobNo).eq("row_no", row.getRowNo()).eq("del_flag", 0));
        for (TNwfmCheckRow c : regs) {
            if (c.getStatus() != null && c.getStatus().intValue() == 1) {
                c.setStatus(Integer.valueOf(2));
                checkRowMapper.updateById(c);
            }
        }

        // 行本身：只记一次补偿
        row.setCompensated(Integer.valueOf(1));
        row.setCompensateFlag(Integer.valueOf(1));
        row.setCompensateCnt(Integer.valueOf(1));
        row.setWritebackFlag(Integer.valueOf(0));
        row.setStatus(Integer.valueOf(ROW_ROLLED));
        row.setUpdateBy(actor);
        row.setUpdateTime(at);
        jobMapper.updateById(row);

        trail(jobNo, row.getRowNo(), STAGE_ROLLBACK, "ROLLBACK_OK", 1,
                settle == null ? "退回（无结算流水，仅冲器具册/行标记）"
                        : "补冲账流水 ref=" + settle.getId(), at, actor);
        return true;
    }

    /**
     * 判据核验（口径写死，系统不替人定）：
     * 资料不齐（判据版本/金额/器具编号缺）→ 不成；
     * 判据册上压根没有 → 不成；停用 → 已撤；当前时刻落在生效区间外 → 已过期。
     */
    private TNwfmDeviceRule resolveRule(TNwfmJob row, Date at) {
        boolean incomplete = isBlank(row.getDeviceNo())
                || row.getAmount() == null
                || isBlank(row.getVersion());
        if (incomplete) {
            throw new RuleException("资料不齐：器具编号/金额/判据版本有空缺，按口径本趟不收");
        }
        TNwfmDeviceRule rule = deviceRuleMapper.selectOne(new QueryWrapper<TNwfmDeviceRule>()
                .eq("rule_code", row.getVersion())
                .eq("del_flag", 0)
                .orderByDesc("eff_end").last("limit 1"));
        if (rule == null) {
            throw new RuleException("判据状况：册上查不到判据 " + row.getVersion());
        }
        if (rule.getStatus() != null && rule.getStatus().intValue() == 1) {
            throw new RuleException("判据状况：判据 " + rule.getRuleCode() + " 已撤下");
        }
        Date start = rule.getEffStart();
        Date end = rule.getEffEnd();
        if ((start != null && at.before(start)) || (end != null && !at.before(end))) {
            throw new RuleException("判据状况：判据 " + rule.getRuleCode() + " 已过期");
        }
        return rule;
    }

    private void trail(String jobNo, Integer rowNo, int stage, String action,
                       int result, String detail, Date at, String actor) {
        TNwfmJobTrail t = new TNwfmJobTrail();
        t.setJobNo(jobNo);
        t.setRowNo(rowNo == null ? Integer.valueOf(0) : rowNo);
        t.setStage(Integer.valueOf(stage));
        t.setAction(action);
        t.setResult(Integer.valueOf(result));
        t.setDetail(detail);
        t.setActor(actor);
        t.setActTime(at);
        t.setDelFlag(0);
        trailMapper.insert(t);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
