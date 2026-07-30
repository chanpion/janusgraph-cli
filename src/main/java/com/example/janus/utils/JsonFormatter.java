package com.example.janus.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

public class JsonFormatter {

    public static String toJson(Object obj) {
        return JSON.toJSONString(obj,
                JSONWriter.Feature.PrettyFormat,
                JSONWriter.Feature.WriteNulls);
    }
}