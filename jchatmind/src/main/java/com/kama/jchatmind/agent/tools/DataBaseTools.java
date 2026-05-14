package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DataBaseTools implements Tool {

    private static final String CHUNK_TABLE = "chunk_bge_m3";
    private static final Pattern SQL_COLUMNS_QUERY = Pattern.compile(
            "^\\s*select\\s+column_name\\s*,\\s*data_type\\s+from\\s+information_schema\\.columns\\s+where\\s+table_name\\s*=\\s*'([a-zA-Z0-9_]+)'\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQL_CHUNK_BY_DOC_QUERY = Pattern.compile(
            "^\\s*select\\s+content\\s+from\\s+chunk_bge_m3\\s+where\\s+(doc_id|document_id)\\s*=\\s*'([0-9a-fA-F\\-]{36})'\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    public DataBaseTools(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "Read-only SQL helper with strict whitelist templates for safe museum data lookup.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "Execute whitelisted read-only SQL templates only. Supported: 1) table columns for chunk_bge_m3; 2) chunk content by doc_id."
    )
    public String query(String sql) {
        try {
            if (sql == null || sql.isBlank()) {
                return "错误：SQL 不能为空。";
            }
            String normalizedSql = normalizeSql(sql);

            Matcher columnsMatcher = SQL_COLUMNS_QUERY.matcher(normalizedSql);
            if (columnsMatcher.matches()) {
                String tableName = columnsMatcher.group(1).toLowerCase(Locale.ROOT);
                if (!CHUNK_TABLE.equals(tableName)) {
                    return "错误：仅允许查询 chunk_bge_m3 的表结构。";
                }
                List<Map<String, Object>> rows = chunkBgeM3Mapper.selectTableColumns(tableName);
                List<String> lines = new ArrayList<>();
                lines.add("| column_name | data_type |");
                lines.add("|-------------|-----------|");
                if (rows == null || rows.isEmpty()) {
                    lines.add("| (无数据) | (无数据) |");
                } else {
                    for (Map<String, Object> row : rows) {
                        lines.add("| " + toText(row.get("column_name")) + " | " + toText(row.get("data_type")) + " |");
                    }
                }
                return "查询结果:\n" + String.join("\n", lines);
            }

            Matcher contentMatcher = SQL_CHUNK_BY_DOC_QUERY.matcher(normalizedSql);
            if (contentMatcher.matches()) {
                String docId = contentMatcher.group(2);
                List<ChunkBgeM3> chunks = chunkBgeM3Mapper.selectByDocId(docId);
                List<String> lines = new ArrayList<>();
                lines.add("| content |");
                lines.add("|---------|");
                if (chunks == null || chunks.isEmpty()) {
                    lines.add("| (无数据) |");
                } else {
                    for (ChunkBgeM3 chunk : chunks) {
                        String content = chunk == null ? "" : toText(chunk.getContent());
                        lines.add("| " + content.replace("\n", "\\n") + " |");
                    }
                }
                return "查询结果:\n" + String.join("\n", lines);
            }

            return "错误：SQL 不在白名单模板中。仅支持："
                    + "1) SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'chunk_bge_m3' "
                    + "2) SELECT content FROM chunk_bge_m3 WHERE doc_id = 'uuid'";
        } catch (Exception e) {
            log.error("databaseQuery failed: {}", e.getMessage(), e);
            return "错误：数据库查询失败 - " + e.getMessage();
        }
    }

    private String normalizeSql(String sql) {
        String normalized = sql.trim();
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replace(';', ' ').trim();
        if (normalized.toLowerCase(Locale.ROOT).contains("chunk_bge_m3")
                && normalized.toLowerCase(Locale.ROOT).contains("document_id")) {
            normalized = normalized.replaceAll("(?i)\\bdocument_id\\b", "doc_id");
        }
        return normalized;
    }

    private String toText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
