package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * 核验作业发号册对象 t_nwfm_job_seq
 * 册次码一律由系统在册上排出来，不许手填。
 *
 * @author fuce
 * @date 2026-10-06
 */
@TableName("t_nwfm_job_seq")
public class TNwfmJobSeq implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 号别：册次码 */
    public static final String KEY_JOB_NO = "JOB_NO";

    /** 号别 */
    @TableId("seq_key")
    @TableField("seq_key")
    private String seqKey;

    /** 下一个号 */
    @TableField("next_val")
    private Long nextVal;

    /** 更新时间 */
    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

    public String getSeqKey() {
        return seqKey;
    }

    public void setSeqKey(String seqKey) {
        this.seqKey = seqKey;
    }

    public Long getNextVal() {
        return nextVal;
    }

    public void setNextVal(Long nextVal) {
        this.nextVal = nextVal;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
