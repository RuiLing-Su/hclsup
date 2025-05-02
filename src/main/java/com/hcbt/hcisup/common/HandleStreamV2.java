package com.hcbt.hcisup.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 流处理器 V2
 * 用于处理视频流数据，解析 PES 包并提取裸流（ES）
 * 通过跳过部分帧解析降低 CPU 使用率。
 */
@Slf4j
public class HandleStreamV2 {

    // 存储拼接后的裸流数据
    private byte[] allEsBytes = null;
    // 用户 ID
    private final Integer luserId;
    // 是否正在处理流数据
    private boolean isProcessing = false;
    /**
     * 构造函数
     *
     * @param luserId     用户 ID
     */
    public HandleStreamV2(Integer luserId) {
        this.luserId = luserId;
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

        // 标记处理状态
        if (!isProcessing) {
            isProcessing = true;
            log.debug("开始为用户 ID: {} 处理视频流", luserId);
        }

        // 检查是否为 RTP 包（包头为 00 00 01 BA）
        if (isRtpPacket(outputData)) {
            // 处理完整的帧数据
            processCompleteFrame();
            allEsBytes = null;
        }

        // 检查是否为 PES 包（包头为 00 00 01 E0）
        if (isPesPacket(outputData)) {
            // 提取并拼接裸流
            byte[] esBytes = extractEsBytes(outputData);
            allEsBytes = concatenateEsBytes(allEsBytes, esBytes);
        }
    }

    /**
     * 处理完整的帧数据
     * 将累积的裸流数据发送到 WebSocket 或 FFmpeg
     */
    private void processCompleteFrame() {
        // 通过 FFmpeg 推送数据
        FFmpegStreamHandler.writeData(luserId, allEsBytes);
        log.debug("通过 FFmpeg 推送裸流数据，用户 ID: {}", luserId);
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
        // 计算裸流起始位置和长度
        int from = 9 + (outputData[8] & 0xff);
        int len = outputData.length - 9 - (outputData[8] & 0xff);

        // 提取裸流
        byte[] esBytes = new byte[len];
        System.arraycopy(outputData, from, esBytes, 0, len);
        return esBytes;
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

        // 创建新数组并拼接数据
        byte[] combinedBytes = new byte[existingBytes.length + newBytes.length];
        System.arraycopy(existingBytes, 0, combinedBytes, 0, existingBytes.length);
        System.arraycopy(newBytes, 0, combinedBytes, existingBytes.length, newBytes.length);
        return combinedBytes;
    }

}