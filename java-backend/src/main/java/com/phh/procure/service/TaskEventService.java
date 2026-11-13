package com.phh.procure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.entity.TaskStageEvent;
import com.phh.procure.mapper.TaskStageEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段事件：先落库再推 SSE。
 * 落库是为了断线重连能回放；推 SSE 是为了前端实时。两者顺序不能反——先推后落库会丢历史。
 */
@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskStageEventMapper eventMapper;
    private final SseService sseService;

    public TaskStageEvent record(Long taskId, String stage, String status, Integer progress, String message) {
        TaskStageEvent event = new TaskStageEvent();
        event.setTaskId(taskId);
        event.setStage(stage);
        event.setSeq(nextSeq(taskId));
        event.setEventStatus(status);
        event.setMessage(message);
        eventMapper.insert(event);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("stage", stage);
        data.put("status", status);
        data.put("progress", progress);
        data.put("message", message);
        data.put("seq", event.getSeq());
        data.put("at", event.getCreatedAt());
        sseService.publish(taskId, "stage", data);
        return event;
    }

    public List<TaskStageEvent> list(Long taskId) {
        return eventMapper.selectList(Wrappers.<TaskStageEvent>lambdaQuery()
                .eq(TaskStageEvent::getTaskId, taskId)
                .orderByAsc(TaskStageEvent::getSeq));
    }

    private int nextSeq(Long taskId) {
        TaskStageEvent last = eventMapper.selectOne(Wrappers.<TaskStageEvent>lambdaQuery()
                .eq(TaskStageEvent::getTaskId, taskId)
                .orderByDesc(TaskStageEvent::getSeq)
                .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 1 : last.getSeq() + 1;
    }
}
