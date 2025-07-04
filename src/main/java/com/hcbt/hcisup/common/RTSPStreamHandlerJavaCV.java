package com.hcbt.hcisup.common;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 使用 JavaCV 将 RTSP 视频流转推为 RTMP（FLV 封装）到 SRS 服务器
 * @author llg
 * @slogan 致敬大师，致敬未来的你
 * @create 2025-06-05 10:03
 */
@Slf4j
public class RTSPStreamHandlerJavaCV {
    /**
     * 表示一个推流会话，包括 JavaCV 的采集器与推流器等状态信息
     */
    static class StreamSession {
        FFmpegFrameGrabber grabber;         // JavaCV 的视频抓取器（用于读取 RTSP）
        FFmpegFrameRecorder recorder;       // JavaCV 的视频推流器（用于推送到 RTMP）
        ExecutorService executor;           // 独立线程池用于处理帧抓取与推流
        boolean running = false;            // 当前推流是否正在运行
        long lastActiveTime = System.currentTimeMillis(); // 最后活跃时间（用于自动清理）
        String videoCodec;                  // 视频编码格式（如 h264、hevc 等）
    }

    /** 用户启动推流的返回结果，包括是否成功、视频编码格式及描述信息 */
    public static class StartResult {
        public boolean success;
        public String videoCodec;
        public String message;

        public StartResult(boolean success, String videoCodec, String message) {
            this.success = success;
            this.videoCodec = videoCodec;
            this.message = message;
        }
    }

    /** 记录每个通道的推流会话：channel -> session */
    private static final Map<Integer, StreamSession> channelSessionMap = new ConcurrentHashMap<>();

    /** 记录每个通道被哪些用户使用：channel -> userIdSet */
    private static final Map<Integer, Set<Integer>> channelUserMap = new ConcurrentHashMap<>();

    /**
     * 启动 RTSP 到 RTMP 的推流任务（使用 JavaCV）
     *
     * @param userId 用户 ID
     * @param channel 通道号
     * @param inputUrl RTSP 源地址
     * @param outputUrl RTMP 推送目标（SRS 支持 FLV）
     * @return StartResult 启动状态信息
     */
    public static StartResult startStream(int userId, int channel, String inputUrl, String outputUrl) {
        // 注册用户使用该通道
        channelUserMap.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(userId);
        StreamSession session = channelSessionMap.get(channel);

        // 如果当前通道已经在推流中，则复用该会话
        if (session != null && session.running) {
            session.lastActiveTime = System.currentTimeMillis();
            log.info("复用通道推流中，channel: {}, userId: {}", channel, userId);
            return new StartResult(true, session.videoCodec, "当前通道已在推流，复用会话");
        }

        // 判断推流总路数是否超过上限
        if (!StreamLimitManager.tryAcquire()) {
            return new StartResult(false, null, "系统已达推流上限（" + StreamLimitManager.getRunningCount() + "路）");
        }

        try {

            // 创建 FrameGrabber 用于拉取 RTSP 视频帧
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputUrl);
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("stimeout", "5000000"); // 微秒
            grabber.start();
            // 获取视频编码格式（可用于后续判断是否转码）
            String codecName = grabber.getVideoCodecName();

            // 创建 FrameRecorder 推送到 RTMP（封装为 FLV）
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputUrl,
                    grabber.getImageWidth(), grabber.getImageHeight(), 0);
            recorder.setFormat("flv");
            recorder.setFrameRate(15);
            recorder.setVideoBitrate(400_000);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.start();

            // 注册会话
            StreamSession newSession = new StreamSession();
            newSession.grabber = grabber;
            newSession.recorder = recorder;
            newSession.running = true;
            newSession.videoCodec = codecName;
            newSession.executor = Executors.newSingleThreadExecutor();
            channelSessionMap.put(channel, newSession);
            log.info("开启新通道，channel: {}, inputUrl: {}, outputUrl: {}", channel, inputUrl, outputUrl);
            // 启动线程异步推送帧数据
            newSession.executor.submit(() -> {
                try {
                    Frame frame;
                    while (newSession.running && (frame = grabber.grabImage()) != null) {
                        recorder.record(frame);
                    }
                } catch (Exception e) {
                    log.error("通道 {} 推流异常：{}", channel, e.getMessage());
                } finally {
                    stopStream(userId, channel);
                }
            });

            log.info("新通道推流启动成功：channel={}, inputUrl={}, outputUrl={} ", channel, inputUrl, outputUrl);
            return new StartResult(true, codecName, "推流启动成功");
        } catch (Exception e) {
            log.error("启动通道 {} 推流失败：{}", channel, e.getMessage());
            StreamLimitManager.release();
            return new StartResult(false, null, "推流启动异常: " + e.getMessage());
        }
    }

    /** 停止某个通道的推流任务（如果无用户使用） */
    public static Boolean stopStream(int userId, int channel) {
        Set<Integer> users = channelUserMap.get(channel);
        if (users != null) {
            users.remove(userId);
            if (!users.isEmpty()) {
                log.info("仍有用户使用通道{}，不停止。剩余用户：{}", channel, users);
                return false;
            }
        }

        StreamSession session = channelSessionMap.remove(channel);
        if (session != null) {
            session.running = false;
            try {
                if (session.grabber != null) session.grabber.stop();
                if (session.recorder != null) session.recorder.stop();
                if (session.executor != null) session.executor.shutdownNow();
                log.info("通道 {} 推流已停止", channel);
            } catch (Exception e) {
                log.warn("通道 {} 停止推流异常：{}", channel, e.getMessage());
            }
            StreamLimitManager.release();
        }
        return true;
    }

    /** 查看通道的用户集合 */
    public static Set<Integer> isRunning(int channel) {
        return channelUserMap.get(channel);
    }
    /** 返回当前正在推流的所有通道号 */
    public static Set<Integer> playingChannels() {
        return channelSessionMap.keySet();
    }


    /** 每分钟检查是否有会话超过空闲时间（15分钟）自动关闭 */
    private static final ScheduledExecutorService cleaner = Executors.newScheduledThreadPool(1);
    private static final long TIMEOUT_MS = 15 * 60 * 1000;

    // 静态初始化块：每分钟检测一次空闲会话
    static {
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, StreamSession> entry : channelSessionMap.entrySet()) {
                int channel = entry.getKey();
                StreamSession session = entry.getValue();
                if (now - session.lastActiveTime > TIMEOUT_MS) {
                    log.warn("通道 {} 空闲超时，自动停止推流", channel);
                    stopStream(-1, channel);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }


}
