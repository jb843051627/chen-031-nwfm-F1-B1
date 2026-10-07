package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.mapper.auto.TNwfmJobMapper;
import com.fc.v2.mapper.auto.TNwfmSettleFlowMapper;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.model.auto.TNwfmJob;
import com.fc.v2.model.auto.TNwfmJobTrail;
import com.fc.v2.model.auto.TNwfmSettleFlow;
import com.fc.v2.service.ITNwfmJobService;
import com.fc.v2.service.impl.NwfmJobRowProcessor;

/**
 * 核验作业生命周期：排队/状态机/判据/三本账/部分隔离/冲账去重/并发，真库(H2 MySQL模式)。
 * 不加 @Transactional：要真提交，才能验跨行事务、唯一键、行锁。
 */
@SpringBootTest(classes = NwfmJobTestConfig.class)
class TNwfmJobLifecycleTest {

    @Autowired
    private ITNwfmJobService jobService;
    @Autowired
    private TNwfmJobMapper jobMapper;
    @Autowired
    private TNwfmCheckRowMapper checkRowMapper;
    @Autowired
    private TNwfmSettleFlowMapper flowMapper;

    private static final int HEAD_PENDING = 1;
    private static final int HEAD_EFFECTING = 2;
    private static final int HEAD_EFFECTED = 3;
    private static final int HEAD_PARTIAL = 4;
    private static final int HEAD_ROLLING = 5;
    private static final int HEAD_ROLLED = 6;

    private TNwfmJob row(String device, String amount, String version) {
        TNwfmJob r = new TNwfmJob();
        r.setDeviceNo(device);
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setVersion(version);
        r.setJobQty(Integer.valueOf(1));
        return r;
    }

    private TNwfmJob head(String jobNo) {
        return jobService.selectByJobNo(jobNo);
    }

    private List<TNwfmJob> itemRows(String jobNo) {
        return jobMapper.selectList(new QueryWrapper<TNwfmJob>()
                .eq("job_no", jobNo).ne("row_no", 0).eq("del_flag", 0)
                .orderByAsc("row_no"));
    }

    private List<TNwfmSettleFlow> flows(String jobNo, Integer rowNo, int type) {
        return flowMapper.selectList(new QueryWrapper<TNwfmSettleFlow>()
                .eq("job_no", jobNo).eq("row_no", rowNo).eq("flow_type", type));
    }

