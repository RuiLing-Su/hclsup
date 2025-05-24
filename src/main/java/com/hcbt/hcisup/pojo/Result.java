package com.hcbt.hcisup.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    private Integer code;   //响应码   1 成功 0失败
    private String mag;     //相应信息  描述字符串
    private Object data;    //返回数据

    //成功 无数据
    public static Result success() {
        return new Result(1, "success", null);
    }

    //成功 带数据
    public static Result success(Object data){
        return new Result(1, "success", data);
    }

    //失败
    public static Result error(String mag){
        return new Result(0,mag,null);
    }

}
