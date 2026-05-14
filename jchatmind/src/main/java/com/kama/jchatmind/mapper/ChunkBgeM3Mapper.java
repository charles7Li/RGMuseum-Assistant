package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jchatmind.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper extends BaseMapper<ChunkBgeM3> {
    List<ChunkBgeM3> selectByDocId(
            @Param("docId") String docId
    );
    List<ChunkBgeM3> selectByKbId(
            @Param("kbId") String kbId
    );
    List<ChunkBgeM3> selectByKbIdAndArtifactId(
            @Param("kbId") String kbId,
            @Param("artifactId") String artifactId
    );
    List<ChunkBgeM3> selectByIds(
            @Param("ids") List<String> ids
    );

    List<Map<String, Object>> selectTableColumns(
            @Param("tableName") String tableName
    );

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );
    List<ChunkBgeM3> similaritySearchByArtifactId(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("artifactId") String artifactId,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> lexicalSearch(
            @Param("kbId") String kbId,
            @Param("queryText") String queryText,
            @Param("queryLike") String queryLike,
            @Param("limit") int limit
    );
    List<ChunkBgeM3> lexicalSearchByArtifactId(
            @Param("kbId") String kbId,
            @Param("queryText") String queryText,
            @Param("queryLike") String queryLike,
            @Param("artifactId") String artifactId,
            @Param("limit") int limit
    );
}
