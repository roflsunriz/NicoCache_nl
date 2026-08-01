package nicocache.cmaftomp4;

/** CLI引数または利用方法のエラー。 */
final class CliException extends Exception {
    private static final long serialVersionUID = 1L;

    CliException(String message) {
        super(message);
    }
}
