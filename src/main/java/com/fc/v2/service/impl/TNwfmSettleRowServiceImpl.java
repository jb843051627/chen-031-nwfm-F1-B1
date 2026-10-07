package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TNwfmSettleRowMapper;
import com.fc.v2.mapper.auto.TNwfmSettleBaseMapper;
import com.fc.v2.model.auto.TNwfmSettleRow;
import com.fc.v2.model.auto.TNwfmSettleBase;
import com.fc.v2.service.ITNwfmSettleRowService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 清算赔付条目Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TNwfmSettleRowServiceImpl extends ServiceImpl<TNwfmSettleRowMapper, TNwfmSettleRow> implements ITNwfmSettleRowService {

    @Autowired
    private TNwfmSettleBaseMapper nwfmSettleBaseMapper;

    @Override
    public TNwfmSettleRow selectTNwfmSettleRowById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TNwfmSettleRow>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TNwfmSettleRow> selectTNwfmSettleRowList(Wrapper<TNwfmSettleRow> queryWrapper) {
        QueryWrapper<TNwfmSettleRow> wrapper = new QueryWrapper<TNwfmSettleRow>();
        com.github.pagehelper.PageHelper.startPage(1, 10);
        wrapper.eq("status", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTNwfmSettleRow(TNwfmSettleRow record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getItemNo());
        TNwfmSettleBase refArch = nwfmSettleBaseMapper.selectOne(new QueryWrapper<TNwfmSettleBase>()
                .eq("id", record.getBaseId()).eq("del_flag", 0));
        if (refArch == null) {
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            return 0;
        }
        record.setGradeCode(refArch.getGradeCode());
        if (StringUtils.isNotEmpty(record.getItemNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TNwfmSettleRow>()
                    .eq("item_no", record.getItemNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTNwfmSettleRow(TNwfmSettleRow record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (record.getId() != null && StringUtils.isNotEmpty(record.getItemNo())) {
            QueryWrapper<TNwfmSettleRow> dupQ = new QueryWrapper<TNwfmSettleRow>();
            dupQ.eq("item_no", record.getItemNo()).eq("del_flag", 0);
            dupQ.ne("id", record.getId());
            Integer dupCnt = this.baseMapper.selectCount(dupQ);
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TNwfmSettleRow>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTNwfmSettleRowByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTNwfmSettleRowById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
