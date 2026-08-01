package nicocache.cmaftomp4;

/** FFmpegの起動・変換・中断に関する失敗。 */
public final class ConversionException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        TOOL_NOT_FOUND,
        CONVERSION_FAILED,
        CANCELLED,
        INVALID_REQUEST
    }

    private final Kind kind;

    ConversionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    ConversionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
