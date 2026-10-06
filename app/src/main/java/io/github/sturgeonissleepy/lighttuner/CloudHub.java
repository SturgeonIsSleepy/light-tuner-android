package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CloudHub {
    public static final String ADDRESS="https://lighttuner-threads.zhx100560.workers.dev";
    public static JSONObject request(String path,JSONObject payload) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ADDRESS+path).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Accept","application/json");
        try{if(payload!=null){byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);if(body.length>262144)throw new IOException("上传内容超过 256 KiB，请缩小录制范围");connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(body.length);try(OutputStream out=connection.getOutputStream()){out.write(body);}}
            int code=connection.getResponseCode();String type=connection.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).contains("application/json"))throw new IOException("共享服务返回了非数据页面，请稍后重试");InputStream source=code>=400?connection.getErrorStream():connection.getInputStream();if(source==null)throw new IOException("共享服务未返回内容");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=source){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){bytes.write(buf,0,n);if(bytes.size()>262144)throw new IOException("共享数据超过限制");}}JSONObject result=PlatformProfile.object(new String(bytes.toByteArray(),StandardCharsets.UTF_8));if(code!=200)throw new IOException(result.optString("error","共享服务请求失败"));return result;
        }finally{connection.disconnect();}
    }
    public static ThreadRules download() throws Exception {JSONObject catalog=request("/v1/catalog",null);PlatformProfile.require(catalog.getInt("schemaVersion")==1&&catalog.getString("platform").equals("mt6991"),"共享策略平台或版本不符");JSONArray entries=catalog.getJSONArray("policies");PlatformProfile.require(entries.length()<=200,"共享策略过多");ThreadRules out=new ThreadRules();for(int i=0;i<entries.length();i++){JSONObject p=entries.getJSONObject(i);p.remove("updated");out.put(ThreadRules.Rule.parse(p));}return out;}
}
