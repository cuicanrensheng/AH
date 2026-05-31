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
    private Context context;

    public static TVVlcPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVVlcPlayerManager(ctx);
        }
        return instance;
    }

    private TVVlcPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        ArrayList<String> options = new ArrayList<>();
        options.add("--aout=opensles");
        options.add("--audio-time-stretch");
        options.add("-vvv");
        libVLC = new LibVLC(context, options);
        mediaPlayer = new MediaPlayer(libVLC);
    }

    public void attachSurfaceView(SurfaceView surfaceView) {
        mediaPlayer.getVLCVout().setVideoView(surfaceView);
        mediaPlayer.getVLCVout().attachViews();
    }

    public void play(String url) {
        try {
            Media media = new Media(libVLC, url);
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void pause() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void resume() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.play();
        }
    }

    public void release() {
        mediaPlayer.release();
        libVLC.release();
        instance = null;
    }

    public boolean isPlaying() {
        return mediaPlayer.isPlaying();
    }
}
