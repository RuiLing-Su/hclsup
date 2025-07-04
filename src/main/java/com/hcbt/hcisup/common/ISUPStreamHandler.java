package com.hcbt.hcisup.common;

import com.hcbt.hcisup.SdkService.StreamService.SMS;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ISUP 裸流推流处理器
 *
 * @author llg
 * @slogan 致敬大师，致敬未来的你
 * @create 2025-05-28 13:40
 */
@Slf4j
@Component
public class ISUPStreamHandler {
    @Autowired
    private SMS sms;
    private static ISUPStreamHandler instance;

    @PostConstruct
    public void init() {
        instance = this;
    }

    /**
     * 封装每一路推流会话的信息
     */
    static class StreamSession {
        Process process;             // FFmpeg 进程对象
        ExecutorService executor;    // 监控线程池，监控进程状态，防止僵尸进程
        volatile boolean running;    // 标记该推流任务是否运行中
        long lastActiveTime = System.currentTimeMillis();// 最近一次接收裸流数据的时间戳（用于超时判断）
        OutputStream ffmpegInput;    // FFmpeg 标准输入流，向其写入裸流数据
        ReentrantLock writeLock = new ReentrantLock(); // 线程安全写入锁
        String videoCodec = "h264";  // 视频编码，ISUP裸流一般是h265（hevc）
    }

    /**
     * 启动推流操作的返回结果类
     */
    public static class StartResult {
        public boolean success;      // 启动是否成功
        public String videoCodec;    // 视频编码格式
        public String message;       // 附加信息（如错误描述或成功提示）

        public StartResult(boolean success, String videoCodec, String message) {
            this.success = success;
            this.videoCodec = videoCodec;
            this.message = message;
        }
    }

    /**
     * 存储所有用户和通道对应的推流会话
     * key1 = userId, key2 = channel, value = StreamSession
     */
    private static final Map<Integer, StreamSession> sessionMap = new ConcurrentHashMap<>();
    // key: channel -> 正在使用该通道的用户ID集合
    private static final Map<Integer, Set<Integer>> channelUserMap = new ConcurrentHashMap<>();
    private static final int MAX_TOTAL_STREAMS = 1;
    private static final AtomicInteger activeStreamCount = new AtomicInteger(0);
    private static final Object streamLock = new Object();

