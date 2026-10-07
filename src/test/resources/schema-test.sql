-- H2(MODE=MySQL) 集成测试库表：只建核验作业生命周期触及的表，列与实体逐列对齐。
CREATE TABLE t_nwfm_job (
  id bigint NOT NULL PRIMARY KEY,
  job_no varchar(32),
  job_name varchar(64),
  req_no varchar(64),
  status int,
  total_rows int,
  done_rows int,
  fail_rows int,
  eff_start datetime,
  eff_end datetime,
  version varchar(32),
  row_no int,
  device_no varchar(64),
  amount decimal(12,2),
  job_qty int,
  version_status int,
  writeback_flag int,
  compensate_flag int,
  compensated int,
  compensate_cnt int,
  fail_reason varchar(255),
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  CONSTRAINT uk_nwfm_job_req UNIQUE (req_no),
  CONSTRAINT uk_nwfm_job_row UNIQUE (job_no, row_no)
);

CREATE TABLE t_nwfm_job_seq (
  seq_key varchar(32) NOT NULL PRIMARY KEY,
  next_val bigint NOT NULL,
  update_time datetime
);

CREATE TABLE t_nwfm_settle_flow (
  id bigint NOT NULL PRIMARY KEY,
  job_no varchar(32),
  row_no int,
  device_no varchar(64),
  amount decimal(12,2),
  flow_type int,
  ref_flow_id bigint,
  status int DEFAULT 1,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500),
  CONSTRAINT uk_nwfm_flow UNIQUE (job_no, row_no, flow_type)
);

CREATE TABLE t_nwfm_job_trail (
  id bigint NOT NULL PRIMARY KEY,
  job_no varchar(32),
  row_no int,
  stage int,
  action varchar(32),
  result int,
  detail varchar(500),
  actor varchar(64),
  act_time datetime,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500)
);
CREATE INDEX idx_nwfm_trail_job ON t_nwfm_job_trail (job_no, id);

CREATE TABLE t_nwfm_check_row (
  id bigint NOT NULL PRIMARY KEY,
  batch_no varchar(64),
  row_no int,
  item_code varchar(64),
  qty decimal(12,2),
  device_kind varchar(32),
  stall_name varchar(64),
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500)
);

CREATE TABLE t_nwfm_device_rule (
  id bigint NOT NULL PRIMARY KEY,
  rule_code varchar(32),
  rule_name varchar(64),
  th1_max decimal(8,2),
  th2_max decimal(8,2),
  th3_max decimal(8,2),
  eff_start datetime,
  eff_end datetime,
  priority int,
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500)
);

INSERT INTO t_nwfm_job_seq (seq_key, next_val) VALUES ('JOB_NO', 1);

-- 在用判据
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (10, 'V1', '在用判据', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0);
-- 已撤下判据
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (11, 'V2', '已撤判据', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 1, 0);
-- 已过期判据
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (12, 'V3', '过期判据', 0.50, 2.00, 5.00, '2020-01-01 00:00:00', '2021-12-31 23:59:59', 10, 0, 0);

-- 同编号改版两版（V4）：旧版窗口 2020..2023 阈值宽（1/3/8），新版 2024 起阈值严（0.5/2/5）
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (13, 'V4', '旧版口径', 1.00, 3.00, 8.00, '2020-01-01 00:00:00', '2023-12-31 23:59:59', 10, 0, 0);
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (14, 'V4', '新版口径', 0.50, 2.00, 5.00, '2024-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0);

-- 高优先级口径：只在 2030..2035 窗口内可用，阈值最严（0.01/0.02/0.05）
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (15, 'VP', '高优先级口径', 0.01, 0.02, 0.05, '2030-01-01 00:00:00', '2035-12-31 23:59:59', 20, 0, 0);

-- 与 V1 同优先级、编号按字典序更大的口径（验并列时确定性取号），阈值 0.10/0.40/1.00
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (16, 'VW', '同优先级并列口径', 0.10, 0.40, 1.00, '2020-01-01 00:00:00', '2099-12-31 23:59:59', 10, 0, 0);

-- 已撤下的改版口径：窗口虽罩住当下，status=1 不许参与判档
INSERT INTO t_nwfm_device_rule (id, rule_code, rule_name, th1_max, th2_max, th3_max, eff_start, eff_end, priority, status, del_flag)
VALUES (17, 'V4', '改版后撤下的口径', 0.20, 0.30, 0.40, '2024-01-01 00:00:00', '2099-12-31 23:59:59', 99, 1, 0);
