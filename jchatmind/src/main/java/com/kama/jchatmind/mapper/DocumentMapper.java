package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【document】的数据库操作Mapper
 * @createDate 2025-12-02 15:42:18
 * @Entity com.kama.jchatmind.model.entity.Document
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    List<Document> selectAll();

    List<Document> selectByKbId(@Param("kbId") String kbId);
}
