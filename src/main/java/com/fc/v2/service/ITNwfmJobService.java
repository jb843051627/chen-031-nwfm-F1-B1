package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TNwfmJob;
import com.fc.v2.model.auto.TNwfmJobTrail;

/**
 * 器具核验作业 Service 接口（job-lifecycle 形状：批量作业提交/审过/核验/退回）
 *
 * 册上口径：
 * 交件先候着（待审），审过才轮到核，核完才轮到退；段不可跳，跳了不算数。
 * 已核完/已退完的不能再喊核，退到一半（退回中）不能往前核。
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface ITNwfmJobService {

    /** 按主键回查作业 */
    TNwfmJob selectTNwfmJobById(Long id);

    /** 按作业号回查作业（册首 row_no=0） */
    TNwfmJob selectByJobNo(String jobNo);

    /**
     * 交进一叠核验需求。册次码由系统在册上排出来（不许手填），行次从上往下排、落定不挪窝。
     * 同一请求号反复递进按幂等处理，第二遍只当照面，返回已有的册次码。
     *
     * @param reqNo 交件请求号（幂等用，不是册次码）
     * @param rows  这一叠里的各行
     * @return 系统排定的册次码 jobNo
     */
    String submitJob(String reqNo, List<TNwfmJob> rows);

    /**
     * 审过放行：待审 → 候核。只有候着的这叠能放行，其余段一律拒绝。
     * @param jobNo 系统排定的册次码
     * @return 放行后册首状态；未放行（册上不存在/不在待审段）返回 null
     */
    Integer approveJob(String jobNo);

    /**
     * 核验这一叠：每行先验判据（撤了/过期/册上没有/资料不齐→这一趟核不成，写明原因），
     * 再把器具册、结算流水、留痕三处一起落；少一处这行不算核过，单行没落成不推翻整叠。
     * 只能在“候核”段动手。
     * @return 核成行数
     */
    int effectJob(String jobNo);

    /**
     * 退回这一叠：核过的行逐处冲回并补一道冲账流水，同一行只冲一次；整叠退完写明已退。
     * 只能在“核完/部分/退回中”段动手。
     * @return 本次真补了冲账流水的行数（已冲过再退照面，不计）
     */
    int rollbackJob(String jobNo);

    /** 倒着查：这一叠走过的每一段、每行成败、谁动的、什么时候动的 */
    List<TNwfmJobTrail> listTrail(String jobNo);
}
