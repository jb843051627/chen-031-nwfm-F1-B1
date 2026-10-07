-- =====================================================================
-- 器具核验作业（一叠核验需求）生命周期支撑表
-- 排队（系统发册次码）/ 三段状态机 / 三本账（器具册·结算流水·留痕）/ 退回冲账
-- 列名与实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_031
-- =====================================================================

-- 册次码发号册：号一律由系统在册上排出来，不许手填。
-- 取号走 SELECT ... FOR UPDATE，后到等着、不重号。
CREATE TABLE IF NOT EXISTS t_nwfm_job_seq (
  seq_key     varchar(32)  NOT NULL COMMENT '号别(JOB_NO=册次码)',
  next_val    bigint       NOT NULL COMMENT '下一个号',
  update_time datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (seq_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核验作业发号册';

INSERT IGNORE INTO t_nwfm_job_seq (seq_key, next_val) VALUES ('JOB_NO', 1);

-- 结算流水：核验落账写 flow_type=1（结算），退回补 flow_type=2（冲账）并指向原流水。
-- (job_no,row_no,flow_type) 唯一：同一行结算只一道、冲账也只一道，反复请求不重复落。
CREATE TABLE IF NOT EXISTS t_nwfm_settle_flow (
  id           bigint        NOT NULL COMMENT '主键',
  job_no       varchar(32)   DEFAULT NULL COMMENT '册次码',
  row_no       int           DEFAULT NULL COMMENT '行次',
  device_no    varchar(64)   DEFAULT NULL COMMENT '器具编号',
  amount       decimal(12,2) DEFAULT NULL COMMENT '金额',
  flow_type    int           DEFAULT NULL COMMENT '流水类型 1结算 2冲账',
  ref_flow_id  bigint        DEFAULT NULL COMMENT '冲账指向的原结算流水ID',
  status       int           DEFAULT 1    COMMENT '流水状态 1有效 0已冲回',
  del_flag     int           DEFAULT 0    COMMENT '删除标记 0正常 1删除',
  create_by    varchar(64)   DEFAULT NULL COMMENT '创建者',
  create_time  datetime      DEFAULT NULL COMMENT '创建时间',
  update_by    varchar(64)   DEFAULT NULL COMMENT '更新者',
  update_time  datetime      DEFAULT NULL COMMENT '更新时间',
  remark       varchar(500)  DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_nwfm_flow (job_no, row_no, flow_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核验结算流水';

-- 留痕：这一叠走过哪一段、每一行成与不成、谁动的、什么时候动的，倒着查得出来。
-- 只追加、不改写。
CREATE TABLE IF NOT EXISTS t_nwfm_job_trail (
  id           bigint       NOT NULL COMMENT '主键',
  job_no       varchar(32)  DEFAULT NULL COMMENT '册次码',
  row_no       int          DEFAULT NULL COMMENT '行次(册首动作为0)',
  stage        int          DEFAULT NULL COMMENT '所处段 1提交 2核验 3退回',
  action       varchar(32)  DEFAULT NULL COMMENT '动作 SUBMIT/EFFECT_OK/EFFECT_FAIL/ROLLBACK_OK/ROLLBACK_SKIP/TRANSIT',
  result       int          DEFAULT NULL COMMENT '结果 1成 0未成',
  detail       varchar(500) DEFAULT NULL COMMENT '说明(含未成原因)',
  actor        varchar(64)  DEFAULT NULL COMMENT '操作人',
  act_time     datetime     DEFAULT NULL COMMENT '动作时刻',
  del_flag     int          DEFAULT 0    COMMENT '删除标记 0正常 1删除',
  create_by    varchar(64)  DEFAULT NULL COMMENT '创建者',
  create_time  datetime     DEFAULT NULL COMMENT '创建时间',
  update_by    varchar(64)  DEFAULT NULL COMMENT '更新者',
  update_time  datetime     DEFAULT NULL COMMENT '更新时间',
  remark       varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_nwfm_trail_job (job_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核验作业留痕';

-- 作业主表补一列：交件方的幂等请求号（不是册次码，册次码仍由系统发）。
ALTER TABLE t_nwfm_job ADD COLUMN req_no varchar(64) DEFAULT NULL COMMENT '交件请求号(幂等用,非册次码)' AFTER job_name;
-- 未决批次内同一请求号只收一叠。
ALTER TABLE t_nwfm_job ADD UNIQUE KEY uk_nwfm_job_req (req_no);
-- 号和行次定死：同一册次码下行次唯一，交上来不许挪窝/重排。
ALTER TABLE t_nwfm_job ADD UNIQUE KEY uk_nwfm_job_row (job_no, row_no);
