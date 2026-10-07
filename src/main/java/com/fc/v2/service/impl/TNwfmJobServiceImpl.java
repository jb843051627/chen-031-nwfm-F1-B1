package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmJobMapper;
import com.fc.v2.mapper.auto.TNwfmJobSeqMapper;
import com.fc.v2.mapper.auto.TNwfmJobTrailMapper;
import com.fc.v2.mapper.custom.NwfmJobLockDao;
import com.fc.v2.model.auto.TNwfmJob;
import com.fc.v2.model.auto.TNwfmJobSeq;
import com.fc.v2.model.auto.TNwfmJobTrail;
import com.fc.v2.service.ITNwfmJobService;
import com.fc.v2.shiro.util.ShiroUtils;

/**
 * 器具核验作业 Service实现。
 *
 * 一叠核验需求的全生命周期，按册上写死的口径走（系统不替人定）：
 * 交件(待审) → 审过(候核/核验中) → 已核/部分核成 → 退回中 → 已退；
 * 段不可跳、终态不可再推、退到一半不可往前核。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TNwfmJobServiceImpl implements ITNwfmJobService {

    // 作业状态机常量（册上写死，不许跳段）
    /** 草稿 */
    private static final int ST_DRAFT = 0;
    /** 待审（交上来先候着） */
    private static final int ST_PENDING = 1;
    /** 已审候核 / 核验中（同步落账，落完即转 3/4） */
    private static final int ST_EFFECTING = 2;
    /** 已核完 */
    private static final int ST_EFFECTED = 3;
    /** 部分核成（有行没落成） */
    private static final int ST_PARTIAL = 4;
    /** 退回中 */
    private static final int ST_ROLLING = 5;
    /** 已退 */
    private static final int ST_ROLLED = 6;

    /** 留痕段 */
    private static final int STAGE_SUBMIT = 1;
    private static final int STAGE_EFFECT = 2;
    private static final int STAGE_ROLLBACK = 3;

    @Autowired
    private TNwfmJobMapper nwfmJobMapper;
    @Autowired
    private TNwfmJobSeqMapper seqMapper;
    @Autowired
    private TNwfmJobTrailMapper trailMapper;
    @Autowired
    private NwfmJobLockDao lockDao;
    @Autowired
    private NwfmJobRowProcessor rowProcessor;

    @Override
    public TNwfmJob selectTNwfmJobById(Long id) {
        if (id == null) {
            return null;
        }
        return this.nwfmJobMapper.selectById(id);
    }

    @Override
    public TNwfmJob selectByJobNo(String jobNo) {
        if (jobNo == null) {
            return null;
        }
        return this.nwfmJobMapper.selectOne(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitJob(String reqNo, List<TNwfmJob> rows) {
        if (reqNo == null || reqNo.trim().isEmpty()) {
            throw new IllegalArgumentException("交件请求号不能为空：反复递进要靠它照面");
        }
        // 幂等：同一请求号递进，第二遍只当照面，返回已排定的册次码，不另起一叠
        TNwfmJob exist = this.nwfmJobMapper.selectOne(new QueryWrapper<TNwfmJob>()
                .eq("req_no", reqNo).eq("row_no", 0).eq("del_flag", 0).last("limit 1"));
        if (exist != null) {
            return exist.getJobNo();
        }

        // 册次码由系统在册上排出来（FOR UPDATE 串行取号），谁也不许手填
        String jobNo = nextJobNo();

        TNwfmJob head = new TNwfmJob();
        head.setJobNo(jobNo);
        head.setReqNo(reqNo);
        head.setStatus(Integer.valueOf(ST_PENDING));
        head.setRowNo(Integer.valueOf(0));
        head.setTotalRows(Integer.valueOf(rows == null ? 0 : rows.size()));
        head.setDoneRows(Integer.valueOf(0));
        head.setFailRows(Integer.valueOf(0));
        head.setDelFlag(Integer.valueOf(0));
        this.nwfmJobMapper.insert(head);

        // 行次系统从上往下排，交上来不许挪窝；脏行不炸整批，留着到核验时按口径判“没落成”
        if (rows != null) {
            int idx = 0;
            for (TNwfmJob r : rows) {
                idx++;
                r.setId(null);
                r.setJobNo(jobNo);
                // req_no 只落册首（册首唯一键保证一请求只收一叠），行上不冗余
                r.setRowNo(Integer.valueOf(idx));
                r.setStatus(Integer.valueOf(NwfmJobRowProcessor.ROW_WAIT));
                r.setWritebackFlag(Integer.valueOf(0));
                r.setVersionStatus(Integer.valueOf(0));
                r.setCompensateFlag(Integer.valueOf(0));
                r.setCompensated(Integer.valueOf(0));
                r.setCompensateCnt(Integer.valueOf(0));
                r.setFailReason(null);
                r.setDelFlag(Integer.valueOf(0));
                this.nwfmJobMapper.insert(r);
            }
        }

        trail(jobNo, 0, STAGE_SUBMIT, "SUBMIT", 1,
                "交件候审 请求号=" + reqNo + " 行数=" + (rows == null ? 0 : rows.size()));
        return jobNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer approveJob(String jobNo) {
        if (jobNo == null || jobNo.trim().isEmpty()) {
            return null;
        }
        // 锁册首：与交件、核验、退回用同一把册锁，后到等着
        TNwfmJob head = lockDao.lockJobHead(jobNo);
        if (head == null) {
            return null;
        }
        int st = head.getStatus() == null ? ST_DRAFT : head.getStatus().intValue();
        // 只有候着(待审)的这叠能放行；已放行/越段重复放行不改账
        if (st != ST_PENDING) {
            return null;
        }
        head.setStatus(Integer.valueOf(ST_EFFECTING));
        head.setUpdateBy(actor());
        head.setUpdateTime(new Date());
        this.nwfmJobMapper.updateById(head);
        trail(jobNo, 0, STAGE_SUBMIT, "TRANSIT", 1, "审过放行 待审→候核");
        return Integer.valueOf(ST_EFFECTING);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int effectJob(String jobNo) {
        TNwfmJob head = lockDao.lockJobHead(jobNo);
        if (head == null) {
            return 0;
        }
        int st = head.getStatus() == null ? ST_DRAFT : head.getStatus().intValue();
        // 只有“已审候核”能动手：待审(没审)、核完/部分(已核过)、退回中/已退 都不许再往前核
        if (st != ST_EFFECTING) {
            throw new IllegalStateException("当前段(" + st + ")不许核验：候着未审/已核完/退回中/已退 的不受理，照册上的来");
        }

        Date now = new Date();
        String actor = actor();
        List<TNwfmJob> items = this.nwfmJobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).ne("row_no", 0).eq("del_flag", 0)
                .orderByAsc("row_no"));

        int done = 0;
        int fail = 0;
        for (TNwfmJob row : items) {
            try {
                // 每行独立事务：三本账同成同败；这行炸了只回这行，不推翻整叠
                rowProcessor.effectOne(jobNo, row, now, actor);
                done++;
            } catch (Exception ex) {
                fail++;
                String reason = trim(ex.getMessage());
                row.setStatus(Integer.valueOf(NwfmJobRowProcessor.ROW_FAIL));
                row.setWritebackFlag(Integer.valueOf(0));
                row.setFailReason(reason);
                row.setUpdateBy(actor);
                row.setUpdateTime(now);
                this.nwfmJobMapper.updateById(row);
                trail(jobNo, row.getRowNo(), STAGE_EFFECT, "EFFECT_FAIL", 0,
                        "没落成 " + reason);
            }
        }

        int endStatus = fail == 0 ? ST_EFFECTED : ST_PARTIAL;
        head.setStatus(Integer.valueOf(endStatus));
        head.setDoneRows(Integer.valueOf(done));
        head.setFailRows(Integer.valueOf(fail));
        head.setUpdateBy(actor);
        head.setUpdateTime(now);
        this.nwfmJobMapper.updateById(head);
        trail(jobNo, 0, STAGE_EFFECT, "TRANSIT", 1,
                "核完 成=" + done + " 未成=" + fail + (fail == 0 ? " 已核完" : " 部分核成"));
        return done;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollbackJob(String jobNo) {
        TNwfmJob head = lockDao.lockJobHead(jobNo);
        if (head == null) {
            return 0;
        }
        int st = head.getStatus() == null ? ST_DRAFT : head.getStatus().intValue();
        // 核完才轮到退：待审/候核不许跳过来退；退第二遍照面（已退），不重复补冲账
        if (st != ST_EFFECTED && st != ST_PARTIAL && st != ST_ROLLING && st != ST_ROLLED) {
            throw new IllegalStateException("当前段(" + st + ")不许退回：还没核完，不能跳段往回退");
        }
        if (st == ST_ROLLED) {
            return 0;
        }

        Date now = new Date();
        String actor = actor();
        // 先落“退回中”：退到一半谁来喊核都不受理
        head.setStatus(Integer.valueOf(ST_ROLLING));
        head.setUpdateBy(actor);
        head.setUpdateTime(now);
        this.nwfmJobMapper.updateById(head);

        List<TNwfmJob> items = this.nwfmJobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).ne("row_no", 0).eq("del_flag", 0)
                .orderByAsc("row_no"));

        int changed = 0;
        for (TNwfmJob row : items) {
            // 只冲核过的行；没落成的行没落过账，无账可冲
            if (row.getStatus() == null
                    || row.getStatus().intValue() != NwfmJobRowProcessor.ROW_DONE) {
                continue;
            }
            boolean did = rowProcessor.rollbackOne(jobNo, row, now, actor);
            if (did) {
                changed++;
            } else {
                trail(jobNo, row.getRowNo(), STAGE_ROLLBACK, "ROLLBACK_SKIP", 1,
                        "该行已冲过，照面，不重复写冲账流水");
            }
        }

        // 整叠退完写明“已退”，往后一眼看得出退过没有
        head.setStatus(Integer.valueOf(ST_ROLLED));
        head.setUpdateBy(actor);
        head.setUpdateTime(now);
        this.nwfmJobMapper.updateById(head);
        trail(jobNo, 0, STAGE_ROLLBACK, "TRANSIT", 1,
                "整叠退完 本次补冲账=" + changed + "（已退）");
        return changed;
    }

    @Override
    public List<TNwfmJobTrail> listTrail(String jobNo) {
        return this.trailMapper.selectList(new QueryWrapper<TNwfmJobTrail>()
                .eq("job_no", jobNo).eq("del_flag", 0).orderByAsc("id"));
    }

    /** 系统在册上取册次码：锁号册→读号→推进，后到等着、不重号 */
    private String nextJobNo() {
        Long cur = lockDao.selectSeqForUpdate(TNwfmJobSeq.KEY_JOB_NO);
        long v = cur == null ? 1L : cur.longValue();
        lockDao.advanceSeq(TNwfmJobSeq.KEY_JOB_NO, v + 1);
        return "JOB" + v;
    }

    private void trail(String jobNo, int rowNo, int stage, String action, int result, String detail) {
        Date now = new Date();
        TNwfmJobTrail t = new TNwfmJobTrail();
        t.setJobNo(jobNo);
        t.setRowNo(Integer.valueOf(rowNo));
        t.setStage(Integer.valueOf(stage));
        t.setAction(action);
        t.setResult(Integer.valueOf(result));
        t.setDetail(detail);
        t.setActor(actor());
        t.setActTime(now);
        t.setDelFlag(Integer.valueOf(0));
        this.trailMapper.insert(t);
    }

    /** 留痕操作人：册外/定时等没有登录上下文时记 system，不留空 */
    private static String actor() {
        try {
            return ShiroUtils.getLoginName();
        } catch (RuntimeException ex) {
            return "system";
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "未知原因";
        }
        return s.length() > 250 ? s.substring(0, 250) : s;
    }
}
