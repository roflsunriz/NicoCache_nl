package nlfilterlab;

record SimulationRequest(
        String fixture,
        String url,
        String contentType,
        int statusCode,
        FilterRule.CacheState cacheState,
        boolean cacheApiFailure,
        String reencoded,
        int reencodedBitrate) {

    SimulationRequest(String fixture, String url, String contentType, int statusCode,
            FilterRule.CacheState cacheState, boolean cacheApiFailure) {
        this(fixture, url, contentType, statusCode, cacheState, cacheApiFailure, "null", 0);
    }
}
