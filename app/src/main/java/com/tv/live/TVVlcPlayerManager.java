package com.tv.live;
import android.content.Context;
import android.view.SurfaceView;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.util.ArrayList;

public class TVVlcPlayerManager {
    private static TVVlcPlayerManager instance;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;

    public static TVVlcPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVVlcPlayerManager(ctx);
        }
        return instance;
    }

    private TVVlcPlayerManager(Context ctx) {
        ArrayList<String> options = new ArrayList<>();
        options.add("--aout=opensles");
        options.add("--audio-time-stretch");
        libVLC = new LibVLC(ctx, options);
        mediaPlayer = new MediaPlayer(libVLC);
    }

    public void play(String url) {
        Media media = new Media(libVLC, url);
        media.addOption(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        media.addOption(":http-referrer=https://www.huya.com/");
        media.addOption(":http-forward-cookies");
        media.addOption(":network-caching=1500");
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();
    }

    public void pause() {
        mediaPlayer.pause();
    }

    public void resume() {
        mediaPlayer.play();
    }

    public void release() {
        mediaPlayer.release();
        libVLC.release();
        instance = null;
    }
}