    /**
     * 启动某用户某通道的裸流推流任务
     * <p>
     * 该方法会启动一个 FFmpeg 进程，参数为从标准输入读取裸 H.265 流，推送到指定 RTMP 地址。
     * 如果该通道已经在推流，则直接返回正在运行的结果，不重复启动。
     *
     * @param userId    用户ID
     * @param channel   通道号
     * @param outputUrl RTMP 推流地址（通常为 SRS 或其他推流服务器地址）
     * @param dwStreamType 流类型 0主码流、1子码流
     * @return 启动结果，包括成功与否、视频编码格式和消息
     */
    public static StartResult startStream(int userId, int channel, String outputUrl) {
        int dwStreamType = Integer.parseInt(String.valueOf(channel).substring(String.valueOf(channel).length() - 2));
        // 注册用户使用该通道
        channelUserMap.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(userId);
        // 获取通道推流会话，若无则新建
        StreamSession userSession = sessionMap.get(channel);
        // 如果该通道已有推流会话且运行中，返回提示“正在运行”
        if (userSession != null && userSession.running) {
            userSession.lastActiveTime = System.currentTimeMillis();
            log.info("复用通道推流，channel: {}, outputUrl: {}", channel, outputUrl);
            return new StartResult(true, userSession.videoCodec, "通道正在推流，复用当前会话");
        }
        synchronized (streamLock) {
            // 在启动前检查总数限制
            if (activeStreamCount.get() >= MAX_TOTAL_STREAMS) {
                log.info("当前推流数量:{}", activeStreamCount.get());
                log.warn("会话map的数量：{}", sessionMap.size());
                log.warn("推流数量已满，准备挤出最早的通道，当前准备播放通道{}", channel);
                if (sessionMap.size() > 0) {
                    // 找到最早启动的通道
                    int oldestChannel = 0;
                    for (Map.Entry<Integer, ISUPStreamHandler.StreamSession> entry : sessionMap.entrySet()) {
                        oldestChannel = entry.getKey();
                    }
                    log.warn("挤出通道{}", oldestChannel);
                    if (oldestChannel != 0) {
                        log.warn("关闭通道 channel {}，为新通道腾位", oldestChannel);
                        // 删除会话
                        stopStream2(oldestChannel);
                        // 清除通道用户
                        channelUserMap.remove(oldestChannel);
                        // 停止推流成功后，重新启动推流
                        return startStream(userId, channel, outputUrl);
                    }
                } else {
                    return new StartResult(false, null, "会话map为空，无法挤出最早的通道");
                }
            }
            // 构造 FFmpeg 命令行参数，利用管道方式传入裸流数据
            String codecName = "h264";
            String[] command;
            if(dwStreamType == 1){
                if (channel == 2201 || channel == 2101) {
                    command = new String[]{
                            "ffmpeg",
                            "-f", "hevc",                 // 裸流输入格式为 H.265
                            "-i", "pipe:0",               // 从标准输入读取裸流
                            "-vf", "scale=854:480",       // 缩放分辨率（可改为 640:360 或 854:480）
                            "-c:v", "libx264",            // 转码为 H.264
                            // "-c:v", "copy",
                            "-an",                        // 无音频
                            "-f", "flv",                  // 输出为 FLV 容器（RTMP 支持）
                            outputUrl
                    };
                } else {
                    command = new String[]{
                            "ffmpeg",
                            "-f", "h264",                // 输入是裸 H.264 编码流
                            "-i", "pipe:0",              // 从标准输入读取数据
                            "-vf", "scale=854:480",             // 缩放为低分辨率（可改为 640:360 或 854:480）
                            "-c:v", "libx264",                  // 编码为 H.264（再编码是必须的）
                            // "-c:v", "copy",
                            "-an",                       // 无音频
                            "-f", "flv",                 // 输出为 FLV
                            outputUrl                    // 推流目标地址（RTMP）
                    };
                }
            }else{
                if (channel == 2202 || channel == 2102) {
                    command = new String[]{
                            "ffmpeg",
                            "-f", "hevc",                 // 裸流输入格式为 H.265
                            "-i", "pipe:0",               // 从标准输入读取裸流
                            "-c:v", "libx264",            // 转码为 H.264
                            // "-c:v", "copy",
                            "-an",                        // 无音频
                            "-f", "flv",                  // 输出为 FLV 容器（RTMP 支持）
                            outputUrl
                    };
                } else{
                    command = new String[]{
                            "ffmpeg",
                            "-f", "h264",                // 输入是裸 H.264 编码流
                            "-i", "pipe:0",              // 从标准输入读取数据
                            // "-c:v", "libx264",                  // 编码为 H.264（再编码是必须的）
                            "-c:v", "copy",
                            "-an",                       // 无音频
                            "-f", "flv",                 // 输出为 FLV
                            outputUrl                    // 推流目标地址（RTMP）
                    };
                }

            }


            try {
                log.info("开启新通道，channel: {}, outputUrl: {}", channel, outputUrl);
                // 创建并启动 FFmpeg 进程
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true); // 合并标准错误和标准输出，便于读取日志

                Process process = builder.start();
                // 打印ffmpeg日志
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // log.info("[FFmpeg] {}", line);
                        }
                    } catch (IOException e) {
                        log.error("读取 FFmpeg 输出流失败");
                    }
                }).start();
                // 创建推流会话对象
                StreamSession session = new StreamSession();
                session.process = process;
                session.executor = Executors.newSingleThreadExecutor();
                session.running = true;
                session.lastActiveTime = System.currentTimeMillis();
                session.ffmpegInput = process.getOutputStream();
                session.videoCodec = codecName;
                // 会话必须先注册进 map 再启动监控线程
                sessionMap.put(channel, session);
                activeStreamCount.incrementAndGet(); // 成功启动后 +1
                // 监控线程：等待进程结束
                session.executor.submit(() -> {
                    try {
                        log.warn("等待 FFmpeg 结束中...");
                        int exitCode = process.waitFor();
                        log.warn("FFmpeg进程退出，用户{}通道{}，退出码{}", userId, channel, exitCode);
                    } catch (Exception e) {
                        log.error("等待 FFmpeg 结束时被中断,通道{}", channel);
                    } finally {
                        stopStream(userId,channel);
                        log.warn("FFmpeg 推流结束，调用 stopStream()");
                    }
                });
                return new StartResult(true, session.videoCodec, "新通道推流已启动");
            } catch (IOException e) {
                e.printStackTrace();
                return new StartResult(false, null, "启动失败: " + e.getMessage());
            }
        }

    }

    /**
     * 推送裸流数据到 FFmpeg 标准输入
     * <p>
     * 外部调用该方法，将采集到的裸流数据通过此接口写入 FFmpeg 进程，
     * 实现实时推送。
     *
     * @param userId  用户 ID
     * @param channel 通道号
     * @param data    裸流数据字节数组
     */
    public static void pushRaw(int userId, int channel, byte[] data) {

        if (data == null || data.length < 10) {
            log.warn("推送数据为空，跳过写入");
            return;
        }
        StreamSession userSession = sessionMap.get(channel);
        if (userSession == null || !userSession.running) return;
        // 使用写入锁保证线程安全
        userSession.writeLock.lock();
        try {
            if (userSession.process.isAlive()) {
                // log.info("用户{}通道{}推送数据", userId, channel);
                userSession.ffmpegInput.write(data);
                userSession.ffmpegInput.flush();
                // userSession.lastActiveTime = System.currentTimeMillis();
            } else {
                log.info("FFmpeg 进程已结束，跳过写入操作");
            }

        } catch (IOException e) {
            e.printStackTrace();
            log.info("写入裸流失败，停止用户：{}通道{}", userId, channel);
            // 清除通道用户
            channelUserMap.remove(channel);
            stopStream2(channel); // 出错后停止推流，触发重启逻辑
        } finally {
            userSession.writeLock.unlock();
        }
    }

    /**
     * 查看哪些通道正在播放
     */
    public static Set<Integer> playingChannels() {
        return sessionMap.keySet();
    }


    /**
     * 停止某个用户某通道的推流
     * <p>
     * 关闭 FFmpeg 进程，关闭输入流，并释放资源。
     *
     * @param userId  用户 ID
     * @param channel 通道号
     */
    public static boolean stopStream(int userId, int channel) {
        synchronized(streamLock) {
            Set<Integer> users = channelUserMap.get(channel);
            if (users != null) {
                users.remove(userId);
                if (!users.isEmpty()) {
                    log.info("还有其他用户在使用通道 {}，不停止推流,用户{}", channel, users);
                    return false;
                } else {
                    return stopStream2(channel);
                }
            } else {
                return stopStream2(channel);
            }
        }
    }

    public static boolean stopStream2(Integer channel) {
        instance.stopStreamInternal(0, channel);
        // 删除会话
        StreamSession session = sessionMap.remove(channel);
        // 如果指定通道为空，则直接返回
        if (session != null) {
            // 设置会话状态为停止
            session.running = false;
            try {
                // 关闭输入流
                if (session.ffmpegInput != null) session.ffmpegInput.close();
            } catch (IOException ignored) {
                log.error("关闭输入流错误");
            }
            // 销毁 FFmpeg 进程
            if (session.process != null) session.process.destroy();
            // 关闭线程池
            if (session.executor != null) session.executor.shutdownNow();
            log.info("已停止通道推流：{}", channel);
            activeStreamCount.decrementAndGet(); // 推流结束后 -1
        }
        return true;
    }

    /**
     * 查询通道哪些用户正在使用
     *
     * @param channel 通道号
     * @return true 表示正在推流
     */
    public static Set<Integer> isRunning(int channel) {
        return channelUserMap.get(channel);
    }

    // 定时清理 15 分钟无活动会话
    private static final ScheduledExecutorService cleaner = Executors.newScheduledThreadPool(1);
    private static final long TIMEOUT_MS = 15 * 60 * 1000;

    static {
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, ISUPStreamHandler.StreamSession> entry : sessionMap.entrySet()) {
                int channel = entry.getKey();
                ISUPStreamHandler.StreamSession session = entry.getValue();
                if (now - session.lastActiveTime > TIMEOUT_MS) {
                    log.warn("isup通道 {} 空闲超时，自动停止推流", channel);
                    // 清除通道用户
                    channelUserMap.remove(channel);
                    stopStream2(channel);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }


    private void stopStreamInternal(Integer userId, Integer channel) {
        // 从映射中获取会话 ID
        Integer sessionId = SMS.LuserIDandSessionMap.get(channel);
        if (sessionId == null) return;
        int luserId = 0;
        log.info("isup关闭通道{},会话ID{}",channel,sessionId);
        // 停止 ISUP 流
        sms.StopRealPlay(luserId, channel, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
    }

}
