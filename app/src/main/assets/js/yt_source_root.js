function getParser(url) {
    if (url.indexOf('huya.com') !== -1 || url.indexOf('huya://') !== -1) {
        return JSON.stringify(['huya_parse.js', 'parseHuya']);
    }
    return JSON.stringify([]);
}
