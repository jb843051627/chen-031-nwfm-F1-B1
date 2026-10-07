package com.fc.v2.mapper.custom;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.model.auto.TNwfmJob;

/**
 * 核验作业行锁/发号：一律 SELECT ... FOR UPDATE，后到等着、不互相覆盖。
 *
 * @author fuce
 * @date 2026-10-06
 */
public interface NwfmJobLockDao {

    /** 锁住发号册取当前号（调用方须在事务内） */
    @Select("SELECT next_val FROM t_nwfm_job_seq WHERE seq_key = #{key} FOR UPDATE")
    Long selectSeqForUpdate(@Param("key") String key);

    /** 推进发号册 */
    @Update("UPDATE t_nwfm_job_seq SET next_val = #{val}, update_time = NOW() WHERE seq_key = #{key}")
    int advanceSeq(@Param("key") String key, @Param("val") long val);

    /** 锁册首（row_no=0）：状态机推进、退回互斥都先锁它 */
    @Select("SELECT * FROM t_nwfm_job WHERE job_no = #{jobNo} AND row_no = 0 AND del_flag = 0 FOR UPDATE")
    TNwfmJob lockJobHead(@Param("jobNo") String jobNo);

    /** 锁器具册上同一器具的行：不同器具各锁各的区段，同一器具后到等先到 */
    @Select("SELECT * FROM t_nwfm_check_row WHERE item_code = #{deviceNo} AND del_flag = 0 FOR UPDATE")
    List<TNwfmCheckRow> lockDeviceRows(@Param("deviceNo") String deviceNo);
}
