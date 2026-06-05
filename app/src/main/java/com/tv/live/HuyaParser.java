import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HuyaParser {

    private final OkHttpClient client = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private OnParseResultListener listener;

    public interface OnParseResultListener {
        void onSuccess(String url, int type);
        void onError(String msg);
    }

    public void parse(int roomId, OnParseResultListener listener) {
        this.listener = listener;
        if (roomId <= 0) {
            sendError("房间号错误");
            return;
        }
        executor.execute(() -> {
            try {
                String[] result = getPlayUrl(roomId);
                if (result != null && result.length >= 2) {
                    String realUrl = getFinalRedirectUrl(result[0]);
                    sendSuccess(realUrl, Integer.parseInt(result[1]));
                } else {
                    sendError("解析失败，未开播或房间不存在");
                }
            } catch (Exception e) {
                sendError("解析异常：" + e.getMessage());
            }
        });
    }

    private String getFinalRedirectUrl(String url) {
        try {
            URL obj = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) obj.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ExoPlayer");

            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String location = conn.getHeaderField("Location");
                if (location != null && !location.trim().isEmpty()) {
                    conn.disconnect();
                    return getFinalRedirectUrl(location);
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return url;
    }

    private String[] getPlayUrl(int roomId) throws Exception {
        String roomInfo = getRoomInfo(roomId);
        if (roomInfo == null || roomInfo.isEmpty()) return null;

        JSONObject roomObj = new JSONObject(roomInfo);
        JSONObject data = roomObj.optJSONObject("data");
        if (data == null) return null;

        int videoRoom = data.optInt("isVideoRoom", 0);
        String streamName = data.optString("streamName");
        long uid = data.optLong("uid", 111111L);

        String wsTime = Long.toHexString(System.currentTimeMillis() / 1000);
        String wsSecret = md5(uid + streamName + wsTime + "97b64242aa187a74");

        String url;
        if (videoRoom == 1) {
            url = "https://m.huya.com/" + roomId;
        } else {
            url = "https://api.huya.com/m_push/" + roomId +
                    "?wsSecret=" + wsSecret +
                    "&wsTime=" + wsTime +
                    "&u=" + uid;
        }

        String playBody = requestGet(url);
        JSONObject playJson = new JSONObject(playBody);
        JSONArray array = playJson.optJSONArray("data");
        if (array == null || array.length() == 0) return null;

        JSONObject item = array.optJSONObject(0);
        String flv = item.optString("flv");
        if (flv.isEmpty()) flv = item.optString("m3u8");
        return new String[]{flv, videoRoom + ""};
    }

    private String getRoomInfo(int roomId) throws Exception {
        String url = "https://cdn.huya.com/cache/mini-global-" + roomId + ".json";
        return requestGet(url);
    }

    private String requestGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(getHeaders())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("请求失败");
            return response.body().string();
        }
    }

    // 已改为：ExoPlayer
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Referer", "https://m.huya.com/");
        headers.put("Accept", "application/json, text/plain");
        return headers;
    }

    private String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(str.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) sb.append("0");
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void sendSuccess(final String url, final int type) {
        handler.post(() -> {
            if (listener != null) listener.onSuccess(url, type);
        });
    }

    private void sendError(final String msg) {
        handler.post(() -> {
            if (listener != null) listener.onError(msg);
        });
    }

    public void release() {
        executor.shutdown();
    }
}
