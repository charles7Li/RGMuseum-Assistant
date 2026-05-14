package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chat_session】的数据库操作Mapper
 * @createDate 2025-12-02 14:52:46
 * @Entity com.kama.jchatmind.model.entity.ChatSession
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
    List<ChatSession> selectAll();

    List<ChatSession> selectByAgentId(@Param("agentId") String agentId);
}
