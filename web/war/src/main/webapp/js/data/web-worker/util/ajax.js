
define(['util/promise'], function() {
    'use strict';

    return ajax;

    function paramPair(key, value) {
        return key + '=' + encodeURIComponent(value);
    }

    function toQueryString(params) {
        var str = '', key;
        for (key in params) {
            if (typeof params[key] !== 'undefined') {

                if (_.isArray(params[key])) {
                    str += _.map(params[key], _.partial(paramPair, key + '[]')).join('&') + '&';
                } else {
                    str += paramPair(key, params[key]) + '&';
                }
            }
        }
        return str.slice(0, str.length - 1);
    }

    function ajax(method, url, parameters) {
        var isJson = true;
        method = method.toUpperCase();

        if (method === 'GET->HTML') {
            isJson = false;
            method = 'GET';
        }

        return new Promise(function(fulfill, reject) {
            var r = new XMLHttpRequest(),
                params = toQueryString(parameters),
                resolvedUrl = BASE_URL +
                    url +
                    ((method === 'GET' && parameters) ?
                        '?' + params :
                        ''
                    ),
                formData;

            r.onload = function() {
                var text = r.status === 200 && r.responseText;

                if (text) {
                    if (isJson) {
                        try {
                            var json = JSON.parse(text);
                            if (typeof ajaxPostfilter !== 'undefined') {
                                ajaxPostfilter(r, json, {
                                    method: method,
                                    url: url,
                                    parameters: parameters
                                });
                            }
                            fulfill(json);
                        } catch(e) {
                            reject(new Error(e.message));
                        }
                    } else {
                        fulfill(text)
                    }
                } else {
                    reject(r);
                }
            };
            r.onerror = function() {
                reject(new Error('Network Error'));
            };
            r.open(method || 'get', resolvedUrl, true);

            if (method === 'POST' && parameters) {
                formData = params;
                r.setRequestHeader('Content-type', 'application/x-www-form-urlencoded');
            }

            if (typeof ajaxPrefilter !== 'undefined') {
                ajaxPrefilter.call(null, r, method, url, parameters);
            }

            r.send(formData);
        });
    }
})