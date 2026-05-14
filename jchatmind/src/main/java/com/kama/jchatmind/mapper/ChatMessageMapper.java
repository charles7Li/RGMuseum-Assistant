package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chat_message】的数据库操作Mapper
 * @createDate 2025-12-02 15:40:13
 * @Entity com.kama.jchatmind.model.entity.ChatMessage
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    List<ChatMessage> selectBySessionId(@Param("sessionId") String sessionId);

    List<ChatMessage> selectBySessionIdRecently(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
