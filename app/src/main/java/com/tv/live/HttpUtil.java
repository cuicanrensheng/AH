package com.tv.live;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

public class HttpUtil {
    public static String get(String url) throws Exception {
        URL u = new URL(url);
        BufferedReader br = new BufferedReader(new InputStreamReader(u.openStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        return sb.toString();
    }
}
