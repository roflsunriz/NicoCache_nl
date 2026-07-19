// 01_globalFilter.txt から読み込まれるグローバルオブジェクト定義
// 2021-03-20
//
// 同梱フィルタで使用する Prototype.js/jQuery に依存しない基本処理を提供する
// （なるべく jQuery っぽくしていますが必要最低限しか実装していません）
//
// また、ページ毎に必要なグローバル定義を本オブジェクトに追加する事もある
// Extension 等でグローバル空間を汚染したくない場合、適当なプレフィックスを
// 付加して本オブジェクトに追加しても良い [ex) NicoCache_nl.ext1FuncA()]
//

var NicoCache_nl = window.NicoCache_nl || {};

// NicoCache_nl オブジェクトを拡張する
NicoCache_nl.extend = function(options) {
  for (var name in options) {
    this[name] = options[name];
  }
};

// console.log があればメッセージをログに出力
NicoCache_nl.log = function(message) {
  if (window.console && typeof console.log === 'function') {
    console.log(message);
  }
};

// XMLHttpRequest オブジェクトを生成して返す
NicoCache_nl.xhr = function() {
  var xhr = null;
  if (window.XMLHttpRequest) {
    xhr = new XMLHttpRequest();
  } else if (window.ActiveXObject) { // IE6
    try {
      xhr = new ActiveXObject("MSXML2.XMLHTTP");
    } catch (e) {
      try {
        xhr = new ActiveXObject("Microsoft.XMLHTTP");
      } catch (e) {}
    }
  }
  if (xhr == null) {
    this.log("XMLHttpRequest object not found");
  }
  return xhr;
};

// オブジェクトをパラメータ文字列(key=value&...)にして返す
NicoCache_nl.param = function(obj) {
  var s = [];
  for (var key in obj) {
    s.push(encodeURIComponent(key) + "=" + encodeURIComponent(obj[key]));
  }
  return s.join("&").replace(/%20/g, "+");
};

// HTTP GET
NicoCache_nl.get = function(url, data, callback) {
  var xhr = this.xhr();
  if (xhr != null) {
    if (typeof data === 'object' && data != null) {
      data = this.param(data);
    }
    if (typeof data !== 'string') {
      callback = data;
      data = "";
    }
    if (data.length > 0) {
      url += (url.indexOf("?") < 0 ? "?" : "&") + data;
    }
    var sync = false;
    if (typeof callback === 'boolean') {
      sync = callback;
    }
    if (typeof callback !== 'function') {
      callback = this.empty;
    }
    xhr.open('GET', url, !sync);
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) callback(xhr);
    };
    xhr.send(null);
  }
  return xhr;
};

// HTTP POST
NicoCache_nl.post = function(url, data, callback) {
  var xhr = this.xhr();
  if (xhr != null) {
    if (typeof data === 'object' && data != null) {
      data = this.param(data);
    }
    if (typeof data !== 'string') {
      callback = data;
      data = "";
    }
    var sync = false;
    if (typeof callback === 'boolean') {
      sync = callback;
    }
    if (typeof callback !== 'function') {
      callback = this.empty;
    }
    xhr.open('POST', url, !sync);
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) callback(xhr);
    };
    xhr.send(data);
  }
  return xhr;
};

NicoCache_nl.empty = function() {};

// cross-site localStorage
// サブドメインをまたいでwww.nicovideo.jpのlocalStorageにアクセス．
// 全てのメソッドはPromiseを返す．
NicoCache_nl.xsLocalStorage = {
  // 処理に必要なiframeなどを差し込む．
  // 必要に応じて自動で呼ばれるが事前に準備も可能．
  // www.nicovideo.jpから呼んだ場合は何もしない．
  prepare: function() {
    return NicoCache_nl._xsStorage.prepare().then(function(_) {
      return NicoCache_nl.xsLocalStorage;
    });
  },

  // keyの先頭に自動で"nl.xsStorage."が付加される
  setItem: function(key, value) {
    return NicoCache_nl._xsStorage.setItem(true, key, value);
  },
  getItem: function(key) {
    return NicoCache_nl._xsStorage.getItem(true, key);
  },
  removeItem: function(key) {
    return NicoCache_nl._xsStorage.removeItem(true, key);
  },

  // keyの値がそのまま使われる
  setItemWithoutPrefix: function(key, value) {
    return NicoCache_nl._xsStorage.setItemWithoutPrefix(true, key, value);
  },
  getItemWithoutPrefix: function(key) {
    return NicoCache_nl._xsStorage.getItemWithoutPrefix(true, key);
  },
  removeItemWithoutPrefix: function(key) {
    return NicoCache_nl._xsStorage.removeItemWithoutPrefix(true, key);
  },
};

