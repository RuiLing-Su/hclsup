package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.common.FFmpegStreamHandler;
import com.hcbt.hcisup.service.FrameDetectionProcessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * FFmpeg 推流控制器
 * 提供通过 FFmpeg 进行 RTMP/SRT 推流控制的接口
 */
@RestController
@RequestMapping("/stream")
@Tag(name = "FFmpeg推流接口", description = "通过 FFmpeg 进行 RTMP 推流控制")
public class FFmpegStreamController {

    @Resource
    private SMS sms;
    @Autowired
    private FrameDetectionProcessor frameDetectionProcessor;
    private final String hlsDirBasePath;

    public FFmpegStreamController(@Value("${app.stream.hls-dir}") String hlsDirBasePath) {
        this.hlsDirBasePath = hlsDirBasePath;
    }

    /**
     * 开始 HLS 推流（使用 FFmpeg）
     *
     * @param channel 通道
     * @param hlsPath 目标 HLS 文件路径（例如 /home/ubuntu/hc_isup/srs/trunk/hls/stream_1.m3u8）
     * @return AjaxResult 推流启动结果
     */
    @PostMapping("/startHlsStream")
    @Operation(summary = "开始 HLS 推流")
    public AjaxResult startHlsStream(@RequestParam("channel") Integer channel,
                                     @RequestParam("hlsPath") String hlsPath) {
        Integer luserId = 0;
        hlsPath = hlsDirBasePath + hlsPath + ".m3u8";

        // 验证 HLS 路径
        if (!isValidHlsPath(hlsPath)) {
            return AjaxResult.error("无效的 HLS 路径");
        }

        // 启动 FFmpeg 进程
        if (!FFmpegStreamHandler.startFFmpeg(luserId, hlsPath)) {
            return AjaxResult.error("启动 FFmpeg 失败");
        }

        // 启动帧检测
        frameDetectionProcessor.startDetection(luserId);

        // 启动 ISUP 流并处理结果
        CompletableFuture<String> future = new CompletableFuture<>();
        sms.RealPlayWithFFmpeg(luserId, channel, future);
        try {
            String result = future.get();
            if ("true".equals(result)) {
                return AjaxResult.success("HLS 流启动成功，播放路径: " + hlsPath);
            } else {
                FFmpegStreamHandler.stopFFmpeg(luserId);
                frameDetectionProcessor.stopDetection(luserId);
                return AjaxResult.error("启动流失败");
            }
        } catch (InterruptedException | ExecutionException e) {
            FFmpegStreamHandler.stopFFmpeg(luserId);
            frameDetectionProcessor.stopDetection(luserId);
            return AjaxResult.error("流启动失败: " + e.getMessage());
        }
    }

    /**
     * 验证 HLS 路径是否有效
     *
     * @param hlsPath HLS 文件路径
     * @return 是否有效
     */
    private boolean isValidHlsPath(String hlsPath) {
        if (hlsPath == null || !hlsPath.endsWith(".m3u8")) {
            return false;
        }
        return true;
    }


//    /**
//     * 开始 SRT 推流（使用 FFmpeg）
//     *
//     * @param luserId 用户 ID
//     * @param channel 通道
//     * @param srtUrl  目标 SRT URL
//     * @return AjaxResult 推流启动结果
//     */
//    @PostMapping("/startSrtStream")
//    @Operation(summary = "开始 SRT 推流")
//    public AjaxResult startSrtStream(
//            @RequestParam("luserId") Integer luserId,
//            @RequestParam("channel") Integer channel,
//            @RequestParam("srtUrl") String srtUrl) {
//
//        // 验证 SRT URL 格式
//        if (!isValidSrtUrl(srtUrl)) {
//            return AjaxResult.error("无效的 SRT URL 格式");
//        }
//
//        // 启动 FFmpeg 进程
//        if (!startFFmpegProcess(luserId, srtUrl)) {
//            return AjaxResult.error("启动 FFmpeg 失败");
//        }
//
//        // 启动 ISUP 流并处理结果
//        return startIsupStream(luserId, channel);
//    }
//
//    /**
//     * 验证 SRT URL 是否有效
//     *
//     * @param srtUrl SRT URL
//     * @return 是否有效
//     */
//    private boolean isValidSrtUrl(String srtUrl) {
//        // 检查 URL 是否为空且以 "srt://" 开头
//        return srtUrl != null && srtUrl.startsWith("srt://");
//    }
//
//    /**
//     * 启动 FFmpeg 进程
//     *
//     * @param luserId 用户 ID
//     * @param srtUrl  目标 SRT URL
//     * @return 是否成功启动
//     */
//    private boolean startFFmpegProcess(Integer luserId, String srtUrl) {
//        // 调用 FFmpegStreamHandler 的静态方法启动 FFmpeg
//        return FFmpegStreamHandler.startFFmpeg(luserId, srtUrl);
//    }
//
//    /**
//     * 启动 ISUP 流
//     *
//     * @param luserId 用户 ID
//     * @param channel 通道
//     * @return AjaxResult 流启动结果
//     */
//    private AjaxResult startIsupStream(Integer luserId, Integer channel) {
//        CompletableFuture<String> completableFuture = new CompletableFuture<>();
//
//        try {
//            // 调用 SMS 服务启动 ISUP 流（使用 FFmpeg 标志）
//            sms.RealPlayWithFFmpeg(luserId, channel, completableFuture);
//        } catch (Exception e) {
//            // 如果启动失败，停止 FFmpeg 进程并返回错误
//            FFmpegStreamHandler.stopFFmpeg(luserId);
//            frameDetectionProcessor.stopDetection(luserId);
//            return AjaxResult.error("启动流失败: " + e.getMessage());
//        }
//
//        try {
//            // 获取异步结果并判断是否成功
//            String result = completableFuture.get();
//            if (Objects.equals(result, "true")) {
//                return AjaxResult.success("流启动成功");
//            }
//            // 如果结果不是 "true"，停止 FFmpeg 并返回错误
//            FFmpegStreamHandler.stopFFmpeg(luserId);
//            frameDetectionProcessor.stopDetection(luserId);
//            return AjaxResult.error("启动流失败");
//        } catch (InterruptedException | ExecutionException e) {
//            // 处理异步执行中的异常，停止 FFmpeg 并返回错误
//            FFmpegStreamHandler.stopFFmpeg(luserId);
//            frameDetectionProcessor.stopDetection(luserId);
//            return AjaxResult.error("流启动被中断: " + e.getMessage());
//        }
//    }

    /**
     * 停止 RTSP 推流
     *
     * @return AjaxResult 停止结果
     */
    @PostMapping("/stopStream")
    @Operation(summary = "停止推流")
    public AjaxResult stopRtspStream() {
        Integer luserId = 0;

        // 从映射中获取会话 ID
        Integer sessionId = SMS.LuserIDandSessionMap.get(luserId);
        if (sessionId == null) {
            return AjaxResult.error("未找到活动的流");
        }

        try {
            // 停止 ISUP 流并清理 FFmpeg 进程
            sms.StopRealPlay(luserId, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
            FFmpegStreamHandler.stopFFmpeg(luserId);
            frameDetectionProcessor.stopDetection(luserId);
            return AjaxResult.success("流已成功停止");
        } catch (Exception e) {
            return AjaxResult.error("停止流失败: " + e.getMessage());
        }
    }
}