package com.hcbt.hcisup.SdkService.StreamService;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.CmsService.HCISUPCMS;
import com.hcbt.hcisup.common.HandleStreamV2;
import com.hcbt.hcisup.common.PlayBackStream;
import com.hcbt.hcisup.common.osSelect;
import com.hcbt.hcisup.utils.lUserIdAndDeviceMap;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ws.schild.jave.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 流媒体服务（SMS）类
 * 负责管理流媒体服务的初始化、预览、回放等功能。
 */
@Slf4j
@Component
public class SMS {

    public static HCISUPSMS hcISUPSMS = null;

    // 存储 sessionID 和 HandleStreamV2 的映射
    public static Map<Integer, HandleStreamV2> concurrentMap = new HashMap<>();
    // 存储 sessionID 和 PlayBackStream 的映射
    public static Map<Integer, PlayBackStream> PlayBackconcurrentMap = new HashMap<>();

    // 预览相关映射
    public static Map<Integer, Integer> PreviewHandSAndSessionIDandMap = new HashMap<>(); // lLinkHandle -> SessionID
    public static Map<Integer, Integer> SessionIDAndPreviewHandleMap = new HashMap<>();  // SessionID -> lLinkHandle
    public static Map<Integer, Integer> LuserIDandSessionMap = new HashMap<>();          // lUserID -> SessionID

    // 回放相关映射
    public static Map<Integer, Integer> BackLuserIDandSessionMap = new HashMap<>();
    public static Map<Integer, Integer> BackPreviewHandSAndSessionIDandMap = new HashMap<>();
    public static Map<Integer, Integer> BackSessionIDAndPreviewHandleMap = new HashMap<>();

    // 回调函数
    static FPREVIEW_NEWLINK_CB fPREVIEW_NEWLINK_CB; // 预览监听回调
    static FPREVIEW_DATA_CB_WIN fPREVIEW_DATA_CB_WIN; // 预览数据回调
    static PLAYBACK_NEWLINK_CB_FILE fPLAYBACK_NEWLINK_CB_FILE; // 回放监听回调
    static PLAYBACK_DATA_CB_FILE fPLAYBACK_DATA_CB_FILE; // 回放数据回调

    // 监听配置
    HCISUPSMS.NET_EHOME_LISTEN_PREVIEW_CFG struPreviewListen = new HCISUPSMS.NET_EHOME_LISTEN_PREVIEW_CFG();
    HCISUPSMS.NET_EHOME_PLAYBACK_LISTEN_PARAM struPlayBackListen = new HCISUPSMS.NET_EHOME_PLAYBACK_LISTEN_PARAM();

    @Value("${ehome.in-ip}")
    private String ehomeInIp;

    @Value("${ehome.pu-ip}")
    private String ehomePuIp;

    @Value("${ehome.sms-preview-port}")
    private short ehomeSmsPreViewPort;

    @Value("${ehome.sms-back-port}")
    private short ehomeSmsBackPort;

    @Value("${ehome.playBack-videoPath}")
    private String fileVideoPath;

