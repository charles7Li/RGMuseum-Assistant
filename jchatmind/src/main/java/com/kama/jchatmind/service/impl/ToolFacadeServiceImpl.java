package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.service.ToolFacadeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;
    private final boolean databaseToolEnabled;

    // 初始化工具集合与数据库工具开关配置。
    public ToolFacadeServiceImpl(
            List<Tool> tools,
            @Value("${tool.database.enabled:false}") boolean databaseToolEnabled
    ) {
        this.tools = tools;
        this.databaseToolEnabled = databaseToolEnabled;
    }

    @Override
    // 返回系统已注册的全部工具列表。
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    // 返回可由 Agent 按配置选择启用的工具列表。
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    // 返回系统固定启用的工具列表。
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    // 按工具类型过滤并应用数据库工具开关。
    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .filter(tool -> databaseToolEnabled || !"dataBaseTool".equals(tool.getName()))
                .toList();
    }
}
