package com.hcbt.hcisup.SdkService.AlarmService;

import com.hcbt.hcisup.SdkService.CmsService.HCISUPCMS;
import com.hcbt.hcisup.common.osSelect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Created with IntelliJ IDEA
 *
 * @Author: Sout
 * @Date: 2024/11/20 下午4:40
 * @Description:
 */
@Slf4j
public class Alarm {
    public static HCISUPAlarm hcEHomeAlarm = null;
    public static int AlarmHandle = -1; //Alarm监听句柄
    static HCISUPAlarm.EHomeMsgCallBack cbEHomeMsgCallBack;//报警监听回调函数实现
    static HCISUPAlarm.NET_EHOME_ALARM_LISTEN_PARAM net_ehome_alarm_listen_param = new HCISUPAlarm.NET_EHOME_ALARM_LISTEN_PARAM();


    @Value("${ehome.in-ip}")
    private String ehomeInIp;

    @Value("${ehome.pu-ip}")
    private String ehomePuIp;

    @Value("${ehome.Alarm.Alarm-Tcp-port}")
    private short tcpPort;

    @Value("${ehome.Alarm.Alarm-Udp-port}")
    private short udpPort;

    @Value("${ehome.Alarm.Alarm-Server-type}")
    private short serverType;

    @Value("${ehome.Alarm.EventInfoPrintType}")
    private String EventInfoPrintType;

    /**
     * 根据不同操作系统选择不同的库文件和库路径
     *
     * @return
     */
    private static boolean CreateSDKInstance() {
        if (hcEHomeAlarm == null) {
            synchronized (HCISUPAlarm.class) {
                String strDllPath = "";
                try {
                    //System.setProperty("jna.debug_load", "true");
                    if (osSelect.isWindows())
                        //win系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir") + "\\lib\\HCISUPAlarm.dll";
                    else if (osSelect.isLinux())
                        //Linux系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir") + "/lib/libHCISUPAlarm.so";
                    hcEHomeAlarm = (HCISUPAlarm) Native.loadLibrary(strDllPath, HCISUPAlarm.class);
                } catch (Exception ex) {
                    System.out.println("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    public void eAlarm_Init() {
        if (hcEHomeAlarm == null) {
            if (!CreateSDKInstance()) {
                log.error("Load Alarm SDK fail");
                return;
            }
        }
        if (osSelect.isWindows()) {
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\lib\\libeay32.dll"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcEHomeAlarm.NET_EALARM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\lib\\ssleay32.dll";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcEHomeAlarm.NET_EALARM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());

            //报警服务初始化
            boolean bRet = hcEHomeAlarm.NET_EALARM_Init();
            if (!bRet) {
                log.error("NET_EALARM_Init failed!");
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\lib\\HCAapSDKCom";        //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcEHomeAlarm.NET_EALARM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());
        } else if (osSelect.isLinux()) {
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/lib/libcrypto.so"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcEHomeAlarm.NET_EALARM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/lib/libssl.so";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcEHomeAlarm.NET_EALARM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());
            //报警服务初始化
            boolean bRet = hcEHomeAlarm.NET_EALARM_Init();
            if (!bRet) {
                log.error("NET_EALARM_Init failed!");
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/lib/HCAapSDKCom/";        //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcEHomeAlarm.NET_EALARM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());
        }

        //监听
        startAlarmListen();
        //启用SDK写日志
        //boolean logToFile = hcEHomeAlarm.NET_EALARM_SetLogToFile(3, System.getProperty("user.dir") + "/EHomeSDKLog", false);
    }

    /**
     * 开启报警服务监听
     */
    public void startAlarmListen() {
        if (cbEHomeMsgCallBack == null) {
            cbEHomeMsgCallBack = new EHomeMsgCallBack();
        }
        System.arraycopy(ehomePuIp.getBytes(), 0, net_ehome_alarm_listen_param.struAddress.szIP, 0, ehomePuIp.length());
        if (serverType == 2) {
            net_ehome_alarm_listen_param.struAddress.wPort = Short.parseShort(String.valueOf(tcpPort));
            net_ehome_alarm_listen_param.byProtocolType = 2; //协议类型：0- TCP，1- UDP, 2-MQTT
        } else {
            net_ehome_alarm_listen_param.struAddress.wPort = Short.parseShort(String.valueOf(udpPort));
            net_ehome_alarm_listen_param.byProtocolType = 1; //协议类型：0- TCP，1- UDP, 2-MQTT
        }
        net_ehome_alarm_listen_param.fnMsgCb = cbEHomeMsgCallBack;
        net_ehome_alarm_listen_param.byUseCmsPort = 0; //是否复用CMS端口：0- 不复用，非0- 复用
        net_ehome_alarm_listen_param.write();

        //启动报警服务器监听
        AlarmHandle = hcEHomeAlarm.NET_EALARM_StartListen(net_ehome_alarm_listen_param);
        if (AlarmHandle < 0) {
            log.error("NET_EALARM_StartListen failed, error:" + hcEHomeAlarm.NET_EALARM_GetLastError());
            hcEHomeAlarm.NET_EALARM_Fini();
            return;
        } else {
            String AlarmListenInfo = new String(net_ehome_alarm_listen_param.struAddress.szIP).trim() + "_" + net_ehome_alarm_listen_param.struAddress.wPort;
            log.info("报警服务器：" + AlarmListenInfo + ",NET_EALARM_StartListen succeed");
        }
    }

    /**
     * 报警回调函数实现，设备上传事件通过回调函数上传进行解析
     */
    class EHomeMsgCallBack implements HCISUPAlarm.EHomeMsgCallBack {
        @Override
        public boolean invoke(int iHandle, HCISUPAlarm.NET_EHOME_ALARM_MSG pAlarmMsg, Pointer pUser) {
            if ("console".equals(EventInfoPrintType)) {
                // 输出事件信息到控制台上
                System.out.println("AlarmType: " + pAlarmMsg.dwAlarmType + ",dwAlarmInfoLen:" + pAlarmMsg.dwAlarmInfoLen + ",dwXmlBufLen:" + pAlarmMsg.dwXmlBufLen + "\n");
            }
            return true;
        }
    }
}
