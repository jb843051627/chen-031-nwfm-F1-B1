-- 四张判定/档案表的种子数据。id=0 为在用行、id=1 为撤下行，
-- 判定档、签批基准、赔付分档三张表各留在用行与停用行作对照；
-- 档案表缺行会让 insert 直接判 0 ⇒ 合法记录会因查不到档案而保存失败。
INSERT IGNORE INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag, create_by, create_time)
VALUES (0, 'NR00', '偏差可用档：示值偏差落在 0.5% 以内按准秤认（在用行）', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0, 'seed', NOW()),
       (1, 'NR01', '偏差可用档：示值偏差落在 0.5% 以内按准秤认（在用行）', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0, 'seed', NOW()),
       (2, 'NR02', '偏差停用档：示值偏差超过 2.00% 收秤不认（停用行）', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2021-12-31 23:59:59', 10, 1, 0, 'seed', NOW());

INSERT IGNORE INTO t_nwfm_settle_rule (id, bill_no, node_no, sign_mode, need_count, sign_count, status, del_flag, create_by, create_time)
VALUES (0, 'NB00', 0, 0, 1, 0, 0, 0, 'seed', NOW()),
       (1, 'NB01', 1, 1, 2, 0, 0, 0, 'seed', NOW());

INSERT IGNORE INTO t_nwfm_dispute_rule (id, biz_no, stage, status, content, last_action, del_flag, create_by, create_time)
VALUES (0, 'ND00', 0, 0, '当场处置档的基准单：争议金额落在 50 元以内', 'seed', 0, 'seed', NOW()),
       (1, 'ND01', 1, 0, '复称处置档的基准单：争议金额 50 元至 200 元', 'seed', 0, 'seed', NOW());

INSERT IGNORE INTO t_nwfm_settle_base (id, grade_code, grade_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES (0, 'GB00', '摊分直赔档：赔付额在 100 元以内（在用行）', 100.00, 500.00, 2000.00, 0, 0, 'seed', NOW()),
       (1, 'GB01', '摊分直赔档：赔付额在 100 元以内（在用行）', 100.00, 500.00, 2000.00, 0, 0, 'seed', NOW()),
       (2, 'GB02', '摊分复核档：赔付额 100 元至 500 元（停用行）', 100.00, 500.00, 2000.00, 1, 0, 'seed', NOW());