    // ① 排队：册次码系统发（JOB1、JOB2…），行次系统从上往下排，交什么号都不许手填
    @Test
    void submit_systemAssignsJobNoAndRowNo() {
        String j1 = jobService.submitJob("R-SEQ-1",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"), row("D2", "20", "V1"))));
        String j2 = jobService.submitJob("R-SEQ-2",
                new ArrayList<>(Arrays.asList(row("D3", "30", "V1"))));

        assertTrue(j1.startsWith("JOB"), "册次码由系统排：" + j1);
        assertNotEquals(j1, j2, "两叠册次码不得重号");
        long n1 = Long.parseLong(j1.substring(3));
        long n2 = Long.parseLong(j2.substring(3));
        assertEquals(n1 + 1, n2, "同一时刻后到的号接着排，不重号、不跳号");
        TNwfmJob h = head(j1);
        assertEquals(HEAD_PENDING, h.getStatus().intValue());
        assertEquals(2, h.getTotalRows().intValue());
        List<TNwfmJob> rows = itemRows(j1);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).getRowNo().intValue());
        assertEquals(2, rows.get(1).getRowNo().intValue());
        // 行是交上来先候着，不许没审就核
        assertEquals(NwfmJobRowProcessor.ROW_WAIT, rows.get(0).getStatus().intValue());
    }

    // ② 幂等：同一批反复递进，第二遍只当照面，同一册次码、不另起行
    @Test
    void submit_sameRequestIsIdempotent() {
        String j1 = jobService.submitJob("R-IDEM",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        String jAgain = jobService.submitJob("R-IDEM",
                new ArrayList<>(Arrays.asList(row("D9", "99", "V1"))));
        assertEquals(j1, jAgain);
        assertEquals(1, itemRows(j1).size());
        // 第二遍内容不得覆盖第一遍
        assertEquals("D1", itemRows(j1).get(0).getDeviceNo());
    }

    // ③ 脏行（器具编号/金额/判据版本缺）交件不炸整批，留着到核验时按口径判“没落成”
    @Test
    void submit_dirtyRowsAcceptedNotRejected() {
        String j = jobService.submitJob("R-DIRTY",
                new ArrayList<>(Arrays.asList(row(null, "10", "V1"))));
        assertEquals(1, itemRows(j).size());
    }

    // ④ 没审不许核；审过才轮到
    @Test
    void effect_beforeApproval_rejected() {
        String j = jobService.submitJob("R-GUARD-1",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        assertThrows(IllegalStateException.class, () -> jobService.effectJob(j));
        assertEquals(HEAD_EFFECTING, jobService.approveJob(j).intValue());
        assertEquals(1, jobService.effectJob(j));
    }

    // ⑤ 核成：器具册、结算流水、留痕三本一起落，少一处都不算
    @Test
    void effect_successWritesThreeLedgers() {
        String j = jobService.submitJob("R-OK",
                new ArrayList<>(Arrays.asList(row("D-OK", "10", "V1"))));
        jobService.approveJob(j);
        int done = jobService.effectJob(j);

        assertEquals(1, done);
        assertEquals(HEAD_EFFECTED, head(j).getStatus().intValue());
        TNwfmJob r = itemRows(j).get(0);
        assertEquals(NwfmJobRowProcessor.ROW_DONE, r.getStatus().intValue());
        assertEquals(1, r.getWritebackFlag().intValue());
        assertNull(r.getFailReason());

        // 账一：器具册
        List<TNwfmCheckRow> regs = checkRowMapper.selectList(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", j).eq("item_code", "D-OK"));
        assertEquals(1, regs.size());
        assertEquals(1, regs.get(0).getStatus().intValue());
        // 账二：结算流水
        assertEquals(1, flows(j, 1, TNwfmSettleFlow.TYPE_SETTLE).size());
        // 账三：留痕（交件、放行、核成可倒查，带操作人）
        List<TNwfmJobTrail> trail = jobService.listTrail(j);
        assertTrue(trail.stream().anyMatch(t -> "EFFECT_OK".equals(t.getAction())
                && t.getActor() != null));
    }

    // ⑥ 判据三态 + 资料不齐：该行没落成写明原因，好行照走，整叠部分核成、不回滚
    @Test
    void effect_ruleStatesAndIncomplete_partialIsolation() {
        String j = jobService.submitJob("R-RULE", new ArrayList<>(Arrays.asList(
                row("D-GOOD", "10", "V1"),     // 成
                row("D-W", "10", "V2"),         // 已撤
                row("D-E", "10", "V3"),         // 已过期
                row("D-M", "10", "V9"),         // 册上没有
                row("D-I", null, "V1"),         // 金额缺（资料不齐）
                row(null, "10", "V1"),          // 器具编号缺
                row("D-V", "10", null)          // 判据版本缺
        )));
        jobService.approveJob(j);
        int done = jobService.effectJob(j);

        assertEquals(1, done);
        assertEquals(HEAD_PARTIAL, head(j).getStatus().intValue());
        assertEquals(1, head(j).getDoneRows().intValue());
        assertEquals(6, head(j).getFailRows().intValue());

        List<TNwfmJob> rows = itemRows(j);
        assertEquals(NwfmJobRowProcessor.ROW_DONE, rows.get(0).getStatus().intValue());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(NwfmJobRowProcessor.ROW_FAIL, rows.get(i).getStatus().intValue());
            assertNotNull(rows.get(i).getFailReason());
            // 没落成的行：结算流水、器具册一处都不许有（三本账同成同败，不留半截账）
            assertEquals(0, flows(j, rows.get(i).getRowNo(), TNwfmSettleFlow.TYPE_SETTLE).size());
        }
        // 好行的账不受连累
        assertEquals(1, flows(j, 1, TNwfmSettleFlow.TYPE_SETTLE).size());
    }

    // ⑦ 终态守卫：已核完不能再喊核
    @Test
    void effect_afterEffected_rejected() {
        String j = jobService.submitJob("R-DONE",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        jobService.approveJob(j);
        jobService.effectJob(j);
        assertThrows(IllegalStateException.class, () -> jobService.effectJob(j));
    }

    // ⑧ 退回：补一道冲账流水逐处冲回；再退照面，冲账流水不重、计数不涨
    @Test
    void rollback_compensateOnceAndMarksRolled() {
        String j = jobService.submitJob("R-BACK",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        jobService.approveJob(j);
        jobService.effectJob(j);

        int changed = jobService.rollbackJob(j);
        assertEquals(1, changed);
        assertEquals(HEAD_ROLLED, head(j).getStatus().intValue());

        TNwfmJob r = itemRows(j).get(0);
        assertEquals(1, r.getCompensated().intValue());
        assertEquals(1, r.getCompensateCnt().intValue());
        assertEquals(NwfmJobRowProcessor.ROW_ROLLED, r.getStatus().intValue());
        // 冲账流水恰好一道，指向原结算流水；原流水置已冲回
        List<TNwfmSettleFlow> comp = flows(j, 1, TNwfmSettleFlow.TYPE_COMPENSATE);
        assertEquals(1, comp.size());
        List<TNwfmSettleFlow> settle = flows(j, 1, TNwfmSettleFlow.TYPE_SETTLE);
        assertEquals(0, settle.get(0).getStatus().intValue());
        assertEquals(settle.get(0).getId(), comp.get(0).getRefFlowId());
        // 器具册也冲回
        TNwfmCheckRow reg = checkRowMapper.selectOne(new QueryWrapper<TNwfmCheckRow>()
                .eq("batch_no", j).eq("item_code", "D1"));
        assertEquals(2, reg.getStatus().intValue());

        // 再退一遍：照面，不重复补
        assertEquals(0, jobService.rollbackJob(j));
        assertEquals(1, flows(j, 1, TNwfmSettleFlow.TYPE_COMPENSATE).size());
        assertEquals(1, itemRows(j).get(0).getCompensateCnt().intValue());
        // 一眼看得出退过
        assertTrue(jobService.listTrail(j).stream()
                .anyMatch(t -> t.getDetail() != null && t.getDetail().contains("已退")));
    }

    // ⑨ 没核完不许跳过来退
    @Test
    void rollback_beforeEffect_rejected() {
        String j = jobService.submitJob("R-BACK-GUARD",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        assertThrows(IllegalStateException.class, () -> jobService.rollbackJob(j));
        jobService.approveJob(j);
        assertThrows(IllegalStateException.class, () -> jobService.rollbackJob(j));
    }

    // ⑩ 退到一半/退完不许往前核
    @Test
    void effect_afterRollback_rejected() {
        String j = jobService.submitJob("R-RB-EFF",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"))));
        jobService.approveJob(j);
        jobService.effectJob(j);
        jobService.rollbackJob(j);
        assertThrows(IllegalStateException.class, () -> jobService.effectJob(j));
    }

    // ⑪ 部分核成只冲核过的行，没落成的行无账可冲
    @Test
    void rollback_partialOnlyCompensatesDone() {
        String j = jobService.submitJob("R-PART",
                new ArrayList<>(Arrays.asList(row("D1", "10", "V1"), row("D2", "10", "V2"))));
        jobService.approveJob(j);
        jobService.effectJob(j);
        int changed = jobService.rollbackJob(j);
        assertEquals(1, changed);
        assertEquals(1, flows(j, 1, TNwfmSettleFlow.TYPE_COMPENSATE).size());
        assertEquals(0, flows(j, 2, TNwfmSettleFlow.TYPE_COMPENSATE).size());
        assertEquals(HEAD_ROLLED, head(j).getStatus().intValue());
    }

    // ⑫ 并发：两叠同一时刻都动同一号器具，先到先得、后到等待，不互相覆盖、不写重流水
    @Test
    void concurrentEffectSameDevice_firstWinsNoOverwrite() throws Exception {
        String ja = jobService.submitJob("R-CON-A",
                new ArrayList<>(Arrays.asList(row("SAME-DEV", "10", "V1"))));
        String jb = jobService.submitJob("R-CON-B",
                new ArrayList<>(Arrays.asList(row("SAME-DEV", "10", "V1"))));
        jobService.approveJob(ja);
        jobService.approveJob(jb);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        Runnable ta = () -> {
            try {
                start.await();
                jobService.effectJob(ja);
                ok.incrementAndGet();
            } catch (Exception ignore) {
            }
        };
        Runnable tb = () -> {
            try {
                start.await();
                jobService.effectJob(jb);
                ok.incrementAndGet();
            } catch (Exception ignore) {
            }
        };
        pool.submit(ta);
        pool.submit(tb);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(2, ok.get(), "两叠都应核成，互不覆盖、不死锁");

        // 各叠各自一行结算流水，互不重号、不覆盖
        assertEquals(1, flows(ja, 1, TNwfmSettleFlow.TYPE_SETTLE).size());
        assertEquals(1, flows(jb, 1, TNwfmSettleFlow.TYPE_SETTLE).size());
        assertEquals(HEAD_EFFECTED, head(ja).getStatus().intValue());
        assertEquals(HEAD_EFFECTED, head(jb).getStatus().intValue());
    }
}
