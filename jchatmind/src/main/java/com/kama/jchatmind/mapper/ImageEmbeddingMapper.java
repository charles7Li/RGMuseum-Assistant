package com.kama.jchatmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kama.jchatmind.model.entity.ImageEmbedding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImageEmbeddingMapper extends BaseMapper<ImageEmbedding> {
    List<ImageEmbedding> selectByIds(
            @Param("ids") List<String> ids
    );

}
