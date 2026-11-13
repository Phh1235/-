package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回调幂等表：event_id 与 nonce 唯一索引，重复回调/重放直接命中唯一约束。
 */
@Data
@TableName("agent_callback_log")
public class AgentCallbackLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String nonce;
    private Long taskId;
    private String eventType;
    private String bodyHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime receivedAt;
}
