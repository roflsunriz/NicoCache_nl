package dareka.processor.impl;

public class Ht2Entry {

    private final long expiredOn;
    private final long updatedOn;

    private final String key;
    private final String videoType;
    private final String audioType;
    private final boolean multiStream;
    private final boolean encrypted;
    private final boolean separatedAudioStream;

    public Ht2Entry(String key, boolean multiStream, String videoType, String audioType,
                    boolean encrypted, boolean separatedAudioStream) {
        this.updatedOn = System.currentTimeMillis();
        this.expiredOn = this.updatedOn + 150000;
        this.key = key;
        this.videoType = videoType;
        this.audioType = audioType;
        this.multiStream = multiStream;
        this.encrypted = encrypted;
        this.separatedAudioStream = separatedAudioStream;
    }

    @Deprecated
    public Ht2Entry(String key, String videoType, String audioType, boolean isFlash) {
        this(key, false, videoType, audioType, false, false);
    }

    public long getExpiredOn() {
        return expiredOn;
    }

    public long getUpdatedOn() {
        return updatedOn;
    }

    public String getKey() {
        return key;
    }

    public String getVideoType() {
        return videoType;
    }

    public String getAudioType() {
        return audioType;
    }

    public boolean getMultiStream() { return multiStream; }

    public boolean getEncrypted() { return encrypted; }

    public boolean getSeparatedAudioStream() { return separatedAudioStream; }

    @Deprecated
    public boolean isFlash() {
        return false;
    }


}
