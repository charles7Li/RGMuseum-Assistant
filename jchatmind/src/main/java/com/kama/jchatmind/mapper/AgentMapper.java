package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @author charon
 * @description 针对表【agent】的数据库操作Mapper
 * @createDate 2025-12-02 14:48:44
 * @Entity com.kama.jchatmind.model.entity.Agent
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
    List<Agent> selectAll();
}
