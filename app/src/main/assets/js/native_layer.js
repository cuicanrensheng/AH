function getUrlContent(url, headers) {
    if (typeof client !== 'undefined') {
        return client.getUrlContent(url, headers || '{}');
    }
    return '';
}

function getRespAndHeaders(url, headers) {
    if (typeof client !== 'undefined') {
        return client.getRespAndHeaders(url, headers || '{}');
    }
    return '';
}

function postUrlContent(url, headers, body, contentType) {
    if (typeof client !== 'undefined') {
        return client.postUrlContent(url, headers || '{}', contentType || 'application/json', body || '');
    }
    return '';
}

function getMD5(str) {
    if (typeof client !== 'undefined') {
        return client.getMD5(str);
    }
    return '';
}

function writeFile(content, filename) {
    if (typeof client !== 'undefined') {
        return client.writeFile(content, filename);
    }
    return '';
}

function AESDecrypt128(content, key) {
    if (typeof client !== 'undefined') {
        return client.AESDecrypt128(content, key);
    }
    return '';
}

function getLocation(url) {
    if (typeof client !== 'undefined') {
        return client.getLocation(url);
    }
    return '';
}