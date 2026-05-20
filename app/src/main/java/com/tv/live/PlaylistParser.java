package com.tv.live;

import android.os.AsyncTask;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {

    public interface CallbackWithName{
        void onSuccess(List<String> urls, List<String> names);
    }

    public void parseWithName(String url, CallbackWithName callback){
        new AsyncTask<String, Void, List<Object>>(){
            @Override
            protected List<Object> doInBackground(String... strings) {
                List<String> urls = new ArrayList<>();
                List<String> names = new ArrayList<>();
                try{
                    URL u = new URL(strings[0]);
                    HttpURLConnection conn = (HttpURLConnection)u.openConnection();
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    String currentName = "未知频道";
                    while((line=br.readLine())!=null){
                        if(line.startsWith("#EXTINF:")){
                            int comma = line.lastIndexOf(",");
                            if(comma>0) currentName = line.substring(comma+1);
                        }else if(line.startsWith("http")){
                            urls.add(line);
                            names.add(currentName);
                        }
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
                List<Object> res = new ArrayList<>();
                res.add(urls);
                res.add(names);
                return res;
            }
            @Override
            protected void onPostExecute(List<Object> res) {
                callback.onSuccess((List<String>)res.get(0), (List<String>)res.get(1));
            }
        }.execute(url);
    }
}
