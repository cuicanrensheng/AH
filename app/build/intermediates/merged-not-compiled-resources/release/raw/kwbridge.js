!function(scope, factoryFunc) {
//  if ("function" == typeof define && (define.amd || define.cmd)) {
//    define(function() {
//      return factoryFunc(scope);
//    });
//  } else {
    factoryFunc(scope, true);
//  }
}(window, function(scope, useScope) {

    if (scope.KiwiJSBridge) {
        return scope.KiwiJSBridge;
    }

    /////////////// begin utils //////////////////

    var base64encodechars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    function base64encode(str) {
        if (str === undefined) {
            return str;
        }

        var out, i, len;
        var c1, c2, c3;
        len = str.length;
        i = 0;
        out = "";
        while (i < len) {
            c1 = str.charCodeAt(i++) & 0xff;
            if (i == len) {
                out += base64encodechars.charAt(c1 >> 2);
                out += base64encodechars.charAt((c1 & 0x3) << 4);
                out += "==";
                break;
            }
            c2 = str.charCodeAt(i++);
            if (i == len) {
                out += base64encodechars.charAt(c1 >> 2);
                out += base64encodechars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xf0) >> 4));
                out += base64encodechars.charAt((c2 & 0xf) << 2);
                out += "=";
                break;
            }
            c3 = str.charCodeAt(i++);
            out += base64encodechars.charAt(c1 >> 2);
            out += base64encodechars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xf0) >> 4));
            out += base64encodechars.charAt(((c2 & 0xf) << 2) | ((c3 & 0xc0) >> 6));
            out += base64encodechars.charAt(c3 & 0x3f);
        }
        return out;
    }
   
   var UTF8 = {

        // public method for url encoding
        encode: function(string) {
            string = string.replace(/\r\n/g, "\n");
            var utftext = "";

            for (var n = 0; n < string.length; n++) {

                var c = string.charCodeAt(n);

                if (c < 128) {
                    utftext += String.fromCharCode(c);
                } else if ((c > 127) && (c < 2048)) {
                    utftext += String.fromCharCode((c >> 6) | 192);
                    utftext += String.fromCharCode((c & 63) | 128);
                } else {
                    utftext += String.fromCharCode((c >> 12) | 224);
                    utftext += String.fromCharCode(((c >> 6) & 63) | 128);
                    utftext += String.fromCharCode((c & 63) | 128);
                }

            }

            return utftext;
        },

        // public method for url decoding
        decode: function(utftext) {
            var string = "";
            var i = 0;
            var c = c1 = c2 = 0;

            while (i < utftext.length) {

                c = utftext.charCodeAt(i);

                if (c < 128) {
                    string += String.fromCharCode(c);
                    i++;
                } else if ((c > 191) && (c < 224)) {
                    c2 = utftext.charCodeAt(i + 1);
                    string += String.fromCharCode(((c & 31) << 6) | (c2 & 63));
                    i += 2;
                } else {
                    c2 = utftext.charCodeAt(i + 1);
                    c3 = utftext.charCodeAt(i + 2);
                    string += String.fromCharCode(((c & 15) << 12) | ((c2 & 63) << 6) | (c3 & 63));
                    i += 3;
                }
            }
            return string;
        }
    };

    var JSON = window.JSON ? window.JSON : {};

    if (!window.JSON) {
        window.JSON = JSON;
    };


    (function() {
        'use strict';

        function f(n) {
            return n < 10 ? '0' + n : n;
        }

        if (typeof Date.prototype.toJSON !== 'function') {

            Date.prototype.toJSON = function(key) {

                return isFinite(this.valueOf()) ? this.getUTCFullYear() + '-' + f(this.getUTCMonth() + 1) + '-' + f(this.getUTCDate()) + 'T' + f(this.getUTCHours()) + ':' + f(this.getUTCMinutes()) + ':' + f(this.getUTCSeconds()) + 'Z' : null;
            };

            String.prototype.toJSON = Number.prototype.toJSON = Boolean.prototype.toJSON = function(key) {
                return this.valueOf();
            };
        }

        var cx = /[\u0000\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g,
            escapable = /[\\\"\x00-\x1f\x7f-\x9f\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]/g,
            gap, indent, meta = { // table of character substitutions
                '\b': '\\b',
                '\t': '\\t',
                '\n': '\\n',
                '\f': '\\f',
                '\r': '\\r',
                '"': '\\"',
                '\\': '\\\\'
            },
            rep;


        function quote(string) {

            escapable.lastIndex = 0;
            return escapable.test(string) ? '"' + string.replace(escapable, function(a) {
                var c = meta[a];
                return typeof c === 'string' ? c : '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
            }) + '"' : '"' + string + '"';
        }


        function str(key, holder) {

            var i, // The loop counter.
            k, // The member key.
            v, // The member value.
            length, mind = gap,
                partial, value = holder[key];

            // 有些网站如youku.com会重载了toJSON这个函数，然后返回了json转义的串，然后我们再转一次的时候就悲剧了。所以需要注掉下面这个逻辑。
            // Douglas Crockford一开始为什么要加这么个sb逻辑呢。 既然加了，那用了别人的方法应该直接return啊。又转一次那不是sb了吗？？？坑啊。

            // If the value has a toJSON method, call it to obtain a replacement value.           
            // if (value && typeof value === 'object' && typeof value.toJSON === 'function') {
            //     value = value.toJSON(key);
            // }

            if (typeof rep === 'function') {
                value = rep.call(holder, key, value);
            }

            switch (typeof value) {
            case 'string':
                return quote(value);

            case 'number':

                return isFinite(value) ? String(value) : 'null';

            case 'boolean':
            case 'null':

                return String(value);

            case 'object':

                if (!value) {
                    return 'null';
                }

                gap += indent;
                partial = [];

                if (Object.prototype.toString.apply(value) === '[object Array]') {

                    length = value.length;
                    for (i = 0; i < length; i += 1) {
                        partial[i] = str(i, value) || 'null';
                    }

                    v = partial.length === 0 ? '[]' : gap ? '[\n' + gap + partial.join(',\n' + gap) + '\n' + mind + ']' : '[' + partial.join(',') + ']';
                    gap = mind;
                    return v;
                }

                if (rep && typeof rep === 'object') {
                    length = rep.length;
                    for (i = 0; i < length; i += 1) {
                        if (typeof rep[i] === 'string') {
                            k = rep[i];
                            v = str(k, value);
                            if (v) {
                                partial.push(quote(k) + (gap ? ': ' : ':') + v);
                            }
                        }
                    }
                } else {

                    for (k in value) {
                        if (Object.prototype.hasOwnProperty.call(value, k)) {
                            v = str(k, value);
                            if (v) {
                                partial.push(quote(k) + (gap ? ': ' : ':') + v);
                            }
                        }
                    }
                }

                v = partial.length === 0 ? '{}' : gap ? '{\n' + gap + partial.join(',\n' + gap) + '\n' + mind + '}' : '{' + partial.join(',') + '}';
                gap = mind;
                return v;
            }
        }

        if (typeof JSON.stringify !== 'function') {
            JSON.stringify = function(value, replacer, space) {

                var i;
                gap = '';
                indent = '';

                if (typeof space === 'number') {
                    for (i = 0; i < space; i += 1) {
                        indent += ' ';
                    }

                } else if (typeof space === 'string') {
                    indent = space;
                }

                rep = replacer;
                if (replacer && typeof replacer !== 'function' && (typeof replacer !== 'object' || typeof replacer.length !== 'number')) {
                    throw new Error('JSON.stringify');
                }
                return str('', {
                    '': value
                });
            };
        }

        if (typeof JSON.parse !== 'function') {
            JSON.parse = function(text, reviver) {

                var j;

                function walk(holder, key) {

                    var k, v, value = holder[key];
                    if (value && typeof value === 'object') {
                        for (k in value) {
                            if (Object.prototype.hasOwnProperty.call(value, k)) {
                                v = walk(value, k);
                                if (v !== undefined) {
                                    value[k] = v;
                                } else {
                                    delete value[k];
                                }
                            }
                        }
                    }
                    return reviver.call(holder, key, value);
                }

                text = String(text);
                cx.lastIndex = 0;
                if (cx.test(text)) {
                    text = text.replace(cx, function(a) {
                        return '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
                    });
                }

               
                if (/^[\],:{}\s]*$/.test(text.replace(/\\(?:["\\\/bfnrt]|u[0-9a-fA-F]{4})/g, '@').replace(/"[^"\\\n\r]*"|true|false|null|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?/g, ']').replace(/(?:^|:|,)(?:\s*\[)+/g, ''))) {
                    j = eval('(' + text + ')');
                    return typeof reviver === 'function' ? walk({
                        '': j
                    }, '') : j;
                }

                // If the text is not JSON parseable, then a SyntaxError is thrown.
                throw new SyntaxError('JSON.parse');
            };
        }
    }());

    /////////////// end utils //////////////////

    var _readyMessageIframe, _sendMessageQueue = [],
        _CUSTOM_PROTOCOL_SCHEME = 'kiwi',
        _callback_count = 1000,
        _callback_map = {},
        _event_hook_map = {},
        _messageHandlers = {},
        _QUEUE_HAS_MESSAGE = 'dispatch_message/',
        _EVENT_ID = '__event_id',        
        _CALLBACK_ID = '__callback_id',
        _MESSAGE_TYPE = '__msg_type';

    var _handleMessageIdentifier = _handleMessageFromKiwi;
    var _fetchQueueIdentifier = _fetchQueue;
    var _logIdentifier = _log;
    var _callIdentifier = _call;
    var _onIdentifier = _on;

    function registerHandler(handlerName, handler) {
        _messageHandlers[handlerName] = handler
    }

    function _createQueueReadyIframe(doc) {
        // _setResultIframe 的初始化
        _setResultIframe = doc.createElement('iframe');
        _setResultIframe.id = '__HUYAJSBridgeIframe_SetResult';
        _setResultIframe.style.display = 'none';
        doc.documentElement.appendChild(_setResultIframe);

        _readyMessageIframe = doc.createElement('iframe');
        _readyMessageIframe.id = '__HUYAJSBridgeIframe';
        _readyMessageIframe.style.display = 'none';
        doc.documentElement.appendChild(_readyMessageIframe);
        return _readyMessageIframe;
    }

    function _sendMessage(message) {
        _sendMessageQueue.push(message);

        if (_readyMessageIframe) {
            _readyMessageIframe.src = _CUSTOM_PROTOCOL_SCHEME + '://' + _QUEUE_HAS_MESSAGE;
        };
    };

    function _fetchQueue() {

        var messageQueueString = JSON.stringify(_sendMessageQueue);  
        _sendMessageQueue = [];
        _setResultValue('SCENE_FETCHQUEUE', messageQueueString);

        //朵唯 L1M 手机，Java调JS方法不能return 否则会重定向网页，FK
        //return messageQueueString;
    };

    function _handleMessageFromKiwi(message) {

        var SCENE_HANDLEMSGFROMKIWI = "SCENE_HANDLEMSGFROMKIWI";
        var curFuncIdentifier = KiwiJSBridge._handleMessageFromKiwi;

        if (curFuncIdentifier !== _handleMessageIdentifier) {
            return '{}';
        }

        var ret;
        var msgWrap = message;
        
        switch(msgWrap[_MESSAGE_TYPE]){
            case 'callback':
            {       
                var retObj = {
                    msg  : "callback",
                    func : msgWrap.func ? msgWrap.func : ""
                };

                if(typeof msgWrap[_CALLBACK_ID] === 'string' && typeof _callback_map[msgWrap[_CALLBACK_ID]] === 'function'){
                    var ret = _callback_map[msgWrap[_CALLBACK_ID]](msgWrap['__params']);
                    delete _callback_map[msgWrap[_CALLBACK_ID]]; // can only call once
                    retObj.ret = ret;
                    _setResultValue(SCENE_HANDLEMSGFROMKIWI, JSON.stringify(retObj));
                    // return JSON.stringify(retObj);
                }

                retObj['__err_code'] = 'cb404';
                _setResultValue(SCENE_HANDLEMSGFROMKIWI, JSON.stringify(retObj));
                // return JSON.stringify(retObj);          
            }
            break;
            case 'event':
            {
                var retObj = {
                    msg  : "event",
                };
                retObj[_EVENT_ID] = msgWrap[_EVENT_ID];

                if (typeof msgWrap[_EVENT_ID] === 'string' ) {
                    if(typeof _event_hook_map[msgWrap[_EVENT_ID]] === 'function') {
                        var ret = _event_hook_map[msgWrap[_EVENT_ID]](msgWrap['__params']);
                        retObj.ret = ret;
                        _setResultValue(SCENE_HANDLEMSGFROMKIWI, JSON.stringify(retObj));
                        // return JSON.stringify(retObj);
                    }
                }

                retObj["__err_code"] = "cb404";
                _setResultValue(SCENE_HANDLEMSGFROMKIWI, JSON.stringify(retObj));
                // return JSON.stringify(retObj);
            }
            break;
            case 'call':
            {       
                var retObj = {
                    msg  : "call",
                    func : msgWrap.func
                };

                var responseCallback;

                if (msgWrap[_CALLBACK_ID]) {

                    var callbackResponseId = msgWrap[_CALLBACK_ID];

                    responseCallback = function(responseData) {
                        _respond(responseData , callbackResponseId);
                    }
                }
                
                var handler = _messageHandlers[msgWrap.func];
                
                if (handler) {
                    try {
                        ret = handler(msgWrap['__params'], responseCallback);
                    } catch(exception) {
                        if (typeof console != 'undefined') {
                            console.log("WebViewJavascriptBridge: WARNING: javascript handler threw.", msgWrap, exception);
                        }
                    }
                } else {

                    if (msgWrap[_CALLBACK_ID]) {

                        var callbackResponseId = msgWrap[_CALLBACK_ID];

                        _respond({
                            status  : "fail",
                            err_msg : "handlers not found"
                        } , callbackResponseId);

                    } else {
                        retObj["__err_code"] = "cb404";
                        _setResultValue(SCENE_HANDLEMSGFROMKIWI, JSON.stringify(retObj));
                    }
                    
                    // return JSON.stringify(retObj);
                }
            }
            break;
      }
    };

    // 对IFrame的src属性的变更，客户端有可能还没有收到前一次，就被后一次覆盖，
    // 因此添加一个队列处理这种情况，在收到客户端确认后，再处理下一次
    var _setResultQueue = [];
    var _setResultQueueRunning = false;

    function _setResultValue(scene, result) {
        if (result === undefined) {
            result = 'dummy';
        }

        var utf_8 = UTF8.encode(result);
        _setResultQueue.push(scene + '&' + base64encode(utf_8));
        if (!_setResultQueueRunning) {
            _continueSetResult();
        }
    }

    function _continueSetResult() {

        var result = _setResultQueue.shift();

        if (result === undefined) {
            _setResultQueueRunning = false;
        } else {
            _setResultQueueRunning = true;
            _setResultIframe.src = 'kiwi://private/setresult/' + result;
        }
    }

    function _log(fmt) {

        if (typeof fmt !== 'string') {
            return;
        };
        msg = fmt;
        _call('log',{'msg':msg});
    }

    function _respond(params , callbackId) {

        var msgObj = {'params':params};
        msgObj[_MESSAGE_TYPE] = 'respond';        
        msgObj[_CALLBACK_ID]  = callbackId;

        _sendMessage(JSON.stringify(msgObj));
    }

    function _call(func,params,callback) {

        if (!func || typeof func !== 'string') {
            return;
        };
        if (typeof params !== 'object') {
            params = {};
        };

        var callbackID = (_callback_count++).toString();

        if (typeof callback === 'function') {
          _callback_map[callbackID] = callback;
        };

        var msgObj = {'func':func,'params':params};
        msgObj[_MESSAGE_TYPE] = 'call';        
        msgObj[_CALLBACK_ID] = callbackID;

        _sendMessage(JSON.stringify(msgObj));
    }

    function _on(eventId, params, callback) {
        _changeEventListener("on", eventId, params, callback);    
    }

    function _off(eventId, params, callback) {
        _changeEventListener("off", eventId, params, callback);    
    }

    function _changeEventListener(msgtype , eventId, params, callback) {

        if (!eventId || typeof eventId !== 'string') {
            return;
        };
        if (typeof callback !== 'function') {
            callback = function(){};
        };
        if (typeof params !== 'object') {
            params = {};
        };
        _event_hook_map[eventId] = callback;
        var msgObj = {'params':params};
        msgObj[_EVENT_ID] = eventId;
        msgObj[_MESSAGE_TYPE] = msgtype; 
        _sendMessage(JSON.stringify(msgObj));       
    }

    var KiwiJSBridge = {
        // public
        call:_call,
        log:_log,
        on:_on,
        off:_off,
        registerHandler : registerHandler,
        // private
        _fetchQueue: _fetchQueue,
        _handleMessageFromKiwi: _handleMessageFromKiwi,
        _hasInit: false,
        _continueSetResult: _continueSetResult
    };

    scope.KiwiJSBridge = KiwiJSBridge;

    var doc = document;
    _createQueueReadyIframe(doc);
    var readyEvent = doc.createEvent('Events');
    readyEvent.initEvent('KiwiWebViewJavascriptBridgeReady');
    readyEvent.bridge = KiwiJSBridge;
    doc.dispatchEvent(readyEvent)

    return KiwiJSBridge;
});
void 0;

