package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.fc.v2.model.auto.TNwfmDeviceRule;
import com.fc.v2.service.impl.TNwfmDeviceRuleServiceImpl;

/**
 * 计量器具判据求值：按当时口径选版、多口径按优先级、撤下/过期不算、
 * 非法示值偏差拦在门外、册上无编号给「没依据」(0) 而不是报错。
 * 真库(H2 MySQL模式)，时刻全部写死，不靠当下墙钟。
 */
@SpringBootTest(classes = NwfmJobTestConfig.class)
class TNwfmDeviceRuleServiceImplTest {

    @Autowired
    private TNwfmDeviceRuleServiceImpl ruleService;

    private static Date at(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ① 改版不认当时版本：同编号两版口径，旧账复秤必须按旧时刻那版算，秤两遭结果一致
    @Test
    void evaluate_picksVersionEffectiveAt() {
        // 0.6 落在旧版(1/3/8)的可用档、新版(0.5/2/5)的复核档
        assertEquals(1, ruleService.evaluate("V4", new BigDecimal("0.60"), at("2021-06-01 00:00:00")));
        assertEquals(2, ruleService.evaluate("V4", new BigDecimal("0.60"), at("2025-06-01 00:00:00")));
        // 同一批秤按当时时刻复秤两遭，结果对得上
        int first = ruleService.evaluate("V4", new BigDecimal("0.60"), at("2021-06-01 10:00:00"));
        int second = ruleService.evaluate("V4", new BigDecimal("0.60"), at("2021-06-01 12:00:00"));
        assertEquals(first, second);
    }

    // ② 生效窗口起含止不含：切换前一霎认旧版，切换点起认新版
    @Test
    void evaluate_windowBoundaryEndExclusive() {
        assertEquals(1, ruleService.evaluate("V4", new BigDecimal("0.60"), at("2023-12-31 23:59:58")));
        assertEquals(2, ruleService.evaluate("V4", new BigDecimal("0.60"), at("2024-01-01 00:00:00")));
    }

    // ③ 撤下的口径即使窗口罩住当下也不算：查它只能得到「没依据」0
    @Test
    void evaluate_withdrawnRuleCountsAsNoBasis() {
        assertEquals(0, ruleService.evaluate("V2", new BigDecimal("0.10"), at("2025-06-01 00:00:00")));
        // 同编号里虽混着一条撤下行(id=17)，在窗启用版照常秤，撤下行不许顶替
        assertEquals(2, ruleService.evaluate("V4", new BigDecimal("0.60"), at("2025-06-01 00:00:00")));
    }

    // ④ 册上没有的口径编号：给 0（没依据），不许直接抛错
    @Test
    void evaluate_unknownRuleCodeReturnsZeroNotThrow() {
        assertDoesNotThrow(
                () -> assertEquals(0, ruleService.evaluate("V9", new BigDecimal("0.10"), at("2025-06-01 00:00:00"))));
    }

    // ⑤ 示值偏差为负 / 大得离谱：本该拦在门外，不秤出任何档；恰好 100 仍收
    @Test
    void evaluate_negativeOrAbsurdInputRejected() {
        Date now = at("2025-06-01 00:00:00");
        assertEquals(0, ruleService.evaluate("V1", new BigDecimal("-0.01"), now));
        assertEquals(0, ruleService.evaluate("V1", new BigDecimal("-100"), now));
        assertEquals(0, ruleService.evaluate("V1", new BigDecimal("100.01"), now));
        assertEquals(0, ruleService.evaluate("V1", new BigDecimal("99999"), now));
        // 合法上沿不被误杀：0.10 落 V1 可用档；100 虽是合法输入但远超停用档上限，秤出第 4 档
        assertEquals(1, ruleService.evaluate("V1", new BigDecimal("0.10"), now));
        assertEquals(4, ruleService.evaluate("V1", new BigDecimal("100"), now));
    }

    // ⑥ 同一时刻多条口径可用：挑册上优先级标得最高那条，不随库返回顺序飘
    @Test
    void evaluateTop_picksHighestPriorityInWindow() {
        // 2032 年 VP(优先级20, 阈值0.01/0.02/0.05) 在窗：0.03 按 VP 是停用档(3)
        assertEquals(3, ruleService.evaluateTop(new BigDecimal("0.03"), at("2032-06-01 00:00:00")));
        // 2025 年 VP 不在窗；V1 与 VW 同为优先级10，编号大者(VW)中，0.30 按 VW 是复核档(2)，
        // 若错拿 V1 会秤成可用档(1)
        assertEquals(2, ruleService.evaluateTop(new BigDecimal("0.30"), at("2025-06-01 00:00:00")));
    }

    // ⑦ 非法值进门之前先拦，evaluateTop 也不例外
    @Test
    void evaluateTop_illegalInputReturnsZero() {
        assertEquals(0, ruleService.evaluateTop(new BigDecimal("-1"), at("2025-06-01 00:00:00")));
        assertEquals(0, ruleService.evaluateTop(new BigDecimal("101"), at("2025-06-01 00:00:00")));
    }

    // ⑧ 可用列表：撤下的、过期的、窗口外的都不许混进来；顺序按优先级/编号确定
    @Test
    void listAvailable_filtersWindowStatusAndSorts() {
        List<TNwfmDeviceRule> avail = ruleService.listAvailable(at("2025-06-01 00:00:00"));
        // 在窗且启用：V1、V4新版、VW；V2撤下、V3过期、V4旧版、撤下改版行 均排除
        assertEquals(3, avail.size());
        assertEquals("VW", avail.get(0).getRuleCode());
        assertEquals("V4", avail.get(1).getRuleCode());
        assertEquals("V1", avail.get(2).getRuleCode());
        assertEquals(3, ruleService.countAvailable(at("2025-06-01 00:00:00")));

        // 2032 年高优先级 VP 进窗，且排在最前
        List<TNwfmDeviceRule> later = ruleService.listAvailable(at("2032-06-01 00:00:00"));
        assertEquals(4, later.size());
        assertEquals("VP", later.get(0).getRuleCode());
    }

    // ⑨ usable：口径与定位/列表同一把尺
    @Test
    void usable_sharesSameCriteria() {
        Date now = at("2025-06-01 00:00:00");
        assertTrue(ruleService.usable(10L, now), "V1 在窗启用，可用");
        assertFalse(ruleService.usable(11L, now), "V2 已撤下，不可用（开关必须真接上）");
        assertFalse(ruleService.usable(12L, now), "V3 已过期，不可用");
        assertTrue(ruleService.usable(12L, at("2021-06-01 00:00:00")), "V3 在 2021 年仍在窗，可用");
        assertFalse(ruleService.usable(17L, now), "V4 撤下行即便窗口罩住当下也不可用");
        assertFalse(ruleService.usable(999L, now), "册上没有的编号不可用");
        assertFalse(ruleService.usable(null, now));
    }
}
