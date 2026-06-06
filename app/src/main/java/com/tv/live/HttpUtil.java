private static void setBaseHeader(HttpURLConnection conn, URL urlObj) {
    conn.setConnectTimeout(CONNECT_TIMEOUT);
    conn.setReadTimeout(READ_TIMEOUT);
    try {
        conn.setRequestMethod("GET");
    } catch (ProtocolException e) {
        e.printStackTrace();
    }
    conn.setInstanceFollowRedirects(false);
    conn.setUseCaches(false);

    String host = urlObj.getHost();
    // 【核心适配这套3层中转虎牙接口】只要是中转域名，固定Referer/Origin
    if(host.contains("jdshipin.com") || host.contains("zxyxndc.top")){
        // 和抓包请求头完全一致
        conn.setRequestProperty("Referer","http://cdn.jdshipin.com:8880/");
        conn.setRequestProperty("Origin","http://cdn.jdshipin.com");
        conn.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
    }else if(host.contains("huya.com")){
        // 最终虎牙FLV域名
        conn.setRequestProperty("Referer","https://www.huya.com/");
        conn.setRequestProperty("Origin","https://www.huya.com");
        conn.setRequestProperty("User-Agent","ExoPlayerLib/2.19.1 (Linux; Android 11; Android TV)");
    }else{
        // 普通源沿用原有自动域名
        String domain = urlObj.getProtocol() + "://" + urlObj.getHost();
        conn.setRequestProperty("Referer", domain);
        conn.setRequestProperty("Origin", domain);
        conn.setRequestProperty("User-Agent", UA);
    }

    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
    conn.setRequestProperty("Connection", "keep-alive");
    conn.setRequestProperty("Accept","*/*");
    conn.setRequestProperty("sec-fetch-site","cross-site");
    conn.setRequestProperty("sec-fetch-mode","cors");

    String domainCk = cookieStore.get(urlObj.getHost());
    if (domainCk != null && !domainCk.isEmpty()) {
        conn.setRequestProperty("Cookie", domainCk);
    }
}
