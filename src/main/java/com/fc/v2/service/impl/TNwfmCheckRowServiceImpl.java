package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.service.ITNwfmCheckRowService;

/**
 * 周期核收条目 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNwfmCheckRowServiceImpl implements ITNwfmCheckRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TNwfmCheckRowMapper nwfmCheckRowMapper;

    @Override
    public TNwfmCheckRow selectTNwfmCheckRowById(Long id) {
        return this.nwfmCheckRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TNwfmCheckRow> rows) {
        String no = rows.get(0).getBatchNo();
        java.util.List<TNwfmCheckRow> errors = new java.util.ArrayList<TNwfmCheckRow>();
        int seq = 0;
        for (TNwfmCheckRow r : rows) {
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                seq++;
                r.setRowNo(Integer.valueOf(seq));
                r.setBatchNo(no);
                r.setStatus(STATUS_FAIL);
                this.nwfmCheckRowMapper.insert(r);
                errors.add(r);
            }
        }
        if (!errors.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (TNwfmCheckRow r : rows) {
            r.setBatchNo(no);
            r.setStatus(STATUS_OK);
            this.nwfmCheckRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<TNwfmCheckRow> listErrors(String batchNo) {
        return this.nwfmCheckRowMapper.selectList(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL));
    }
}
