var parsers = {};

function registerParser(keyword, jsFile, parseFunc) {
    parsers[keyword] = {
        file: jsFile,
        func: parseFunc
    };
}

function getParser(url) {
    for (var keyword in parsers) {
        if (url.indexOf(keyword) !== -1) {
            return [parsers[keyword].file, parsers[keyword].func];
        }
    }
    return [];
}

registerParser('huya', 'huya_parse.js', 'parseHuya');
registerParser('live.huya.com', 'huya_parse.js', 'parseHuya');
registerParser('cdn.huya.com', 'huya_parse.js', 'parseHuya');