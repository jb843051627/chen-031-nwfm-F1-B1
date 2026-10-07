package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TNwfmSettleRow;

import java.util.List;

/**
 * 清算赔付条目 Service接口
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITNwfmSettleRowService {

    /** 按主键查询 */
    TNwfmSettleRow selectTNwfmSettleRowById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<TNwfmSettleRow> selectTNwfmSettleRowList(Wrapper<TNwfmSettleRow> queryWrapper);

    /** 新增 */
    int insertTNwfmSettleRow(TNwfmSettleRow record);

    /** 修改 */
    int updateTNwfmSettleRow(TNwfmSettleRow record);

    /** 批量删除 */
    int deleteTNwfmSettleRowByIds(String ids);

    /** 按主键删除 */
    int deleteTNwfmSettleRowById(Long id);
}
