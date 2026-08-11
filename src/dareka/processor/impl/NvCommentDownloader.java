package dareka.processor.impl;

import java.io.IOException;

import dareka.common.Logger;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.processor.HttpRequestHeader;
import dareka.processor.URLResource;

/** 現行nvcomment v1のコメントJSONをwatchページの情報から取得する。 */
final class NvCommentDownloader {
    private NvCommentDownloader() {
    }

    static URLResource getResource(String videoId, WatchVars watchVars,
            HttpRequestHeader requestHeader) throws IOException {
        if (watchVars == null || watchVars.getType() != WatchVars.Type.Html5) {
            return null;
        }
        JsonObject json = watchVars.getJsonObject();
        JsonObject nvComment = json == null
                ? null : json.getObject("comment", "nvComment");
        if (nvComment == null) {
            Logger.warning("comment.nvComment not found: " + videoId);
            return null;
        }

        String serverUrl = nvComment.getString("server");
        String threadKey = nvComment.getString("threadKey");
        JsonObject params = nvComment.getObject("params");
        if (serverUrl == null || serverUrl.isEmpty()
                || threadKey == null || threadKey.isEmpty() || params == null) {
            Logger.warning("nvcomment download parameters not found: " + videoId);
            return null;
        }

        JsonObject requestJson = new JsonObject()
                .put("params", params)
                .put("threadKey", new JsonString(threadKey))
                .put("additionals", new JsonObject());
        requestHeader.setMessageHeader("Content-Type",
                "text/plain;charset=UTF-8");
        requestHeader.setMessageHeader("Accept", "application/json");
        requestHeader.setMessageHeader("x-client-os-type", "others");
        requestHeader.setMessageHeader("x-frontend-id", "6");
        requestHeader.setMessageHeader("x-frontend-version", "0");
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "v1/threads" : serverUrl + "/v1/threads";
        URLResource resource = NicoApiUtil.getURLResource(
                endpoint, requestHeader, requestJson.toJson());
        resource.getResponseHeader(null, null).setMessageHeader(
                "Content-Disposition",
                "attachment; filename=\"" + videoId + ".comments.json\"");
        return resource;
    }
}
