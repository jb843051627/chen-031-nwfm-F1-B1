package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import com.fc.v2.mapper.auto.TNwfmCheckRowMapper;
import com.fc.v2.model.auto.TNwfmCheckRow;
import com.fc.v2.service.impl.TNwfmCheckRowServiceImpl;

/**
 * 摊户台账（周期核收条目）整批落账：空批不炸、册次以交件传入为准、
 * 坏行原样挂失败明细但不连累好行、整批同成同败、同册重放照面不重挂。
 */
@ExtendWith(MockitoExtension.class)
class TNwfmCheckRowServiceImplTest {

    @Mock
    private TNwfmCheckRowMapper mapper;

    @InjectMocks
    private TNwfmCheckRowServiceImpl service;

    private final List<TNwfmCheckRow> table = new ArrayList<>();

    private TNwfmCheckRow row(String code, String qty) {
        TNwfmCheckRow r = new TNwfmCheckRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @BeforeEach
    void setUp() {
        table.clear();
        // 仿库 selectCount：按 batch_no 数在册行数，带 status 条件时按状态数
        lenient().when(mapper.selectCount(any())).thenAnswer(new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock inv) {
                String seg = inv.<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TNwfmCheckRow>>getArgument(0)
                        .getSqlSegment();
                int n = 0;
                for (TNwfmCheckRow r : table) {
                    boolean match = "B1".equals(r.getBatchNo());
                    if (seg != null && seg.contains("status") && r.getStatus() != null
                            && r.getStatus().intValue() != 1) {
                        match = false;
                    }
                    if (match) {
                        n++;
                    }
                }
                return Integer.valueOf(n);
            }
        });
        lenient().when(mapper.insert(any())).thenAnswer(inv -> {
            table.add(inv.getArgument(0));
            return 1;
        });
    }

    @Test
    void emptyOrNoBatch_returnsZeroAndTouchesNothing() {
        assertEquals(0, service.submitBatch("B1", null));
        assertEquals(0, service.submitBatch("B1", new ArrayList<>()));
        assertEquals(0, service.submitBatch("  ", Arrays.asList(row("D1", "1"))));
        verify(mapper, never()).insert(any());
    }

    @Test
    void overLimit_rejectsWholeBatch() {
        List<TNwfmCheckRow> big = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            big.add(row("D" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("B1", big));
        verify(mapper, never()).insert(any());
    }

    @Test
    void badRowsKeptAsErrors_goodRowsStillLand_batchNoAndRowNoAssigned() {
        int ok = service.submitBatch("B1", new ArrayList<>(Arrays.asList(
                row("D1", "2"),       // 好
                row(null, "1"),       // 缺代号
                row("D3", "-5"),      // 数量非正
                row("D4", "3"))));    // 好
        assertEquals(2, ok);
        assertEquals(4, table.size(), "好坏都在册上留痕，一条不丢");
        for (int i = 0; i < 4; i++) {
            TNwfmCheckRow r = table.get(i);
            assertEquals("B1", r.getBatchNo(), "册次以传入的为准");
            assertEquals(i + 1, r.getRowNo().intValue(), "行次系统按顺序排");
        }
        assertEquals(1, table.get(0).getStatus().intValue());
        assertEquals(2, table.get(1).getStatus().intValue());
        assertEquals(2, table.get(2).getStatus().intValue());
        assertEquals(1, table.get(3).getStatus().intValue());
    }

    @Test
    void replaySameBatch_isIdempotent() {
        service.submitBatch("B1", new ArrayList<>(Arrays.asList(row("D1", "2"), row("D2", "3"))));
        assertEquals(2, table.size());
        // 同一册次再交一遍：照面返回成功行数，不重新挂账
        int again = service.submitBatch("B1", new ArrayList<>(Arrays.asList(row("D1", "2"))));
        assertEquals(2, again);
        verify(mapper, times(2)).insert(any());
    }
}
