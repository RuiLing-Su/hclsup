package com.hcbt.hcisup.common;

import com.hcbt.hcisup.service.StreamService;
import lombok.extern.slf4j.Slf4j;

/**
 * 流处理器 V2
 * 用于处理视频流数据，解析 PES 包并提取裸流（ES）
 * 通过跳过部分帧解析降低 CPU 使用率。
 *
 * 注意：这不是一个 Spring 托管的组件，而是一个通过工厂创建的实例。
 */
@Slf4j
public class HandleStreamV2 {

    // 存储拼接后的裸流数据
    private byte[] allEsBytes = null;
    // 用户 ID
    private final Integer luserId;
    private final Integer sessionId;
    private final StreamService streamService;

    // 是否正在处理流数据
    private boolean isProcessing = false;

    /**
     * 构造函数
     *
     * @param luserId 用户 ID
     * @param streamService 流媒体服务
     */
    public HandleStreamV2(Integer luserId, Integer sessionId, StreamService streamService) {
        this.luserId = luserId;
        this.sessionId = sessionId;
        this.streamService = streamService;
    }

    /**
     * 开始处理视频流数据
     *
     * @param outputData 输入的视频流数据
     */
    public void startProcessing(final byte[] outputData) {
        // 验证输入数据
        if (!validateInput(outputData)) {
            return;
        }

        // 验证服务注入
        if (streamService == null) {
            log.error("streamService 未注入，无法处理视频流");
            return;
        }

        // 标记处理状态
        if (!isProcessing) {
            isProcessing = true;
            log.debug("开始为用户 ID: {} 处理视频流", luserId);
        }

        try {
            // 检查sessionId是否有效
            if (sessionId == null) {
                log.error("会话ID为空，无法处理视频流");
                return;
            }

            // 检查是否为 RTP 包（包头为 00 00 01 BA）
            if (isRtpPacket(outputData)) {
                synchronized (this) { // 同步访问共享资源
                    if (allEsBytes != null && allEsBytes.length > 0) {
                        streamService.processStreamData(sessionId, allEsBytes);
                        allEsBytes = null; // 重置
                    }
                }
            }

            // 检查是否为 PES 包（包头为 00 00 01 E0）
            if (isPesPacket(outputData)) {
                // 提取并拼接裸流
                byte[] esBytes = extractEsBytes(outputData);
                if (esBytes != null && esBytes.length > 0) {
                    synchronized (this) { // 同步访问共享资源
                        allEsBytes = concatenateEsBytes(allEsBytes, esBytes);
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理视频流时发生错误: {}", e.getMessage(), e);
        }
    }

    /**
     * 验证输入数据是否有效
     *
     * @param outputData 输入数据
     * @return 是否有效
     */
    private boolean validateInput(final byte[] outputData) {
        if (outputData == null || outputData.length <= 0) {
            log.warn("输入数据为空或无效，用户 ID: {}", luserId);
            return false;
        }
        return true;
    }

    /**
     * 判断是否为 RTP 包（包头为 00 00 01 BA）
     *
     * @param data 输入数据
     * @return 是否为 RTP 包
     */
    private boolean isRtpPacket(final byte[] data) {
        return data.length >= 4 &&
                (data[0] & 0xff) == 0x00 &&
                (data[1] & 0xff) == 0x00 &&
                (data[2] & 0xff) == 0x01 &&
                (data[3] & 0xff) == 0xBA;
    }

    /**
     * 判断是否为 PES 包（包头为 00 00 01 E0）
     *
     * @param data 输入数据
     * @return 是否为 PES 包
     */
    private boolean isPesPacket(final byte[] data) {
        return data.length >= 4 &&
                (data[0] & 0xff) == 0x00 &&
                (data[1] & 0xff) == 0x00 &&
                (data[2] & 0xff) == 0x01 &&
                (data[3] & 0xff) == 0xE0;
    }

    /**
     * 提取 PES 包中的裸流数据（ES）
     *
     * @param outputData 输入的 PES 包数据
     * @return 提取的裸流数据
     */
    private byte[] extractEsBytes(final byte[] outputData) {
        try {
            // 验证输入数据长度，确保索引不会越界
            if (outputData.length <= 8) {
                log.warn("数据长度不足，无法提取 ES 数据");
                return new byte[0];
            }

            // 读取 PES 包头长度字段
            int headerSize = outputData[8] & 0xff;

            // 计算裸流起始位置和长度
            int from = 9 + headerSize;
            int len = outputData.length - from;

            // 检查计算出的长度是否有效
            if (len <= 0 || from >= outputData.length) {
                log.warn("计算的 ES 数据长度无效: {}, 数据总长度: {}", len, outputData.length);
                return new byte[0];
            }

            // 提取裸流
            byte[] esBytes = new byte[len];
            System.arraycopy(outputData, from, esBytes, 0, len);
            return esBytes;
        } catch (Exception e) {
            log.error("提取 ES 数据时发生错误: {}", e.getMessage(), e);
            return new byte[0];
        }
    }

    /**
     * 拼接裸流数据
     *
     * @param existingBytes 已有的裸流数据
     * @param newBytes      新的裸流数据
     * @return 拼接后的裸流数据
     */
    private byte[] concatenateEsBytes(final byte[] existingBytes, final byte[] newBytes) {
        if (existingBytes == null) {
            return newBytes;
        }

        if (newBytes == null || newBytes.length == 0) {
            return existingBytes;
        }

        try {
            // 创建新数组并拼接数据
            byte[] combinedBytes = new byte[existingBytes.length + newBytes.length];
            System.arraycopy(existingBytes, 0, combinedBytes, 0, existingBytes.length);
            System.arraycopy(newBytes, 0, combinedBytes, existingBytes.length, newBytes.length);
            return combinedBytes;
        } catch (Exception e) {
            log.error("拼接 ES 数据时发生错误: {}", e.getMessage(), e);
            return existingBytes;
        }
    }
}