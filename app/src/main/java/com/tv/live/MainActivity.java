public void playChannel(int index) {
    if (channelSourceList == null || channelSourceList.isEmpty()) {
        log("【播放】频道列表为空，无法播放");
        return;
    }
    index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
    currentPlayIndex = index;
    Channel ch = channelSourceList.get(index);
    if (ch == null || TextUtils.isEmpty(ch.getPlayUrl())) {
        log("【播放】频道地址为空");
        return;
    }
    String url = ch.getPlayUrl();
    log("========================================");
    log("【播放】频道名称：" + ch.getName());
    log("【播放】频道地址：" + url);
    log("【播放】当前索引：" + index);
    log("========================================");
    playerStateListener.setCurrentChannelName(ch.getName());
    //=====虎牙新增代码开始=====
    if (url != null && (url.contains("huya") || url.contains("jdshipin") || url.contains("zxyndc"))) {
        new HuyaParser().parse(String.valueOf(extractRoomId(url)), new HuyaParser.OnParseResultListener() {
            @Override
            public void onSuccess(String playUrl, int type) {
                mPlayerManager.playUrl(playUrl);
            }
            @Override
            public void onError(String msg) {
                mPlayerManager.playUrl(url);
            }
        });
    } else {
        mPlayerManager.playUrl(url);
    }
    //=====虎牙新增代码结束=====
    showChannelNum(index + 1);
    appConfig.setLastPlayIndex(index);
    channelListManager.setChannels(channelSourceList, index);
    epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
    if (info_bar != null) {
        info_bar.setVisibility(View.VISIBLE);
        info_bar.removeCallbacks(hideInfoBar);
        info_bar.postDelayed(hideInfoBar, 2000);
        tv_channel_name.setText(ch.getName());
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        tv_tag_fhd.setText(live.quality);
        tv_tag_audio.setText(live.audio);
        tv_bitrate.setText(live.bitrate);
    }
}
