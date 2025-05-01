package com.hcbt.hcisup.common;

import com.hcbt.hcisup.service.StreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HandleStreamV2 的工厂类
 * 用于创建 HandleStreamV2 实例，解决 Spring 依赖注入与非 Spring 管理的组件之间的桥接问题
 */
@Component
public class HandleStreamV2Factory {

    private final StreamService streamService;

    @Autowired
    public HandleStreamV2Factory(StreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * 创建 HandleStreamV2 实例
     *
     * @param luserId 用户 ID
     * @return 新的 HandleStreamV2 实例
     */
    public HandleStreamV2 createHandleStream(Integer luserId, Integer sessionId) {
        return new HandleStreamV2(luserId, sessionId, streamService);
    }
}