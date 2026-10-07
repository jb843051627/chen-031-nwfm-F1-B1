package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TNwfmDeviceRuleMapper;
import com.fc.v2.model.auto.TNwfmDeviceRule;
import com.fc.v2.service.impl.TNwfmDeviceRuleServiceImpl;

/**
 * 判据求值：认当时生效的版本、边界取低不就高、多条取优先级最高、
 * 脏数拦回、没编号给“没依据”、撤下/过期不参与。
 */
@ExtendWith(MockitoExtension.class)
class TNwfmDeviceRuleServiceImplTest {

    @Mock
    private TNwfmDeviceRuleMapper mapper;

    @InjectMocks
    private TNwfmDeviceRuleServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private Date t(String s) {
        try {
            return f.parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TNwfmDeviceRule rule(long id, String code, BigDecimal th1, BigDecimal th2, BigDecimal th3,
                                 String start, String end, Integer priority, Integer status) {
        TNwfmDeviceRule r = new TNwfmDeviceRule();
        r.setId(Long.valueOf(id));
        r.setRuleCode(code);
        r.setTh1Max(th1);
        r.setTh2Max(th2);
        r.setTh3Max(th3);
        r.setEffStart(start == null ? null : t(start));
        r.setEffEnd(end == null ? null : t(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(Integer.valueOf(0));
        return r;
    }

    private List<TNwfmDeviceRule> store;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        // 仿库：findRule 的 wrapper 是 del_flag + rule_code，listAvailable 只有 del_flag。
        // 带 rule_code 段就按字符串参数（编号）过滤，否则返回全量在册行。
        lenient().when(mapper.selectList(any())).thenAnswer(inv -> {
            @SuppressWarnings("rawtypes")
            QueryWrapper w = inv.getArgument(0);
            boolean byCode = w.getSqlSegment() != null && w.getSqlSegment().contains("rule_code");
            String code = null;
            if (byCode) {
                code = (String) w.getParamNameValuePairs().values().stream()
                        .filter(v -> v instanceof String).findFirst().orElse(null);
            }
            List<TNwfmDeviceRule> out = new ArrayList<>();
            for (TNwfmDeviceRule r : store) {
                if (!byCode || java.util.Objects.equals(code, r.getRuleCode())) {
                    out.add(r);
                }
            }
            return out;
        });
    }

    // ① 改版前后是两条时段不同的版本：复秤旧批次必须用旧版算，现行版不倒算旧账
    @Test
    void evaluate_usesVersionEffectiveAtThatTime() {
        // R1 旧版 0.5/2/5（2020~2024），新版 1/4/10（2024~2099）
        store.addAll(Arrays.asList(
                rule(1, "R1", bd("0.5"), bd("2"), bd("5"),
                        "2020-01-01 00:00:00", "2024-01-01 00:00:00", 10, 0),
                rule(2, "R1", bd("1"), bd("4"), bd("10"),
                        "2024-01-01 00:00:00", "2099-12-31 23:59:59", 10, 0)));

        // 同一示值 0.8：旧版超 0.5 落复核档(2)，新版在 1 以内落可用档(1)
        assertEquals(2, service.evaluate("R1", bd("0.8"), t("2023-06-01 00:00:00")),
                "旧时刻复秤认旧版");
        assertEquals(1, service.evaluate("R1", bd("0.8"), t("2025-06-01 00:00:00")),
                "新时刻用新版");
        // 同一批秤两遭，时刻钉死在旧版时段，结果不许漂
        assertEquals(2, service.evaluate("R1", bd("0.8"), t("2023-06-01 00:00:00")));
    }

    // ② 恰好等于上限归这档（取低不就高），两条口径一般高也不摇摆
    @Test
    void evaluate_boundaryEqualsLimit_staysInLowerTier() {
        store.add(rule(1, "R1", bd("0.5"), bd("2.0"), bd("5.0"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10, 0));
        Date at = t("2025-01-01 00:00:00");
        assertEquals(1, service.evaluate("R1", bd("0.5"), at), "0.50 仍在可用档以内");
        assertEquals(2, service.evaluate("R1", bd("2.0"), at), "2.00 仍在复核档以内");
        assertEquals(3, service.evaluate("R1", bd("5.0"), at), "5.00 仍在停用档以内");
        assertEquals(4, service.evaluate("R1", bd("5.01"), at), "过了停用档才到下一档");
    }

    // ③ 同一时刻多条口径可用，挑册上优先级标的最高的，不按取数顺序碰运气
    @Test
    void evaluateTop_picksHighestPriorityNotFirstRow() {
        // A 优先级低、取数排前面：2.0 对它落复核档；B 优先级高：2.0 对它落可用档
        store.add(rule(10, "A", bd("1"), bd("2"), bd("5"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 5, 0));
        store.add(rule(11, "B", bd("5"), bd("10"), bd("20"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 9, 0));
        // 混进一条撤下的高优先级：开关必须接上，不许它参与
        store.add(rule(12, "C", bd("50"), bd("60"), bd("70"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 99, 1));
        // 再混一条过期的高优先级
        store.add(rule(13, "D", bd("50"), bd("60"), bd("70"),
                "2020-01-01 00:00:00", "2021-01-01 00:00:00", 99, 0));

        assertEquals(1, service.evaluateTop(bd("2.0"), t("2025-01-01 00:00:00")),
                "只认在用启用口径里优先级最高的 B");
        assertEquals(2, service.countAvailable(t("2025-01-01 00:00:00")),
                "撤下的、过期的不进可用条数");
    }

    // ④ 示值偏差为负、或大得离谱，拦在门外，不许秤出档
    @Test
    void evaluate_negativeOrAbsurdInput_returnsZero() {
        store.add(rule(1, "R1", bd("0.5"), bd("2"), bd("5"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10, 0));
        Date at = t("2025-01-01 00:00:00");
        assertEquals(0, service.evaluate("R1", bd("-0.01"), at));
        assertEquals(0, service.evaluate("R1", bd("9999"), at));
        assertEquals(0, service.evaluateTop(bd("-1"), at));
        assertEquals(0, service.evaluateTop(bd("100.01"), at));
    }

    // ⑤ 册上没有的口径编号：给“没依据”(0)，不抛错
    @Test
    void evaluate_unknownRuleCode_returnsZeroNotThrow() {
        store.add(rule(1, "R1", bd("0.5"), bd("2"), bd("5"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10, 0));
        Date at = t("2025-01-01 00:00:00");
        assertEquals(0, service.evaluate("NO-SUCH", bd("0.1"), at));
        // 编号在册，但此刻没有生效版本（已过期），也算没依据
        assertEquals(0, service.evaluate("R1", bd("0.1"), t("2019-01-01 00:00:00")));
    }

    // ⑥ 撤下/过期：单条可用判定口径与列表、计数一致
    @Test
    void usable_matchesListAndStatusSwitch() {
        TNwfmDeviceRule withdrawn = rule(20, "W", bd("0.5"), bd("2"), bd("5"),
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10, 1);
        store.add(withdrawn);
        when(mapper.selectById(Long.valueOf(20L))).thenReturn(withdrawn);
        Date at = t("2025-01-01 00:00:00");
        assertFalse(service.usable(Long.valueOf(20L), at), "撤下的开关接上：不可用");
        assertFalse(service.listAvailable(at).stream().anyMatch(r -> "W".equals(r.getRuleCode())));

        // 撤下后重新启用（status 回 0）即恢复，不用改一行代码
        withdrawn.setStatus(Integer.valueOf(0));
        assertTrue(service.usable(Long.valueOf(20L), at));
    }

    // ⑦ 阈值一档都没维护：不瞎秤，按不可用 0
    @Test
    void evaluate_allThresholdsMissing_returnsZero() {
        store.add(rule(1, "R1", null, null, null,
                "2020-01-01 00:00:00", "2099-12-31 23:59:59", 10, 0));
        assertEquals(0, service.evaluate("R1", bd("0.1"), t("2025-01-01 00:00:00")));
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
