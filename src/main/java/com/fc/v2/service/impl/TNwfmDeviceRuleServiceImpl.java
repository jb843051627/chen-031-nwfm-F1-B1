package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmDeviceRuleMapper;
import com.fc.v2.model.auto.TNwfmDeviceRule;
import com.fc.v2.service.ITNwfmDeviceRuleService;

/**
 * 计量器具判定规则 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmDeviceRuleServiceImpl implements ITNwfmDeviceRuleService {

    /** 示值偏差(%)合法区间：不接受负值，超过 100% 视为离谱值，一律拦在门外不判档 */
    private static final BigDecimal MAX_INPUT = new BigDecimal("100");

    /** 优先级降序；并列按 ruleCode 降序、再按 id 降序（确定性，不靠库返回顺序） */
    private static final Comparator<TNwfmDeviceRule> PRIORITY_THEN_CODE_DESC = new Comparator<TNwfmDeviceRule>() {
        @Override
        public int compare(TNwfmDeviceRule a, TNwfmDeviceRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            long ia = a.getId() == null ? 0L : a.getId().longValue();
            long ib = b.getId() == null ? 0L : b.getId().longValue();
            return Long.compare(ib, ia);
        }
    };

    @javax.annotation.Resource
    private TNwfmDeviceRuleMapper nwfmDeviceRuleMapper;

    @Override
    public TNwfmDeviceRule selectTNwfmDeviceRuleById(Long id) {
        return this.nwfmDeviceRuleMapper.selectById(id);
    }

    @Override
    public List<TNwfmDeviceRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TNwfmDeviceRule>();
        }
        // 窗口在 Java 侧判，不把 java.util.Date 绑进 SQL（JDBC serverTimezone 与本机
        // 时区不对称，绑定参数会整体偏移，切换当日边界会随机错档）；先在库上拦 启用/未删
        List<TNwfmDeviceRule> all = this.nwfmDeviceRuleMapper.selectList(
                new QueryWrapper<TNwfmDeviceRule>().eq("del_flag", 0).eq("status", 0));
        List<TNwfmDeviceRule> avail = new java.util.ArrayList<TNwfmDeviceRule>();
        for (TNwfmDeviceRule r : all) {
            if (inWindow(r, at)) {
                avail.add(r);
            }
        }
        java.util.Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        // 负数偏差、离谱偏差本就不该进门，不替它秤出任何档
        if (!inputValid(input)) {
            return 0;
        }
        TNwfmDeviceRule rule = findRule(ruleCode, at);
        if (rule == null) {
            // 册上没有这版口径（含已撤下/已过期）：没依据，不判档
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (!inputValid(input)) {
            return 0;
        }
        List<TNwfmDeviceRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        // listAvailable 已按优先级降序排定，头一条就是册上标得最高的口径
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TNwfmDeviceRule r = this.nwfmDeviceRuleMapper.selectById(id);
        return isAvailable(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
    }

    /**
     * 按 ruleCode 定位 at 时刻生效的那版口径：未删、启用、且 at 落在其生效窗口内。
     * 改版即新版一行新窗口，复秤旧账传旧时刻仍取到旧版，处置不会随改版漂。
     * 同一编号窗口理论上不重叠；万一重叠，取生效起始最新的一版（确定性，不靠库返回顺序）。
     */
    private TNwfmDeviceRule findRule(String ruleCode, Date at) {
        List<TNwfmDeviceRule> hit = this.nwfmDeviceRuleMapper.selectList(new QueryWrapper<TNwfmDeviceRule>()
                .eq("del_flag", 0).eq("status", 0).eq("rule_code", ruleCode));
        TNwfmDeviceRule picked = null;
        for (TNwfmDeviceRule r : hit) {
            if (!inWindow(r, at)) {
                continue;
            }
            if (picked == null || effStartOf(r).after(effStartOf(picked))) {
                picked = r;
            }
        }
        return picked;
    }

    /** 口径在 at 时刻可用：未删、启用（撤下开关必须真生效）、在生效窗口内（起含止不含） */
    private boolean isAvailable(TNwfmDeviceRule r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus().intValue() != 0) {
            return false;
        }
        return inWindow(r, at);
    }

    /** at 落在 [effStart, effEnd)；两端为空分别视为不限 */
    private boolean inWindow(TNwfmDeviceRule r, Date at) {
        Date start = r.getEffStart();
        Date end = r.getEffEnd();
        if (start != null && at.before(start)) {
            return false;
        }
        if (end != null && !at.before(end)) {
            return false;
        }
        return true;
    }

    private static Date effStartOf(TNwfmDeviceRule r) {
        return r.getEffStart() == null ? new Date(Long.MIN_VALUE) : r.getEffStart();
    }

    private static boolean inputValid(BigDecimal input) {
        return input.signum() >= 0 && input.compareTo(MAX_INPUT) <= 0;
    }

    /** 分档：等于上限取高一档 */
    private int levelOf(TNwfmDeviceRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
