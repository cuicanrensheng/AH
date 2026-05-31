import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BasicCookieJar implements CookieJar {
    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

    // 保存响应返回的 Cookie
    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        cookieStore.put(url.host(), new ArrayList<>(cookies));
    }

    // 请求时自动携带对应域名 Cookie
    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> cookies = cookieStore.get(url.host());
        return cookies != null ? cookies : new ArrayList<>();
    }

    // 清空所有 Cookie（切换直播间/登出使用）
    public void clearCookie() {
        cookieStore.clear();
    }
}
