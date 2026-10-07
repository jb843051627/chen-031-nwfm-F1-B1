package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmDisputeRuleMapper;
import com.fc.v2.model.auto.TNwfmDisputeRule;
import com.fc.v2.service.ITNwfmDisputeRuleService;

/**
 * 计量争议调解判定规则 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmDisputeRuleServiceImpl implements ITNwfmDisputeRuleService {

    private static final int MAX_STAGE = 3;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TNwfmDisputeRuleMapper nwfmDisputeRuleMapper;

    @Override
    public TNwfmDisputeRule selectTNwfmDisputeRuleById(Long id) {
        return this.nwfmDisputeRuleMapper.selectById(id);
    }

    @Override
    public List<TNwfmDisputeRule> selectTNwfmDisputeRuleList(QueryWrapper<TNwfmDisputeRule> queryWrapper) {
        return this.nwfmDisputeRuleMapper.selectList(queryWrapper);
    }

    @Override
    public TNwfmDisputeRule advance(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        r.setStage(Math.min(st + 2, MAX_STAGE));
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.nwfmDisputeRuleMapper.updateById(r);
        return r;
    }

    @Override
    public TNwfmDisputeRule rollback(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return null;
        }
        r.setStage(0);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.nwfmDisputeRuleMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return false;
        }
        r.setContent(remark);
        return this.nwfmDisputeRuleMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TNwfmDisputeRule r = this.nwfmDisputeRuleMapper.selectById(id);
        if (r == null) {
            return false;
        }
        return this.nwfmDisputeRuleMapper.deleteById(id) > 0;
    }

}
