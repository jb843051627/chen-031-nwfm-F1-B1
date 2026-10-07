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