    /**
     * 实例化 HCISUPSMS 对象
     *
     * @return 是否成功
     */
    private static boolean createSDKInstance() {
        if (hcISUPSMS == null) {
            synchronized (HCISUPSMS.class) {
                String strDllPath = "";
                try {
                    if (osSelect.isWindows()) {
                        strDllPath = System.getProperty("user.dir") + "\\lib\\HCISUPStream.dll";
                    } else if (osSelect.isLinux()) {
                        strDllPath = System.getProperty("user.dir") + "/lib/libHCISUPStream.so";
                    }
                    hcISUPSMS = (HCISUPSMS) Native.loadLibrary(strDllPath, HCISUPSMS.class);
                } catch (Exception ex) {
                    log.error("加载库失败: " + strDllPath + " 错误: " + ex.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 初始化 SMS 服务
     */
    @PostConstruct
    public void init() {
        if (hcISUPSMS == null) {
            if (!createSDKInstance()) {
                log.error("加载 SMS SDK 失败");
                return;
            }
        }

        // 根据系统加载对应的库
        if (osSelect.isWindows()) {
            configureWindowsLibraries();
        } else if (osSelect.isLinux()) {
            configureLinuxLibraries();
        }

        // 流媒体初始化
        if (hcISUPSMS.NET_ESTREAM_Init()) {
            log.info("SMS 流媒体初始化成功!");
            startPreviewListen();
            startPlayBackListen();
        } else {
            log.error("SMS 流媒体初始化失败! 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }

        // 设置 HCAapSDKCom 组件库路径
        setHCAapSDKComPath();
    }

    /**
     * 配置 Windows 平台的库
     */
    private void configureWindowsLibraries() {
        // 设置 libeay32.dll 路径
        HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
        String strPathCrypto = System.getProperty("user.dir") + "\\lib\\libeay32.dll";
        System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
        ptrByteArrayCrypto.write();
        if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
            log.error("NET_ESTREAM_SetSDKInitCfg 0 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }

        // 设置 ssleay32.dll 路径
        HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
        String strPathSsl = System.getProperty("user.dir") + "\\lib\\ssleay32.dll";
        System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
        ptrByteArraySsl.write();
        if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
            log.error("NET_ESTREAM_SetSDKInitCfg 1 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }
    }

    /**
     * 配置 Linux 平台的库
     */
    private void configureLinuxLibraries() {
        // 设置 libcrypto.so 路径
        HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
        String strPathCrypto = System.getProperty("user.dir") + "/lib/libcrypto.so";
        System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
        ptrByteArrayCrypto.write();
        if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
            log.error("NET_ESTREAM_SetSDKInitCfg 0 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }

        // 设置 libssl.so 路径
        HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
        String strPathSsl = System.getProperty("user.dir") + "/lib/libssl.so";
        System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
        ptrByteArraySsl.write();
        if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
            log.error("NET_ESTREAM_SetSDKInitCfg 1 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }
    }

    /**
     * 设置 HCAapSDKCom 组件库路径
     */
    private void setHCAapSDKComPath() {
        HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
        String strPathCom = System.getProperty("user.dir") + (osSelect.isWindows() ? "\\lib\\HCAapSDKCom" : "/lib/HCAapSDKCom/");
        System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
        ptrByteArrayCom.write();
        if (!hcISUPSMS.NET_ESTREAM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer())) {
            log.error("NET_ESTREAM_SetSDKLocalCfg 5 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
        }
    }

    /**
     * 开启实时预览监听
     */
    private void startPreviewListen() {
        if (fPREVIEW_NEWLINK_CB == null) {
            fPREVIEW_NEWLINK_CB = new FPREVIEW_NEWLINK_CB();
        }
        struPreviewListen.struIPAdress.szIP = ehomeInIp.getBytes();
        struPreviewListen.struIPAdress.wPort = ehomeSmsPreViewPort; // 流媒体服务器监听端口
        struPreviewListen.fnNewLinkCB = fPREVIEW_NEWLINK_CB; // 预览连接请求回调
        struPreviewListen.pUser = null;
        struPreviewListen.byLinkMode = 0; // 0- TCP 方式，1- UDP 方式
        struPreviewListen.write();

        int SmsHandle = hcISUPSMS.NET_ESTREAM_StartListenPreview(struPreviewListen);
        if (SmsHandle < 0) {
            log.error("SMS 流媒体服务监听失败, 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
            hcISUPSMS.NET_ESTREAM_Fini();
        } else {
            String StreamListenInfo = new String(struPreviewListen.struIPAdress.szIP).trim() + ":" + struPreviewListen.struIPAdress.wPort;
            log.info("SMS 流媒体服务: " + StreamListenInfo + " 监听成功!");
        }
    }

    /**
     * 开启回放监听
     */
    private void startPlayBackListen() {
        if (fPLAYBACK_NEWLINK_CB_FILE == null) {
            fPLAYBACK_NEWLINK_CB_FILE = new PLAYBACK_NEWLINK_CB_FILE();
        }
        struPlayBackListen.struIPAdress.szIP = ehomeInIp.getBytes(); // SMS 服务器 IP
        struPlayBackListen.struIPAdress.wPort = ehomeSmsBackPort; // SMS 服务器监听端口
        struPlayBackListen.pUser = null;
        struPlayBackListen.fnNewLinkCB = fPLAYBACK_NEWLINK_CB_FILE;
        struPlayBackListen.byLinkMode = 0; // 0- TCP 方式，1- UDP 方式
        struPlayBackListen.write();

        int m_lPlayBackListenHandle = hcISUPSMS.NET_ESTREAM_StartListenPlayBack(struPlayBackListen);
        if (m_lPlayBackListenHandle < 0) {
            log.error("NET_ESTREAM_StartListenPlayBack 失败, 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
            hcISUPSMS.NET_ESTREAM_Fini();
        } else {
            String BackStreamListenInfo = new String(struPlayBackListen.struIPAdress.szIP).trim() + ":" + struPlayBackListen.struIPAdress.wPort;
            log.info("回放流媒体服务: " + BackStreamListenInfo + ", NET_ESTREAM_StartListenPlayBack 成功");
        }
    }

    /**
     * 实时预览监听回调
     */
    public class FPREVIEW_NEWLINK_CB implements HCISUPSMS.PREVIEW_NEWLINK_CB {
        @Override
        public boolean invoke(int lLinkHandle, HCISUPSMS.NET_EHOME_NEWLINK_CB_MSG pNewLinkCBMsg, Pointer pUserData) {
            HCISUPSMS.NET_EHOME_PREVIEW_DATA_CB_PARAM struDataCB = new HCISUPSMS.NET_EHOME_PREVIEW_DATA_CB_PARAM();
            PreviewHandSAndSessionIDandMap.put(lLinkHandle, pNewLinkCBMsg.iSessionID);
            SessionIDAndPreviewHandleMap.put(pNewLinkCBMsg.iSessionID, lLinkHandle);

            if (fPREVIEW_DATA_CB_WIN == null) {
                fPREVIEW_DATA_CB_WIN = new FPREVIEW_DATA_CB_WIN();
            }
            struDataCB.fnPreviewDataCB = fPREVIEW_DATA_CB_WIN;
            if (!hcISUPSMS.NET_ESTREAM_SetPreviewDataCB(lLinkHandle, struDataCB)) {
                log.error("NET_ESTREAM_SetPreviewDataCB 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
                return false;
            }
            return true;
        }
    }

    /**
     * 预览数据回调
     */
    public class FPREVIEW_DATA_CB_WIN implements HCISUPSMS.PREVIEW_DATA_CB {
        @Override
        public void invoke(int iPreviewHandle, HCISUPSMS.NET_EHOME_PREVIEW_CB_MSG pPreviewCBMsg, Pointer pUserData) throws IOException {
            switch (pPreviewCBMsg.byDataType) {
                case HCNetSDK.NET_DVR_SYSHEAD: // 系统头
                    break;
                case HCNetSDK.NET_DVR_STREAMDATA: // 码流数据
                    byte[] dataStream = pPreviewCBMsg.pRecvdata.getByteArray(0, pPreviewCBMsg.dwDataLen);
                    if (dataStream != null) {
                        Integer sessionID = PreviewHandSAndSessionIDandMap.get(iPreviewHandle);
                        HandleStreamV2 handleStreamV2 = concurrentMap.get(sessionID);
                        if (handleStreamV2 != null) {
                            handleStreamV2.startProcessing(dataStream);
                        }
                    }
                    break;
            }
        }
    }

    /**
     * 回放监听回调
     */
    public class PLAYBACK_NEWLINK_CB_FILE implements HCISUPSMS.PLAYBACK_NEWLINK_CB {
        @Override
        public boolean invoke(int lPlayBackLinkHandle, HCISUPSMS.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO pNewLinkCBMsg, Pointer pUserData) {
            pNewLinkCBMsg.read();
            HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_PARAM struDataCB = new HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_PARAM();
            BackPreviewHandSAndSessionIDandMap.put(lPlayBackLinkHandle, pNewLinkCBMsg.lSessionID);
            BackSessionIDAndPreviewHandleMap.put(pNewLinkCBMsg.lSessionID, lPlayBackLinkHandle);

            if (fPLAYBACK_DATA_CB_FILE == null) {
                fPLAYBACK_DATA_CB_FILE = new PLAYBACK_DATA_CB_FILE();
            }
            struDataCB.fnPlayBackDataCB = fPLAYBACK_DATA_CB_FILE;
            struDataCB.byStreamFormat = 0;
            struDataCB.write();
            if (!hcISUPSMS.NET_ESTREAM_SetPlayBackDataCB(lPlayBackLinkHandle, struDataCB)) {
                log.error("NET_ESTREAM_SetPlayBackDataCB 失败, 错误: " + hcISUPSMS.NET_ESTREAM_GetLastError());
                return false;
            }
            return true;
        }
    }

    /**
     * 回放数据回调
     */
    public class PLAYBACK_DATA_CB_FILE implements HCISUPSMS.PLAYBACK_DATA_CB {
        byte[] bytes1 = new byte[1024 * 1024];

        @Override
        public boolean invoke(int iPlayBackLinkHandle, HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_INFO pDataCBInfo, Pointer pUserData) {
            Integer sessionID = BackPreviewHandSAndSessionIDandMap.get(iPlayBackLinkHandle);
            PlayBackStream playBackStream = PlayBackconcurrentMap.get(sessionID);
            if (pDataCBInfo.pData != null && playBackStream != null) {
                String date = playBackStream.getStartTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String filePath = fileVideoPath + playBackStream.getDeviceId() + "/" + date + "/" + playBackStream.getFileName() + ".mp4";
                try {
                    File file = new File(fileVideoPath + playBackStream.getDeviceId() + "/" + date);
                    if (!file.exists()) {
                        file.mkdirs();
                    }
                    try (FileOutputStream playbackFileOutput = new FileOutputStream(filePath, true)) {
                        long offset = 0;
                        ByteBuffer buffers = pDataCBInfo.pData.getByteBuffer(offset, pDataCBInfo.dwDataLen);
                        byte[] bytes = new byte[pDataCBInfo.dwDataLen];
                        buffers.rewind();
                        buffers.get(bytes);
                        playbackFileOutput.write(bytes);
                    }
                } catch (IOException e) {
                    log.error("文件写入失败: " + e.getMessage());
                }
            } else {
                if (playBackStream != null) {
                    String date = playBackStream.getStartTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                    String filePath = fileVideoPath + playBackStream.getDeviceId() + "/" + date + "/" + playBackStream.getFileName() + ".mp4";
                    String filePath2 = fileVideoPath + playBackStream.getDeviceId() + "/" + date + "/" + playBackStream.getFileName() + "_0.mp4";
                    try {
                        MP4Covert(filePath, filePath2);
                        playBackStream.getCompletableFuture().complete("true");
                        PlayBackconcurrentMap.remove(sessionID);
                    } catch (EncoderException e) {
                        log.error("MP4 转换失败: " + e.getMessage());
                    }
                }
            }
            return true;
        }
    }

    /**
     * 开启预览
     *
     * @param luserID               用户 ID
     * @param channel               通道号
     * @param completableFutureOne  异步完成标志
     */
    public void RealPlay(int luserID, int channel, CompletableFuture<String> completableFutureOne) {
        if (LuserIDandSessionMap.containsKey(luserID)) {
            log.error("禁止重复推流!");
            completableFutureOne.complete("false");
            return;
        }

        HCISUPCMS.NET_EHOME_PREVIEWINFO_IN struPreviewIn = new HCISUPCMS.NET_EHOME_PREVIEWINFO_IN();
        struPreviewIn.iChannel = channel;
        struPreviewIn.dwLinkMode = 0; // 0- TCP, 1- UDP
        struPreviewIn.dwStreamType = 0; // 0- 主码流, 1- 子码流, 2- 第三码流
        struPreviewIn.struStreamSever.szIP = ehomePuIp.getBytes();
        struPreviewIn.struStreamSever.wPort = ehomeSmsPreViewPort;
        struPreviewIn.write();

        HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT struPreviewOut = new HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT();
        if (!CMS.hcISUPCMS.NET_ECMS_StartGetRealStream(luserID, struPreviewIn, struPreviewOut)) {
            log.error("请求开始预览失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
            return;
        }
        struPreviewOut.read();

        HCISUPCMS.NET_EHOME_PUSHSTREAM_IN struPushInfoIn = new HCISUPCMS.NET_EHOME_PUSHSTREAM_IN();
        struPushInfoIn.read();
        struPushInfoIn.dwSize = struPushInfoIn.size();
        struPushInfoIn.lSessionID = struPreviewOut.lSessionID;
        struPushInfoIn.write();

        HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT struPushInfoOut = new HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT();
        struPushInfoOut.read();
        struPushInfoOut.dwSize = struPushInfoOut.size();
        struPushInfoOut.write();

        if (!CMS.hcISUPCMS.NET_ECMS_StartPushRealStream(luserID, struPushInfoIn, struPushInfoOut)) {
            log.error("CMS 向设备发送请求预览实时码流失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
        } else {
            log.info("CMS 向设备发送请求预览实时码流成功, sessionID: " + struPushInfoIn.lSessionID);
            completableFutureOne.complete("true");
            LuserIDandSessionMap.put(luserID, struPushInfoIn.lSessionID);
            concurrentMap.put(struPushInfoIn.lSessionID, new HandleStreamV2(luserID));
        }
    }

    /**
     * 开启预览（使用 FFmpeg）
     *
     * @param luserID               用户 ID
     * @param channel               通道号
     * @param completableFutureOne  异步完成标志
     */
    public void RealPlayWithFFmpeg(int luserID, int channel, CompletableFuture<String> completableFutureOne) {
        if (LuserIDandSessionMap.containsKey(luserID)) {
            log.error("禁止重复推流!");
            completableFutureOne.complete("false");
            return;
        }

        HCISUPCMS.NET_EHOME_PREVIEWINFO_IN struPreviewIn = new HCISUPCMS.NET_EHOME_PREVIEWINFO_IN();
        struPreviewIn.iChannel = channel;
        struPreviewIn.dwLinkMode = 0;
        struPreviewIn.dwStreamType = 0;
        struPreviewIn.struStreamSever.szIP = ehomePuIp.getBytes();
        struPreviewIn.struStreamSever.wPort = ehomeSmsPreViewPort;
        struPreviewIn.write();

        HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT struPreviewOut = new HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT();
        if (!CMS.hcISUPCMS.NET_ECMS_StartGetRealStream(luserID, struPreviewIn, struPreviewOut)) {
            log.error("请求开始预览失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
            return;
        }
        struPreviewOut.read();

        HCISUPCMS.NET_EHOME_PUSHSTREAM_IN struPushInfoIn = new HCISUPCMS.NET_EHOME_PUSHSTREAM_IN();
        struPushInfoIn.read();
        struPushInfoIn.dwSize = struPushInfoIn.size();
        struPushInfoIn.lSessionID = struPreviewOut.lSessionID;
        struPushInfoIn.write();

        HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT struPushInfoOut = new HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT();
        struPushInfoOut.read();
        struPushInfoOut.dwSize = struPushInfoOut.size();
        struPushInfoOut.write();

        if (!CMS.hcISUPCMS.NET_ECMS_StartPushRealStream(luserID, struPushInfoIn, struPushInfoOut)) {
            log.error("CMS 向设备发送请求预览实时码流失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
        } else {
            log.info("CMS 向设备发送请求预览实时码流成功, sessionID: " + struPushInfoIn.lSessionID);
            HandleStreamV2 handler = new HandleStreamV2(luserID);
            concurrentMap.put(struPushInfoIn.lSessionID, handler);
            LuserIDandSessionMap.put(luserID, struPushInfoIn.lSessionID);
            completableFutureOne.complete("true");
        }
    }

    /**
     * 停止预览
     *
     * @param luserID        用户 ID
     * @param sessionID      会话 ID
     * @Devices lPreviewHandle 预览句柄
     */
    public void StopRealPlay(int luserID, int sessionID, int lPreviewHandle) {
        if (!LuserIDandSessionMap.containsKey(luserID)) {
            log.error("禁止重复停止推流!");
            return;
        }

        if (!hcISUPSMS.NET_ESTREAM_StopPreview(lPreviewHandle)) {
            log.error("停止转发预览实时码流失败, 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
            return;
        }

        if (!CMS.hcISUPCMS.NET_ECMS_StopGetRealStream(luserID, sessionID)) {
            log.error("请求停止预览失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            return;
        }

        concurrentMap.remove(sessionID);
        PreviewHandSAndSessionIDandMap.remove(lPreviewHandle);
        LuserIDandSessionMap.remove(luserID);
        SessionIDAndPreviewHandleMap.remove(sessionID);

        log.info("会话 " + sessionID + " 相关资源已被清空");
    }

    /**
     * 开启回放（按文件名）
     *
     * @param lUserId             用户 ID
     * @param fileName            文件名
     * @param startTime           开始时间
     * @param endTime             结束时间
     * @param completableFuture   异步完成标志
     */
    public void startPlayBackByFileName(int lUserId, String fileName, LocalDateTime startTime, LocalDateTime endTime, CompletableFuture<String> completableFuture) {
        String deviceId = lUserIdAndDeviceMap.get(String.valueOf(lUserId));
        String date = startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String filePath = fileVideoPath + deviceId + "/" + date + "/" + fileName + "_0.mp4";

        if (new File(filePath).exists()) {
            completableFuture.complete("true");
            return;
        }

        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN m_struPlayBackInfoIn = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN();
        m_struPlayBackInfoIn.read();
        m_struPlayBackInfoIn.dwSize = m_struPlayBackInfoIn.size();
        m_struPlayBackInfoIn.dwChannel = 1;
        m_struPlayBackInfoIn.byPlayBackMode = 1; // 按时间回放
        m_struPlayBackInfoIn.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        setPlayBackTime(m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime, startTime, endTime);
        m_struPlayBackInfoIn.struStreamSever.szIP = ehomePuIp.getBytes();
        m_struPlayBackInfoIn.struStreamSever.wPort = ehomeSmsBackPort;
        m_struPlayBackInfoIn.write();

        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT m_struPlayBackInfoOut = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT();
        m_struPlayBackInfoOut.write();

        if (!CMS.hcISUPCMS.NET_ECMS_StartPlayBack(lUserId, m_struPlayBackInfoIn, m_struPlayBackInfoOut)) {
            log.error("NET_ECMS_StartPlayBack 失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFuture.complete("false");
            return;
        }
        m_struPlayBackInfoOut.read();
        log.info("NET_ECMS_StartPlayBack 成功, lSessionID: " + m_struPlayBackInfoOut.lSessionID);
        PlayBackconcurrentMap.put(m_struPlayBackInfoOut.lSessionID, new PlayBackStream(deviceId, fileName, startTime, completableFuture));

        HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN m_struPushPlayBackIn = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN();
        m_struPushPlayBackIn.read();
        m_struPushPlayBackIn.dwSize = m_struPushPlayBackIn.size();
        m_struPushPlayBackIn.lSessionID = m_struPlayBackInfoOut.lSessionID;
        m_struPushPlayBackIn.write();
        BackLuserIDandSessionMap.put(lUserId, m_struPushPlayBackIn.lSessionID);

        HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT m_struPushPlayBackOut = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT();
        m_struPushPlayBackOut.read();
        m_struPushPlayBackOut.dwSize = m_struPushPlayBackOut.size();
        m_struPushPlayBackOut.write();

        if (!CMS.hcISUPCMS.NET_ECMS_StartPushPlayBack(lUserId, m_struPushPlayBackIn, m_struPushPlayBackOut)) {
            log.error("NET_ECMS_StartPushPlayBack 失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFuture.complete("false");
        } else {
            log.info("NET_ECMS_StartPushPlayBack 成功, sessionID: " + m_struPushPlayBackIn.lSessionID + ", lUserID: " + lUserId);
        }
    }

    /**
     * 设置回放时间
     *
     * @param struPlayBackbyTime 回放时间结构
     * @param startTime          开始时间
     * @param endTime            结束时间
     */
    private void setPlayBackTime(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME struPlayBackbyTime, LocalDateTime startTime, LocalDateTime endTime) {
        struPlayBackbyTime.struStartTime.wYear = (short) startTime.getYear();
        struPlayBackbyTime.struStartTime.byMonth = (byte) startTime.getMonthValue();
        struPlayBackbyTime.struStartTime.byDay = (byte) startTime.getDayOfMonth();
        struPlayBackbyTime.struStartTime.byHour = (byte) startTime.getHour();
        struPlayBackbyTime.struStartTime.byMinute = (byte) startTime.getMinute();
        struPlayBackbyTime.struStartTime.bySecond = (byte) startTime.getSecond();

        struPlayBackbyTime.struStopTime.wYear = (short) endTime.getYear();
        struPlayBackbyTime.struStopTime.byMonth = (byte) endTime.getMonthValue();
        struPlayBackbyTime.struStopTime.byDay = (byte) endTime.getDayOfMonth();
        struPlayBackbyTime.struStopTime.byHour = (byte) endTime.getHour();
        struPlayBackbyTime.struStopTime.byMinute = (byte) endTime.getMinute();
        struPlayBackbyTime.struStopTime.bySecond = (byte) endTime.getSecond();
    }

    /**
     * 停止回放
     *
     * @param lUserID        用户 ID
     * @param sessionID      会话 ID
     * @param lPreviewHandle 预览句柄
     * @return 是否成功
     */
    public boolean stopPlayBackByFileName(int lUserID, int sessionID, int lPreviewHandle) {
        if (!CMS.hcISUPCMS.NET_ECMS_StopPlayBack(lUserID, sessionID)) {
            log.error("NET_ECMS_StopPlayBack 失败, 错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            return false;
        }
        log.info("CMS 发送回放停止请求");

        if (!hcISUPSMS.NET_ESTREAM_StopPlayBack(lPreviewHandle)) {
            log.error("NET_ESTREAM_StopPlayBack 失败, 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
            return false;
        }

        PlayBackconcurrentMap.remove(sessionID);
        BackPreviewHandSAndSessionIDandMap.remove(lPreviewHandle);
        BackLuserIDandSessionMap.remove(lUserID);
        BackSessionIDAndPreviewHandleMap.remove(sessionID);

        log.info("回放会话 " + sessionID + " 相关资源已被清空");
        return true;
    }

    /**
     * MP4 格式转换
     *
     * @param filepath  源文件路径
     * @param filepath1 目标文件路径
     * @throws EncoderException 编码异常
     */
    public static void MP4Covert(String filepath, String filepath1) throws EncoderException {
        File source = new File(filepath);
        File target = new File(filepath1);

        AudioAttributes audio = new AudioAttributes();
        audio.setCodec("aac");
        audio.setBitRate(128000);
        audio.setChannels(2);
        audio.setSamplingRate(44100);

        VideoAttributes video = new VideoAttributes();
        video.setCodec("h264");
        video.setX264Profile(VideoAttributes.X264_PROFILE.BASELINE);
        video.setBitRate(1024 * 1024 * 4);
        video.setFrameRate(25);

        EncodingAttributes attrs = new EncodingAttributes();
        attrs.setFormat("mp4");
        attrs.setAudioAttributes(audio);
        attrs.setVideoAttributes(video);

        Encoder encoder = new Encoder();
        encoder.encode(new MultimediaObject(source), target, attrs);

        // 删除源文件
        source.delete();
    }
}