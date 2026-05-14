package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.ChatMemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMemorySummaryMapper extends BaseMapper<ChatMemorySummary> {
    ChatMemorySummary selectBySessionId(@Param("sessionId") String sessionId);

    int upsertSummary(@Param("sessionId") String sessionId, @Param("summaryText") String summaryText);
}
