package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmDeviceRuleMapper;
import com.fc.v2.model.auto.TNwfmDeviceRule;
import com.fc.v2.service.ITNwfmDeviceRuleService;

/**
 * 计量器具判定规则 Service业务层处理（rule-eval 形状）。
 *
 * 判档只认册、不认人，且认的是**秤的当时**生效的那一版：同一编号改版前后是两条时段不同的
 * 版本行，复秤旧批次必须落回当时区间内的旧版算，不能拿现行版倒算旧账。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmDeviceRuleServiceImpl implements ITNwfmDeviceRuleService {

    /** 示值偏差合法区间（%）：负数、大得离谱的数不进秤，拦在门外按不可用(0)处理 */
    private static final BigDecimal DEV_MIN = BigDecimal.ZERO;
    private static final BigDecimal DEV_MAX = new BigDecimal("100");

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
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
            // 同优先级同编号也不许摇摆：新录入的行（id 大）在前
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
            return new ArrayList<TNwfmDeviceRule>();
        }
        // 全量取在册行，生效区间在内存里判：不走 Date 参数绑定，避开 JDBC serverTimezone
        // 与本机时区不对称造成的“切换当日”整体偏移（实测踩到）。
        List<TNwfmDeviceRule> all = this.nwfmDeviceRuleMapper.selectList(
                new QueryWrapper<TNwfmDeviceRule>().eq("del_flag", 0));
        List<TNwfmDeviceRule> avail = new ArrayList<TNwfmDeviceRule>();
        for (TNwfmDeviceRule r : all) {
            if (isEnabledAt(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        // 资料缺 / 数不合法 / 没时刻：不秤，0 = 没依据
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (!isPlausible(input)) {
            return 0;
        }
        TNwfmDeviceRule rule = findRule(ruleCode, at);
        if (rule == null) {
            // 册上没有这编号（或在 at 时刻没有启用且生效的版本）：返回“没依据”，不抛错
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null || !isPlausible(input)) {
            return 0;
        }
        List<TNwfmDeviceRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        // 可用口径多条时，只认册上标得最高（优先级最大）的那条
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TNwfmDeviceRule r = this.nwfmDeviceRuleMapper.selectById(id);
        return r != null && isEnabledAt(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /**
     * 按编号取 at 时刻生效的启用版本。同编号改版 = 时段不同的多行：
     * 只认 [eff_start, eff_end) 罩得住 at、且未撤下的那一条；区间重叠（脏数据）时
     * 按 id 大者（新录入）决胜，保证同一批秤复秤多少遭结果都不摇摆。
     */
    private TNwfmDeviceRule findRule(String ruleCode, Date at) {
        List<TNwfmDeviceRule> hit = this.nwfmDeviceRuleMapper.selectList(
                new QueryWrapper<TNwfmDeviceRule>()
                        .eq("del_flag", 0).eq("rule_code", ruleCode));
        TNwfmDeviceRule picked = null;
        for (TNwfmDeviceRule r : hit) {
            if (!isEnabledAt(r, at)) {
                continue;
            }
            if (picked == null || PRIORITY_THEN_CODE_DESC.compare(r, picked) < 0) {
                picked = r;
            }
        }
        return picked;
    }

    /** 在册、未撤下、at 落在生效区间 [start, end) 内；区间端点留空表示该侧不限 */
    private static boolean isEnabledAt(TNwfmDeviceRule r, Date at) {
        if (r == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag().intValue() == 1) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus().intValue() == 1) {
            return false;
        }
        if (r.getEffStart() != null && at.before(r.getEffStart())) {
            return false;
        }
        if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
            return false;
        }
        return true;
    }

    /** 示值偏差得是 [0, 100] 内的数：负的、大得离谱的不秤 */
    private static boolean isPlausible(BigDecimal input) {
        return input.compareTo(DEV_MIN) >= 0 && input.compareTo(DEV_MAX) <= 0;
    }

    /**
     * 分档：上限是这档的“以内”边界，input 恰好等于上限仍归这档（取低不就高），
     * 例如 th1=0.50 时 0.50 仍是可用档；超过 th3（或某档阈值未维护被越过）才落到下一档。
     */
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
        if (rule.getTh1Max() == null && rule.getTh2Max() == null && rule.getTh3Max() == null) {
            return 0;
        }
        return 4;
    }
}
