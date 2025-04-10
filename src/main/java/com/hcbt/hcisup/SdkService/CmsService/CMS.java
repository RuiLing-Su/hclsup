package com.hcbt.hcisup.SdkService.CmsService;

import com.alibaba.fastjson.JSONObject;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.osSelect;
import com.hcbt.hcisup.utils.HttpUtil;
import com.hcbt.hcisup.utils.lUserIdAndDeviceMap;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.buf.Utf8Encoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class CMS {

    public static HCISUPCMS hcISUPCMS = null;
    public static int CmsHandle = -1; //CMS监听句柄

    static FRegisterCallBack fRegisterCallBack;//注册回调函数实现

    HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM struCMSListenParam = new HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM();

    //录制视频lUserIDList
    static ArrayList<Integer> lUserIDList = new ArrayList<>();

    @Value("${ehome.pu-ip}")
    private String ehomePuIp;

    @Value("${ehome.in-ip}")
    private String ehomeInIp;

    @Value("${ehome.cms-port}")
    private short ehomeCmsPort;

//    @Value("${ehome.ams-prot}")
//    private short ehomeAmsProt;

    @Value("${ehome.secret-key}")
    private String secretKey;


    @Value("${ehome.ss-filepath}")
    private String ss_filepath;

    //用于存储lUserID的map
    private final Map<String,Integer> LUserIDMap = new HashMap<>();

    /**
     * 实例化 hcISUPCMS 对象
     *
     * @return
     */
    private static boolean CreateSDKInstance() {
        if (hcISUPCMS == null) {
            synchronized (HCISUPCMS.class) {
                String strDllPath = "";
                try {
                    //System.setProperty("jna.debug_load", "true");
                    if (osSelect.isWindows())
                        //win系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir") + "\\lib\\HCISUPCMS.dll";
                    else if (osSelect.isLinux())
                        //Linux系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir")+"/lib/libHCISUPCMS.so";
                    hcISUPCMS = (HCISUPCMS) Native.loadLibrary(strDllPath, HCISUPCMS.class);
                } catch (Exception ex) {
                    log.error("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 初始化CMS注册中心
     * @throws IOException
     */
    @PostConstruct
    public void CMS_Init() throws IOException {

        if (hcISUPCMS == null) {
            if (!CreateSDKInstance()) {
                log.error("加载CMS SDK 失败");
                return;
            }
        }
        //根据系统加载对应的库
        if (osSelect.isWindows()) {
            //设置openSSL库的路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\lib\\libeay32.dll"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcISUPCMS.NET_ECMS_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\lib\\ssleay32.dll";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcISUPCMS.NET_ECMS_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());

            //注册服务初始化
            boolean binit = hcISUPCMS.NET_ECMS_Init();
            if(binit){
                log.info("CMS 注册中心初始化成功!");
                CMS_StartListen();
            }else {
                log.error("CMS 注册中心初始化失败! 错误码:"+hcISUPCMS.NET_ECMS_GetLastError());
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\lib\\HCAapSDKCom";        //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcISUPCMS.NET_ECMS_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());

        }
        else if (osSelect.isLinux()) {
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/lib/libcrypto.so"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcISUPCMS.NET_ECMS_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/lib/libssl.so";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcISUPCMS.NET_ECMS_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());
            //注册服务初始化
            boolean binit = hcISUPCMS.NET_ECMS_Init();
            if(binit){
                log.info("CMS 注册中心初始化成功!");
                CMS_StartListen();
            }else {
                log.error("CMS 注册中心初始化失败! 错误码:"+hcISUPCMS.NET_ECMS_GetLastError());
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/lib/HCAapSDKCom/";        //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcISUPCMS.NET_ECMS_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());
        }

    }

    /**
     * 开启CMS监听 以接收设备注册信息
     */
    public void CMS_StartListen()
    {
        //实例化注册回调函数，便于处理设备事件
        if (fRegisterCallBack == null) {
            fRegisterCallBack = new FRegisterCallBack();
        }
        //设置CMS监听参数
        struCMSListenParam.struAddress.szIP=ehomeInIp.getBytes();
        struCMSListenParam.struAddress.wPort = ehomeCmsPort;
        struCMSListenParam.fnCB = fRegisterCallBack;
        struCMSListenParam.write();

        //启动监听，接收设备注册信息
        CmsHandle = hcISUPCMS.NET_ECMS_StartListen(struCMSListenParam);
        if (CmsHandle < 0) {
            log.error("CMS注册中心监听失败, 错误码:" + hcISUPCMS.NET_ECMS_GetLastError());
            hcISUPCMS.NET_ECMS_Fini();
            return;
        }
        String CmsListenInfo = new String(struCMSListenParam.struAddress.szIP).trim() + "_" + struCMSListenParam.struAddress.wPort;
        log.info("CMS注册服务器:" + CmsListenInfo + "监听成功!");

    }

    //当有设备注册后 回调的函数
    public class FRegisterCallBack implements HCISUPCMS.DEVICE_REGISTER_CB {
        public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer, int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser) {

            log.info("【CMS中心】 注册回调 ,dwDataType:" + dwDataType + ", lUserID:" + lUserID);

            switch (dwDataType) {
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_ON:  //设备上线
                    HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 strDevRegInfo = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
                    strDevRegInfo.write();
                    Pointer pDevRegInfo = strDevRegInfo.getPointer();
                    pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                    strDevRegInfo.read();

                    log.info("【CMS中心】 设备上线==========>,DeviceID:"+ new String(strDevRegInfo.struRegInfo.byDeviceID).trim());

                    //记录到map
                    lUserIdAndDeviceMap.put(String.valueOf(lUserID),new String(strDevRegInfo.struRegInfo.byDeviceID).trim());

                    // FIXME demo逻辑中默认只支持一台设备的功能演示，多台设备需要自行调整这里设备登录后的句柄信息
//                    IsupTest.lLoginID = lUserID;
                    LUserIDMap.put(new String(strDevRegInfo.struRegInfo.byDeviceID).trim(),lUserID);
                    return true;
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_AUTH: //ENUM_DEV_AUTH
                    strDevRegInfo = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
                    strDevRegInfo.write();
                    pDevRegInfo = strDevRegInfo.getPointer();
                    pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                    strDevRegInfo.read();
                    byte[] bs = new byte[0];
                    String szEHomeKey = secretKey; //ISUP5.0登录校验值
                    bs = szEHomeKey.getBytes();
                    pInBuffer.write(0, bs, 0, szEHomeKey.length());
                    break;
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY: //Ehome5.0设备Sessionkey回调
                    strDevRegInfo = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
                    strDevRegInfo.write();
                    pDevRegInfo = strDevRegInfo.getPointer();
                    pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                    strDevRegInfo.read();
                    HCISUPCMS.NET_EHOME_DEV_SESSIONKEY struSessionKey = new HCISUPCMS.NET_EHOME_DEV_SESSIONKEY();
                    System.arraycopy(strDevRegInfo.struRegInfo.byDeviceID, 0, struSessionKey.sDeviceID, 0, strDevRegInfo.struRegInfo.byDeviceID.length);
                    System.arraycopy(strDevRegInfo.struRegInfo.bySessionKey, 0, struSessionKey.sSessionKey, 0, strDevRegInfo.struRegInfo.bySessionKey.length);
                    struSessionKey.write();
                    Pointer pSessionKey = struSessionKey.getPointer();
                    hcISUPCMS.NET_ECMS_SetDeviceSessionKey(pSessionKey);
//                    AlarmDemo.hcEHomeAlarm.NET_EALARM_SetDeviceSessionKey(pSessionKey);
                    break;
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REQ: //Ehome5.0设备重定向请求回调
                    String dasInfo = "{\n" +
                            "    \"Type\":\"DAS\",\n" +
                            "    \"DasInfo\": {\n" +
                            "        \"Address\":\"" + ehomePuIp + "\",\n" +
                            "        \"Domain\":\"\",\n" +
                            "        \"ServerID\":\"\",\n" +
                            "        \"Port\":" + ehomeCmsPort + ",\n" +
                            "        \"UdpPort\":\n" +
                            "    }\n" +
                            "}";
                    byte[] bs1 = dasInfo.getBytes();
                    pInBuffer.write(0, bs1, 0, dasInfo.length());
                    break;
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_OFF:
                    log.info("【CMS中心】 {}退出登录",lUserID);
                    outLogin(lUserID);
                    break;
                default:
                    log.info("【CMS中心】 回调类型为:"+dwDataType);
                    break;
            }
            return true;
        }
    }

    /*
    * 设退出登录
    * */
    public void outLogin(int luserID){
        //移除map
        String deviceID = lUserIdAndDeviceMap.get(String.valueOf(luserID));
        LUserIDMap.remove(deviceID);
        lUserIdAndDeviceMap.delete(String.valueOf(luserID));
        //删除sms预览判断信息
        SMS.LuserIDandSessionMap.remove(luserID);
    }

    //deviceID获取luserID
    public Map<String,Integer> getLUserId(String deviceID) {
        Map<String,Integer> data = new HashMap<>();
        if (LUserIDMap.get(deviceID) == null){
            return null;
        }
        Integer lUserID = LUserIDMap.get(deviceID);
        if(lUserID != null) {
            data.put("lUserID",lUserID);
            return data;
        }
        return null;
    }


    /**
     * @MonthName： setPTZControlOther
     * @Description： 云台控制操作(不用启动图象预览)
     * @Param：
     * lChannel：通道号
     * dwPTZCommand：云台控制命令【21、上仰，22、下俯，23、左转，24、右转】
     * dwStop：云台停止动作或开始动作：0－开始，1－停止
     * dwSpeed：云台控制的速度，用户按不同解码器的速度控制值设置。取值范围[1,7]
     * @return： void
     **/
    public boolean setPTZControlOther(int luserID,int dwPTZCommand,int speed) {

        HCISUPCMS.NET_EHOME_REMOTE_CTRL_PARAM net_ehome_remote_ctrl_param = new HCISUPCMS.NET_EHOME_REMOTE_CTRL_PARAM();
        HCISUPCMS.NET_EHOME_PTZ_PARAM net_ehome_ptz_param = new HCISUPCMS.NET_EHOME_PTZ_PARAM();
        net_ehome_ptz_param.read();
        net_ehome_ptz_param.dwSize = net_ehome_ptz_param.size();
        net_ehome_ptz_param.byPTZCmd = Byte.parseByte(String.valueOf(dwPTZCommand));//0-向上,1-向下,2-向左,3-向右，更多取值参考接口文档
        net_ehome_ptz_param.byAction = 0;//云台动作：0- 开始云台动作，1- 停止云台动作
        net_ehome_ptz_param.bySpeed = Byte.parseByte(String.valueOf(speed));//云台速度，取值范围：0~7，数值越大速度越快
        net_ehome_ptz_param.write();
        net_ehome_remote_ctrl_param.read();
        net_ehome_remote_ctrl_param.dwSize = net_ehome_remote_ctrl_param.size();
        net_ehome_remote_ctrl_param.lpInbuffer = net_ehome_ptz_param.getPointer();//输入控制参数
        net_ehome_remote_ctrl_param.dwInBufferSize = net_ehome_ptz_param.size();

        //条件参数输入通道号
        int iChannel = 1; //视频通道号
        IntByReference channle = new IntByReference(iChannel);
        net_ehome_remote_ctrl_param.lpCondBuffer = channle.getPointer();
        net_ehome_remote_ctrl_param.dwCondBufferSize = 4;

        net_ehome_remote_ctrl_param.write();
        boolean state = hcISUPCMS.NET_ECMS_RemoteControl(luserID,HCISUPCMS.NET_EHOME_PTZ_CTRL, net_ehome_remote_ctrl_param);
        if (!state){
            log.info("云台控制失败！");
            return false;
        }
        return true;
    }

    //停止云台转动
    public boolean stopPTZControlOther(int luserID) {
        HCISUPCMS.NET_EHOME_REMOTE_CTRL_PARAM net_ehome_remote_ctrl_param = new HCISUPCMS.NET_EHOME_REMOTE_CTRL_PARAM();
        HCISUPCMS.NET_EHOME_PTZ_PARAM net_ehome_ptz_param = new HCISUPCMS.NET_EHOME_PTZ_PARAM();
        net_ehome_ptz_param.read();
        net_ehome_ptz_param.dwSize = net_ehome_ptz_param.size();
        net_ehome_ptz_param.byPTZCmd = 0;//0-向上,1-向下,2-向左,3-向右，更多取值参考接口文档
        net_ehome_ptz_param.byAction = 1;//云台动作：0- 开始云台动作，1- 停止云台动作
        net_ehome_ptz_param.bySpeed = 5;//云台速度，取值范围：0~7，数值越大速度越快
        net_ehome_ptz_param.write();
        net_ehome_remote_ctrl_param.read();
        net_ehome_remote_ctrl_param.dwSize = net_ehome_remote_ctrl_param.size();
        net_ehome_remote_ctrl_param.lpInbuffer = net_ehome_ptz_param.getPointer();//输入控制参数
        net_ehome_remote_ctrl_param.dwInBufferSize = net_ehome_ptz_param.size();

        //条件参数输入通道号
        int iChannel = 1; //视频通道号
        IntByReference channle = new IntByReference(iChannel);
        net_ehome_remote_ctrl_param.lpCondBuffer = channle.getPointer();
        net_ehome_remote_ctrl_param.dwCondBufferSize = 4;

        net_ehome_remote_ctrl_param.write();
        boolean state = hcISUPCMS.NET_ECMS_RemoteControl(luserID,HCISUPCMS.NET_EHOME_PTZ_CTRL, net_ehome_remote_ctrl_param);
        if (!state){
            log.info("云台停止控制失败！");
            return false;
        }

        return true;
    }


    /**
     * 抓拍
     */
    public Boolean takePic(int luserID){
        //拍照

        HCISUPCMS.NET_EHOME_PTXML_PARAM m_struParam = new HCISUPCMS.NET_EHOME_PTXML_PARAM();
        m_struParam.read();

        String url = "GET /ISAPI/Streaming/channels/1"+luserID+"1/picture/async?format=json&imageType=JPEG&URLType=cloudURL";
        HCISUPCMS.BYTE_ARRAY ptrUrl = new HCISUPCMS.BYTE_ARRAY(url.length() + 1);
        System.arraycopy(url.getBytes(), 0, ptrUrl.byValue, 0, url.length());
        ptrUrl.write();

        m_struParam.pRequestUrl = ptrUrl.getPointer();
        m_struParam.dwRequestUrlLen = url.length();

        m_struParam.pInBuffer = null;
        m_struParam.dwInSize = 0;

        int iOutSize = 2 * 1024 * 1024;
        HCISUPCMS.BYTE_ARRAY ptrOutByte = new HCISUPCMS.BYTE_ARRAY(iOutSize);
        m_struParam.pOutBuffer = ptrOutByte.getPointer();
        m_struParam.dwOutSize = iOutSize;

        m_struParam.dwRecvTimeOut = 100000;
        m_struParam.write();

        Integer code;
        if (!hcISUPCMS.NET_ECMS_ISAPIPassThrough(luserID, m_struParam) && (code = hcISUPCMS.NET_ECMS_GetLastError()) != 10) {
            log.error("NET_ECMS_ISAPIPassThrough failed, error? " + code);
            return false;
        } else {
            m_struParam.read();
            ptrOutByte.read();
            log.info(luserID+"抓拍 NET_ECMS_ISAPIPassThrough succeed");
            // System.out.println(new String(ptrOutByte.byValue, StandardCharsets.UTF_8));

            // **抓拍返回数据**
            String response = new String(ptrOutByte.byValue, StandardCharsets.UTF_8).trim();

            /*
            * 根据文件名和存储路径规则从临时路径拷贝文件并写入数据库
            *
            * 存储规则: ss_filepath(yml配置)/deviceId(设备id)/Date(yyyyMMdd)/time.jpg
            * */
            try {
                JSONObject urlJson = JSONObject.parseObject(response);
                JSONObject urlJson1 = (JSONObject) urlJson.get("PictureData");
                String imgUrl = urlJson1.getString("url");
                String pFileName = imgUrl.substring(imgUrl.indexOf('?')+1);

                //文件存储路径配置
                String deviceId = lUserIdAndDeviceMap.get(String.valueOf(luserID));
                Long time = new Date().getTime();
                String date = new SimpleDateFormat("yyyyMMdd").format(time);

                //最终的存储位置
                String strFilePath = ss_filepath+deviceId+"/"+date+"/"+pFileName+".jpg";
                //临时文件存储位置
                String oldImgPath = ss_filepath+"temporary/"+pFileName+".jpg";

                if (!moveFile(strFilePath,oldImgPath)){
                    return false;
                }

            }catch (Exception e){
                log.error("【抓拍】{}抓拍返回信息解析异常",luserID);
            }

            return true;
        }

    }

    //录像
    public Boolean startPlayBackVideo(int luserID){

        if(lUserIDList.contains(luserID)){
            log.error("设备正在录制!");
            return true;
        }

        HCISUPCMS.NET_EHOME_PTXML_PARAM m_struParam = new HCISUPCMS.NET_EHOME_PTXML_PARAM();
        m_struParam.read();

        String url = "PUT /ISAPI/ContentMgmt/record/control/manual/start/tracks/1"+luserID+"1";
        HCISUPCMS.BYTE_ARRAY ptrUrl = new HCISUPCMS.BYTE_ARRAY(url.length() + 1);
        System.arraycopy(url.getBytes(), 0, ptrUrl.byValue, 0, url.length());
        ptrUrl.write();

        m_struParam.pRequestUrl = ptrUrl.getPointer();
        m_struParam.dwRequestUrlLen = url.length();

        m_struParam.pInBuffer = null;
        m_struParam.dwInSize = 0;

        int iOutSize = 2 * 1024 * 1024;
        HCISUPCMS.BYTE_ARRAY ptrOutByte = new HCISUPCMS.BYTE_ARRAY(iOutSize);
        m_struParam.pOutBuffer = ptrOutByte.getPointer();
        m_struParam.dwOutSize = iOutSize;

        m_struParam.dwRecvTimeOut = 5000;
        m_struParam.write();
        if (!hcISUPCMS.NET_ECMS_ISAPIPassThrough(luserID, m_struParam)) {
            System.out.println("开始录像 NET_ECMS_ISAPIPassThrough failed,error??" + hcISUPCMS.NET_ECMS_GetLastError());
            return false;
        } else {
            m_struParam.read();
            ptrOutByte.read();
            lUserIDList.add(luserID);
            log.info(luserID+"开始录像 NET_ECMS_ISAPIPassThrough succeed");
            return true;
        }
    }

    //停止录像
    public Boolean stopPlayBackVideo(int luserID){

        if(!lUserIDList.contains(luserID)){
            log.error("设备未录制!");
            return false;
        }

        HCISUPCMS.NET_EHOME_PTXML_PARAM m_struParam = new HCISUPCMS.NET_EHOME_PTXML_PARAM();
        m_struParam.read();

        String url = "PUT /ISAPI/ContentMgmt/record/control/manual/stop/tracks/1"+luserID+"1";
        HCISUPCMS.BYTE_ARRAY ptrUrl = new HCISUPCMS.BYTE_ARRAY(url.length() + 1);
        System.arraycopy(url.getBytes(), 0, ptrUrl.byValue, 0, url.length());
        ptrUrl.write();

        m_struParam.pRequestUrl = ptrUrl.getPointer();
        m_struParam.dwRequestUrlLen = url.length();

        m_struParam.pInBuffer = null;
        m_struParam.dwInSize = 0;

        int iOutSize = 2 * 1024 * 1024;
        HCISUPCMS.BYTE_ARRAY ptrOutByte = new HCISUPCMS.BYTE_ARRAY(iOutSize);
        m_struParam.pOutBuffer = ptrOutByte.getPointer();
        m_struParam.dwOutSize = iOutSize;

        m_struParam.dwRecvTimeOut = 5000;
        m_struParam.write();
        if (!hcISUPCMS.NET_ECMS_ISAPIPassThrough(luserID, m_struParam)) {
            System.out.println("停止录像 NET_ECMS_ISAPIPassThrough failed,error??" + hcISUPCMS.NET_ECMS_GetLastError());
            return false;
        } else {
            m_struParam.read();
            ptrOutByte.read();
            lUserIDList.remove(luserID);
            log.info(luserID+"停止录像 NET_ECMS_ISAPIPassThrough succeed");
            return true;
        }
    }

    //查询当前设备是否在录象
    public Boolean videotapedStatus(int luserID){
        return lUserIDList.contains(luserID);
    }

    /**
     * 查找录像文件
     */
    public List<Map<String,String>> findVideoFile(int luserID,LocalDateTime startTime,LocalDateTime endTime) {

        List<Map<String,String>> fileInfoList = new ArrayList<>();

        HCISUPCMS.NET_EHOME_REC_FILE_COND struRecFileCond = new HCISUPCMS.NET_EHOME_REC_FILE_COND();
        struRecFileCond.dwChannel = 1;
        struRecFileCond.dwRecType = 0xff;
        struRecFileCond.dwMaxFileCountPer = 8;


        struRecFileCond.struStartTime.wYear = (short) startTime.getYear();
        struRecFileCond.struStartTime.byMonth = (byte) startTime.getMonthValue();
        struRecFileCond.struStartTime.byDay = (byte) startTime.getDayOfMonth();
        struRecFileCond.struStartTime.byHour = (byte) startTime.getHour();
        struRecFileCond.struStartTime.byMinute = (byte) startTime.getMinute();
        struRecFileCond.struStartTime.bySecond = (byte) startTime.getSecond();

        struRecFileCond.struStopTime.wYear = (short) endTime.getYear();
        struRecFileCond.struStopTime.byMonth = (byte) endTime.getMonthValue();
        struRecFileCond.struStopTime.byDay = (byte) endTime.getDayOfMonth();
        struRecFileCond.struStopTime.byHour = (byte) endTime.getHour();
        struRecFileCond.struStopTime.byMinute = (byte) endTime.getMinute();
        struRecFileCond.struStopTime.bySecond = (byte) endTime.getSecond();

        struRecFileCond.write();

        Pointer ptrRecFileCond = struRecFileCond.getPointer();
        int m_lFileHandle = hcISUPCMS.NET_ECMS_StartFindFile_V11(luserID, 0, ptrRecFileCond, struRecFileCond.size());

        if (m_lFileHandle < 0) {
            System.out.println("查询失败，错误码：" + hcISUPCMS.NET_ECMS_GetLastError());
            return fileInfoList;
        }

        struRecFileCond.read();
        String Filesize = "";
        String szFileName = "";
        String struStartTime = "";
        String struStopTime = "";
        HCISUPCMS.NET_EHOME_REC_FILE struFileInfo = new HCISUPCMS.NET_EHOME_REC_FILE();
        struFileInfo.dwSize = struFileInfo.size();
        struFileInfo.write();

        while (true) {
            //逐个获取检索到的文件
            int lRet = hcISUPCMS.NET_ECMS_FindNextFile_V11(m_lFileHandle, struFileInfo.getPointer(), struFileInfo.size());
            struFileInfo.read();
            if (lRet == HCISUPCMS.SEARCH_GET_NEXT_STATUS_ENUM.ENUM_GET_NEXT_STATUS_SUCCESS) {
                Filesize = Integer.toString(struFileInfo.dwFileSize);
                szFileName = new String(struFileInfo.sFileName).trim();
                struStartTime = timeToStr(struFileInfo.struStartTime);
                struStopTime = timeToStr(struFileInfo.struStopTime);
                //搜索到的文件信息
                Map<String,String> fileInfo = new HashMap<>();
                fileInfo.put("FileName", szFileName);
                fileInfo.put("Filesize", Filesize);
                fileInfo.put("StartTime", struStartTime);
                fileInfo.put("StopTime", struStopTime);
                //System.out.println("Filename[" + szFileName + "], Filesize[" + Filesize + "], StarTime[" + struStartTime + "], StopTime[" + struStopTime + "]");
                fileInfoList.add(fileInfo);
            } else if (lRet == HCISUPCMS.SEARCH_GET_NEXT_STATUS_ENUM.ENUM_GET_NETX_STATUS_NEED_WAIT) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else if ((lRet == HCISUPCMS.SEARCH_GET_NEXT_STATUS_ENUM.ENUM_GET_NETX_STATUS_NO_FILE)
                    || (lRet == HCISUPCMS.SEARCH_GET_NEXT_STATUS_ENUM.ENUM_GET_NEXT_STATUS_FINISH)) {
                log.info(luserID+"录像文件查询结束！");
                break;
            } else if (lRet == HCISUPCMS.SEARCH_GET_NEXT_STATUS_ENUM.ENUM_GET_NEXT_STATUS_NOT_SUPPORT) {
                log.error("设备不支持该操作！！");
                break;
            } else {
                break;
            }
        }

        return fileInfoList;
    }

    /*
    * 以下是本CLASS所需要的工具函数
    * */
    private String timeToStr(HCISUPCMS.NET_EHOME_TIME struStartTime) {
        LocalDateTime localDateTime = LocalDateTime.of(struStartTime.wYear,struStartTime.byMonth,struStartTime.byDay,struStartTime.byHour,struStartTime.byMinute,struStartTime.bySecond);
        return String.valueOf(localDateTime);
    }

    /*
    * copy文件
    * */
    public static boolean moveFile(String destinationPath, String sourcePath) {
        File sourceFile = new File(sourcePath);
        File destinationFile = new File(destinationPath);

        // 确保源文件存在
        if (!sourceFile.exists()) {
            log.error("【抓拍文件拷贝】源文件不存在：" + sourcePath);
            return false;
        }

        // 确保目标目录存在
        File destinationDir = destinationFile.getParentFile();
        if (destinationDir != null && !destinationDir.exists()) {
            destinationDir.mkdirs();
        }

        try {
            // 使用 Files.copy 进行文件复制
            Files.copy(sourceFile.toPath(), destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 删除原文件
            if (!sourceFile.delete()) {
                log.error("【抓拍文件拷贝】文件复制成功，但删除源文件失败：" + sourcePath);
            }
            return true;
        } catch (IOException e) {
            log.error("【抓拍文件拷贝】文件操作失败");
            return false;
        }
    }

}

