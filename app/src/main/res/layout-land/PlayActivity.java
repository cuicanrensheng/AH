import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ui.PlayerView;

public class PlayActivity extends AppCompatActivity {

    private HuyaLivePlayer mHuyaPlayer;
    private PlayerView mPlayerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);

        // 绑定布局里的播放器控件
        mPlayerView = findViewById(R.id.exo_player_view);
        // 初始化虎牙播放工具类
        mHuyaPlayer = new HuyaLivePlayer(this);
        // 把播放器实例绑定到界面控件上
        mPlayerView.setPlayer(mHuyaPlayer.getExoPlayer());

        // ========== 这里替换成你的虎牙直播间ID ==========
        String roomId = "123456";
        // 开始拉流并播放
        mHuyaPlayer.startPlay(roomId);
    }

    /**
     * 切换直播间的示例方法
     */
    public void changeRoom(String newRoomId) {
        // 切换前清空旧Cookie，避免会话冲突
        mHuyaPlayer.clearCookie();
        // 播放新直播间
        mHuyaPlayer.startPlay(newRoomId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面暂停时，暂停播放
        if (mHuyaPlayer != null) {
            mHuyaPlayer.pausePlay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 页面恢复时，继续播放
        if (mHuyaPlayer != null) {
            mHuyaPlayer.resumePlay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面销毁时，释放播放器资源，防止内存泄漏
        if (mHuyaPlayer != null) {
            mHuyaPlayer.release();
            mHuyaPlayer = null;
        }
    }
}
