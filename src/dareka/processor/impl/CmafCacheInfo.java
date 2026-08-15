package dareka.processor.impl;

import dareka.common.json.JsonArray;
import dareka.common.json.JsonFalse;
import dareka.common.json.JsonNull;
import dareka.common.json.JsonNumber;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.common.json.JsonTrue;
import dareka.common.json.JsonValue;
import java.io.File;
import java.util.SortedSet;
import java.util.TreeSet;

/** Builds the CMAF/Domand-only cache information returned by /cache/info/v3. */
final class CmafCacheInfo {
    private CmafCacheInfo() {
    }

    static void append(StringBuilder output, String videoId) {
        SortedSet<VideoDescriptor> registered = CacheManager.id2Videos.get(videoId);
        if (registered != null) {
            // Refresh stale registrations before taking the response snapshot.
            for (VideoDescriptor video : new TreeSet<>(registered)) {
                Cache.video2File_get(video);
            }
            registered = CacheManager.id2Videos.get(videoId);
        }

        SortedSet<VideoDescriptor> videos = new TreeSet<>();
        if (registered != null) {
            for (VideoDescriptor video : registered) {
                if (video.isDmc() && Cache.HLS.equals(video.getPostfix())) {
                    videos.add(video);
                }
            }
        }

        JsonObject info = new JsonObject();
        info.put("videoId", stringOrNull(videoId));
        VideoDescriptor preferred = Cache.getPreferredCachedVideo(videoId, true, Cache.HLS);
        info.put("preferred", stringOrNull(
                preferred == null ? null : Cache.videoDescriptorToAltId(preferred)));

        JsonArray cacheIds = new JsonArray();
        JsonArray cachings = new JsonArray();
        JsonArray completes = new JsonArray();
        JsonObject caches = new JsonObject();

        for (VideoDescriptor video : videos) {
            Cache cache = new Cache(video);
            String cacheId = Cache.videoDescriptorToAltId(video);
            boolean complete = cache.exists();
            boolean caching = Cache.getDLFlag(video);

            cacheIds.add(new JsonString(cacheId));
            if (caching) {
                cachings.add(new JsonString(cacheId));
            }
            if (complete) {
                completes.add(new JsonString(cacheId));
            }
            caches.put(cacheId, cacheEntry(videoId, cacheId, video, cache, complete, caching));
        }

        info.put("cacheIds", cacheIds);
        info.put("cachings", cachings);
        info.put("completes", completes);
        info.put("caches", caches);
        info.toJson(output);
    }

    private static JsonObject cacheEntry(String videoId, String cacheId,
            VideoDescriptor video, Cache cache, boolean complete, boolean caching) {
        JsonObject entry = new JsonObject();
        entry.put("videoId", new JsonString(videoId));
        entry.put("cacheId", new JsonString(cacheId));
        entry.put("complete", booleanValue(complete));
        entry.put("caching", booleanValue(caching));
        entry.put("videoMode", stringOrNull(video.getVideoMode()));
        entry.put("audioBitrate", new JsonNumber(video.getAudioBitrate()));
        entry.put("legacyLow", booleanValue(video.isLow()));

        if (complete) {
            entry.put("size", new JsonNumber(cache.length()));
            entry.put("title", stringOrNull(cache.getTitle()));
            entry.put("subFolder", stringOrNull(Cache.getPathFromVideoDescriptor(video)));
            entry.put("filename", stringOrNull(cache.getCacheFileName()));
            entry.put("ts", new JsonNumber(cache.getCacheFile().lastModified() / 1000L));
        } else {
            File tmpFile = Cache.video2Tmp.get(video);
            entry.put("size", new JsonNumber(cache.tmpFinalSize()));
            entry.put("cachingSize", new JsonNumber(cache.tmpCachedSize()));
            if (tmpFile != null) {
                entry.put("title", stringOrNull(
                        Cache.getTitleFromFilename(tmpFile.getName().substring(6))));
                entry.put("subFolder", new JsonString(""));
                entry.put("filename", new JsonString(tmpFile.getName()));
                entry.put("ts", new JsonNumber(tmpFile.lastModified() / 1000L));
            } else {
                entry.put("title", new JsonNull());
                entry.put("subFolder", new JsonNull());
                entry.put("filename", new JsonNull());
                entry.put("ts", new JsonNull());
            }
        }
        return entry;
    }

    private static JsonValue stringOrNull(String value) {
        return value == null ? new JsonNull() : new JsonString(value);
    }

    private static JsonValue booleanValue(boolean value) {
        return value ? new JsonTrue() : new JsonFalse();
    }
}
