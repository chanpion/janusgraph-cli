package com.chenpp.janus.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

/**
 * @author April.Chen
 */
public class JsonFormatter {

    public static String toJson(Object obj) {
        return JSON.toJSONString(obj, SerializerFeature.PrettyFormat);
    }
}