package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.service.ITNwfmCheckRowService;

/**
 * 周期核收条目 Service业务层处理（batch-process 形状：整批提交）。
 *
 * 条目挂在摊户台账（册次 batch_no）下面：整批要么整批落、要么一笔不落，
 * 台账一动挂在下面的成与不成都跟着这一批走，不许出现插到一半的半截账。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmCheckRowServiceImpl implements ITNwfmCheckRowService {

    private static final int MAX_ROWS = 500;
    /** 条目状态 0待核 1已核 2已处理：批量落账时合法行先按已核认，坏行落“已处理（失败明细）” */
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TNwfmCheckRowMapper nwfmCheckRowMapper;

    @Override
    public TNwfmCheckRow selectTNwfmCheckRowById(Long id) {
        return this.nwfmCheckRowMapper.selectById(id);
    }

    /**
     * 整批提交，一个事务里落：逐行校验，合法行按已核入库、非法行原样保留并记入失败明细，
     * 返回成功行数。行次按交件顺序系统统一排（坏行也占行号，好行不挪窝）。
     * 同一册次重复提交照面不重落；超过单批上限返回 -1 整批不入库；空批次返回 0。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TNwfmCheckRow> rows) {
        if (rows == null || rows.isEmpty() || batchNo == null || batchNo.trim().isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }

        // 幂等：这一册已落过，照面返回当时的成功行数，绝不重插（旧实现坏行会被反复挂进台账）
        Integer already = this.nwfmCheckRowMapper.selectCount(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo).eq("del_flag", 0));
        if (already != null && already.intValue() > 0) {
            Integer okRows = this.nwfmCheckRowMapper.selectCount(new QueryWrapper<TNwfmCheckRow>()
                    .eq("batch_no", batchNo).eq("status", STATUS_OK).eq("del_flag", 0));
            return okRows == null ? 0 : okRows.intValue();
        }

        int seq = 0;
        int ok = 0;
        for (TNwfmCheckRow r : rows) {
            seq++;
            boolean bad = r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0;
            // 册次一律以交件方传入的为准，行次系统排，交上来的值不作数
            r.setId(null);
            r.setBatchNo(batchNo);
            r.setRowNo(Integer.valueOf(seq));
            r.setStatus(Integer.valueOf(bad ? STATUS_FAIL : STATUS_OK));
            r.setDelFlag(Integer.valueOf(0));
            this.nwfmCheckRowMapper.insert(r);
            if (!bad) {
                ok++;
            }
        }
        return ok;
    }

    @Override
    public List<TNwfmCheckRow> listErrors(String batchNo) {
        return this.nwfmCheckRowMapper.selectList(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL)
                .eq("del_flag", 0).orderByAsc("row_no"));
    }
}