// cross-site sessionStorage
// サブドメインをまたいでwww.nicovideo.jpのsessionStorageにアクセス．
// localStorageと同様．
NicoCache_nl.xsSessionStorage = {
  prepare: function() {
    return NicoCache_nl._xsStorage.prepare().then(function(_) {
      return NicoCache_nl.xsSessionStorage;
    });
  },
  setItem: function(key, value) {
    return NicoCache_nl._xsStorage.setItem(false, key, value);
  },
  getItem: function(key) {
    return NicoCache_nl._xsStorage.getItem(false, key);
  },
  removeItem: function(key) {
    return NicoCache_nl._xsStorage.removeItem(false, key);
  },
  setItemWithoutPrefix: function(key, value) {
    return NicoCache_nl._xsStorage.setItemWithoutPrefix(false, key, value);
  },
  getItemWithoutPrefix: function(key) {
    return NicoCache_nl._xsStorage.getItemWithoutPrefix(false, key);
  },
  removeItemWithoutPrefix: function(key) {
    return NicoCache_nl._xsStorage.removeItemWithoutPrefix(false, key);
  },
};

NicoCache_nl._xsStorage = {
  _TOKEN: 'nl_xsStorage',

  // こういう構造を作る:
  //   元のページ
  //     | same-origin: javascript直接呼び出し
  //   postMessage処理用iframe
  //     | cross-origin: postMessageで通信
  //   Storge処理用iframe
  prepare: function() {
    if (this.isSameOrigin)
      return Promise.resolve(this);

    if (this._receptor) {
      if (this._receptor._ready)
        return Promise.resolve(this);
      else {
        return new Promise(function(resolve) {
          this._receptor._waiting.push(resolve);
        }.bind(this));
      }
    }

    if (!document.body) {
      // iframeを挿入するためにbodyが必要なので
      // bodyが作られるまで待って再実行
      return new Promise(function(resolve) {
        document.addEventListener("DOMContentLoaded", function() {
          resolve(this.prepare());
        }.bind(this));
      }.bind(this));
    }

    // postMessage処理用iframe
    var receptor = document.createElement("iframe");
    receptor.style.display = "none";
    receptor.id = "nl_xsStorage";
    document.body.appendChild(receptor);
    // postMessageのcallerが定義された場所がevent.sourceになるので
    // postMessageを呼ぶ関数だけHTML中に書く
    var html = '<html><head><script>function post(w, data, target) { w.postMessage(data, target); }</script></head><body></body></html>';
    receptor.contentDocument.open();
    receptor.contentDocument.write(html);
    receptor.contentDocument.close();
    this._receptor = receptor.contentWindow;
    this._receptor._ready = false;
    this._receptor._waiting = [];

    // Storge処理用iframe
    var transmitter = receptor.contentDocument.createElement("iframe");
    this._receptor.addEventListener("message", function(event) {
      if (/^https?:\/\/www\.nicovideo\.jp$/.test(event.origin)) {
        this._handleResponse(event.data);
      }
    }.bind(this));
    transmitter.id = "transmitter";
    return new Promise(function(resolve) {
      transmitter.onload = function() {
        this._transmitter = transmitter.contentWindow;
        this._receptor._transmitter = this._transmitter;
        this._receptor._ready = true;
        this._receptor._waiting.forEach(function(wresolve) {
          setTimeout(function() {
            wresolve(this);
          }.bind(this), 0);
        }.bind(this));
        delete this._receptor._waiting;
        resolve(this);
      }.bind(this);
      transmitter.src = "//www.nicovideo.jp/local/nllib_xsStorage.html";
      receptor.contentDocument.body.appendChild(transmitter);
    }.bind(this));
  },

  _id: 0, // コマンドのシリアル番号カウンタ
  _promiseResolvers: {},
  _promiseRejectors: {},
  _handleResponse: function(response) {
    if (typeof response !== 'object' || response.TOKEN !== this._TOKEN)
      return;

    var id = response.id;
    if (this._promiseResolvers[id]) {
      var resolve = this._promiseResolvers[id];
      var reject = this._promiseRejectors[id];
      delete this._promiseResolvers[id];
      delete this._promiseRejectors[id];
      if (response.error) {
        if (reject) reject(response.error);
      } else {
        if (resolve) resolve(response.data);
      }
    }
  },

  // シリアル番号を付けてコマンドを発行して
  // Storge処理用iframeからのレスポンスを待つ
  communicate: function(command, data) {
    var id = ++this._id;
    return new Promise(function(resolve, reject) {
      // _handleResponseでresolve/rejectが呼ばれる
      this._promiseResolvers[id] = resolve;
      this._promiseRejectors[id] = reject;
      this._receptor.post(this._transmitter, {
        TOKEN: this._TOKEN,
        id: id,
        command: command,
        data: data,
      }, window.location.protocol + "//www.nicovideo.jp");
    }.bind(this));
  },

  get isSameOrigin() {
    delete this.isSameOrigin;
    return this.isSameOrigin = /^www\.nicovideo\.jp$/.test(window.location.hostname);
  },
  withPrefix: function(key) {
    return "nl.xsStorage." + key;
  },
  _windowStorage(isLocalStorage) {
    if (isLocalStorage)
      return window.localStorage;
    else
      return window.sessionStorage;
  },

  setItemWithoutPrefix: function(isLocalStorage, key, value) {
    return this.prepare().then(function(xsStorage) {
      if (xsStorage.isSameOrigin) {
        this._windowStorage(isLocalStorage).setItem(key, value);
        return undefined;
      } else {
        return xsStorage.communicate("setItem", {
          isLocalStorage: isLocalStorage,
          key: key,
          value: value,
        });
      }
    }.bind(this));
  },
  getItemWithoutPrefix: function(isLocalStorage, key) {
    return this.prepare().then(function(xsStorage) {
      if (xsStorage.isSameOrigin) {
        return this._windowStorage(isLocalStorage).getItem(key);
      } else {
        return xsStorage.communicate("getItem", {
          isLocalStorage: isLocalStorage,
          key: key,
        });
      }
    }.bind(this));
  },
  removeItemWithoutPrefix: function(isLocalStorage, key, value) {
    return this.prepare().then(function(xsStorage) {
      if (xsStorage.isSameOrigin) {
        this._windowStorage(isLocalStorage).removeItem(key);
        return undefined;
      } else {
        return xsStorage.communicate("removeItem", {
          isLocalStorage: isLocalStorage,
          key: key,
        });
      }
    }.bind(this));
  },

  setItem: function(isLocalStorage, key, value) {
    var prefixedKey = this.withPrefix(key);
    return this.setItemWithoutPrefix(isLocalStorage, prefixedKey, value);
  },
  getItem: function(isLocalStorage, key) {
    var prefixedKey = this.withPrefix(key);
    return this.getItemWithoutPrefix(isLocalStorage, prefixedKey);
  },
  removeItem: function(isLocalStorage, key) {
    var prefixedKey = this.withPrefix(key);
    return this.removeItemWithoutPrefix(isLocalStorage, prefixedKey);
  },
};


//// Polyfills ////
// Object.assign
// https://developer.mozilla.org/ja/docs/Web/JavaScript/Reference/Global_Objects/Object/assign#Polyfill
if (typeof Object.assign != 'function') {
  Object.defineProperty(Object, "assign", {
    value: function assign(target, varArgs) {
      'use strict';
      if (target == null) {
        throw new TypeError('Cannot convert undefined or null to object');
      }
      var to = Object(target);
      for (var index = 1; index < arguments.length; index++) {
        var nextSource = arguments[index];
        if (nextSource != null) {
          for (var nextKey in nextSource) {
            if (Object.prototype.hasOwnProperty.call(nextSource, nextKey)) {
              to[nextKey] = nextSource[nextKey];
            }
          }
        }
      }
      return to;
    },
    writable: true,
    configurable: true
  });
}
