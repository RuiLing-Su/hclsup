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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

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

    // 存储用户ID与当前推流的通道号映射关系
    private static final Map<Integer, Integer> userChannelMap = new ConcurrentHashMap<>();

    // 存储用户ID与对应自动关闭任务的映射关系
    private static final Map<Integer, ScheduledFuture<?>> autoCloseTaskMap = new ConcurrentHashMap<>();

    // 用于定时执行自动关闭任务的线程池
    private static final ScheduledExecutorService scheduledExecutor =
            Executors.newScheduledThreadPool(5);

    // 自动关闭超时时间（毫秒），默认1小时
    private static final long AUTO_CLOSE_TIMEOUT_MS = 60 * 60 * 1000;

    public FFmpegStreamController(@Value("${app.stream.hls-dir}") String hlsDirBasePath) {
        this.hlsDirBasePath = hlsDirBasePath;
    }

//    /**
//     * 开始 HLS 推流（使用 FFmpeg）
//     *
//     * @param channel 通道
//     * @param hlsPath 目标 HLS 文件名称（例如 /home/ubuntu/hc_isup/srs/trunk/hls/stream_1.m3u8）
//     * @return AjaxResult 推流启动结果
//     */
//    @PostMapping("/startHlsStream")
//    @Operation(summary = "开始 HLS 推流")
//    public AjaxResult startHlsStream(@RequestParam("channel") @Parameter(description = "通道") Integer channel,
//                                     @RequestParam("hlsPath") @Parameter(description = "目标 HLS 文件名称") String hlsPath) {
//        Integer luserId = 0;
//        hlsPath = hlsDirBasePath + hlsPath + ".m3u8";
//
//        // 验证 HLS 路径
//        if (!isValidHlsPath(hlsPath)) {
//            return AjaxResult.error("无效的 HLS 路径");
//        }
//
//        // 启动 FFmpeg 进程
//        if (!FFmpegStreamHandler.startFFmpeg(luserId,hlsPath)) {
//            return AjaxResult.error("启动 FFmpeg 失败");
//        }
//
//        // 启动 ISUP 流并处理结果
//        CompletableFuture<String> future = new CompletableFuture<>();
//        sms.RealPlayWithFFmpeg(luserId, channel, future);
//        try {
//            String result = future.get();
//            if ("true".equals(result)) {
//                Thread.sleep(8000);
//                return AjaxResult.success(hlsPath);
//            } else {
//                FFmpegStreamHandler.stopFFmpeg(luserId);
//                return AjaxResult.error("启动流失败");
//            }
//        } catch (InterruptedException | ExecutionException e) {
//            FFmpegStreamHandler.stopFFmpeg(luserId);
//            return AjaxResult.error("流启动失败: " + e.getMessage());
//        }
//    }

    @PostMapping("/smartStream")
    @Operation(summary = "智能推流", description = "自动管理通道切换和超时关闭的推流服务")
    public AjaxResult smartStream(@RequestParam("channel") @Parameter(description = "通道号") Integer channel) {
        Map<String, String> data = new HashMap<>();
        Integer luserId = 0; // 默认用户ID
        data.put("code", "hevc");
        // 生成唯一的HLS路径
        String hlsFileName = "stream_" + channel;
        String hlsPath = hlsDirBasePath + hlsFileName + ".m3u8";
        String streamKey = luserId + "_" + channel;
        String flvUrl = String.format("http://%s:%d/live/%s.flv", "101.132.99.208", 18080, streamKey);
        data.put("flvUrl", flvUrl);
        // 检查当前是否已有流在推送
        Integer currentChannel = userChannelMap.get(luserId);

        // 如果已经在推相同通道的流，则重置自动关闭计时器并直接返回成功
        if (currentChannel != null && currentChannel.equals(channel)) {
            // 重置自动关闭计时器
            resetAutoCloseTimer(luserId,channel);
            return AjaxResult.success("继续使用现有流", data);
        }

        // 检查FFmpeg是否已经在运行
        boolean ffmpegRunning = FFmpegStreamHandler.isProcessAlive(luserId);

        // 如果FFmpeg未运行，则启动FFmpeg
        if (!ffmpegRunning) {
            // if (!FFmpegStreamHandler.startFFmpeg(luserId,  hlsPath)) {
            //     return AjaxResult.error("启动 FFmpeg 失败");
            // }
            if (!FFmpegStreamHandler.startFFmpeg2(luserId,channel)) {
                return AjaxResult.error("启动 FFmpeg 失败");
            }
        }

        // 启动 ISUP 流并处理结果
        CompletableFuture<String> future = new CompletableFuture<>();
        sms.RealPlayWithFFmpeg(luserId, channel, 0,future);

        try {
            String result = future.get();
            if ("true".equals(result)) {
                // 更新通道映射
                userChannelMap.put(luserId, channel);

                // 设置自动关闭计时器
                setupAutoCloseTimer(luserId,channel);

                // Thread.sleep(8000);
                return AjaxResult.success("流启动成功", data);
            } else {
                if (!ffmpegRunning) {
                    FFmpegStreamHandler.stopFFmpeg(luserId);
                }
                return AjaxResult.error("启动流失败");
            }
        } catch (InterruptedException | ExecutionException e) {
            if (!ffmpegRunning) {
                FFmpegStreamHandler.stopFFmpeg(luserId);
            }
            return AjaxResult.error("流启动失败: " + e.getMessage());
        }
    }

    /**
     * 停止 RTSP 推流
     *
     * @return AjaxResult 停止结果
     */
    @PostMapping("/stopStream")
    @Operation(summary = "停止推流")
    public AjaxResult stopRtspStream(@RequestParam("channel") @Parameter(description = "通道号") Integer channel) {
        Integer luserId = 0;

        boolean result = stopStreamInternal(luserId,channel);

        if (result) {
            return AjaxResult.success("流已成功停止");
        } else {
            return AjaxResult.error("未找到活动的流或停止失败");
        }
    }

    /**
     * 内部方法：停止指定用户ID的流
     *
     * @param luserId 用户ID
     * @return 是否成功停止
     */
    private boolean stopStreamInternal(Integer luserId,Integer channel) {
        // 从映射中获取会话 ID
        Integer sessionId = SMS.LuserIDandSessionMap.get(channel);
        if (sessionId == null) {
            return false;
        }

        try {
            // 停止 ISUP 流并清理 FFmpeg 进程
            sms.StopRealPlay(luserId,channel, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
            FFmpegStreamHandler.stopFFmpeg(luserId);
            frameDetectionProcessor.stopDetection(luserId);

            // 取消自动关闭任务
            cancelAutoCloseTask(luserId);

            // 清除通道映射
            userChannelMap.remove(luserId);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置自动关闭计时器
     *
     * @param luserId 用户ID
     */
    private void setupAutoCloseTimer(Integer luserId,Integer channel) {
        // 先取消已有的计时器（如果存在）
        cancelAutoCloseTask(luserId);

        // 创建新的自动关闭任务
        ScheduledFuture<?> future = scheduledExecutor.schedule(() -> {
            stopStreamInternal(luserId,channel);
        }, AUTO_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // 保存任务引用
        autoCloseTaskMap.put(luserId, future);
    }

    /**
     * 重置自动关闭计时器
     *
     * @param luserId 用户ID
     */
    private void resetAutoCloseTimer(Integer luserId,Integer channel) {
        // 先取消已有的计时器
        cancelAutoCloseTask(luserId);

        // 创建新的自动关闭任务
        setupAutoCloseTimer(luserId,channel);
    }

    /**
     * 取消自动关闭任务
     *
     * @param luserId 用户ID
     */
    private void cancelAutoCloseTask(Integer luserId) {
        ScheduledFuture<?> task = autoCloseTaskMap.remove(luserId);
        if (task != null && !task.isDone()) {
            task.cancel(false);
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
}
