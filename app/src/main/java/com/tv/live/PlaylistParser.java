import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    private static final String TAG = "M3UParser";

    // 解析网络M3U文件，返回单频道单线路列表
    public static List<List<String>> parseFromUrl(String m3uUrl) throws IOException {
        List<List<String>> channelList = new ArrayList<>();
        URL url = new URL(m3uUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        List<String> singleChannel;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            // 过滤m3u8播放地址
            if (line.startsWith("http") && (line.endsWith(".m3u8") || line.contains(".m3u8"))) {
                singleChannel = new ArrayList<>();
                singleChannel.add(line);
                channelList.add(singleChannel);
            }
        }
        reader.close();
        connection.disconnect();
        Log.d(TAG, "解析完成，频道总数：" + channelList.size());
        return channelList;
    }
}
