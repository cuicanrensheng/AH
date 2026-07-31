function parseHuya(url) {
    try {
        var roomId = '';
        var match = url.match(/(\d+)/);
        if (match) {
            roomId = match[1];
        }
        
        if (!roomId) {
            return JSON.stringify({error: '无法获取房间ID'});
        }
        
        var apiUrl = 'https://www.huya.com/' + roomId;
        var headers = JSON.stringify({
            'User-Agent': 'Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36',
            'Referer': 'https://www.huya.com/'
        });
        
        var html = getUrlContent(apiUrl, headers);
        if (!html) {
            return JSON.stringify({error: '获取页面失败'});
        }
        
        var streamInfoMatch = html.match(/var\s+streamInfo\s*=\s*({[\s\S]*?});/);
        if (streamInfoMatch) {
            try {
                var streamInfo = JSON.parse(streamInfoMatch[1]);
                var result = [];
                
                if (streamInfo.data && streamInfo.data.streamList) {
                    streamInfo.data.streamList.forEach(function(stream) {
                        result.push({
                            quality: stream.sName,
                            url: stream.sFlvUrl + '/' + stream.sFlvAntiCode + '.flv'
                        });
                    });
                }
                
                return JSON.stringify({success: true, data: result});
            } catch (e) {
                return JSON.stringify({error: '解析失败: ' + e.message});
            }
        }
        
        var liveDataMatch = html.match(/window\.liveData\s*=\s*({[\s\S]*?});/);
        if (liveDataMatch) {
            try {
                var liveData = JSON.parse(liveDataMatch[1]);
                var result = [];
                
                if (liveData.stream && liveData.stream.streamList) {
                    liveData.stream.streamList.forEach(function(stream) {
                        result.push({
                            quality: stream.sName,
                            url: stream.sFlvUrl + '/' + stream.sFlvAntiCode + '.flv'
                        });
                    });
                }
                
                return JSON.stringify({success: true, data: result});
            } catch (e) {
                return JSON.stringify({error: '解析失败: ' + e.message});
            }
        }
        
        return JSON.stringify({error: '未找到直播信息'});
    } catch (e) {
        return JSON.stringify({error: '异常: ' + e.message});
    }
}

function getParseResult(funcName, url) {
    return parseHuya(url);
}