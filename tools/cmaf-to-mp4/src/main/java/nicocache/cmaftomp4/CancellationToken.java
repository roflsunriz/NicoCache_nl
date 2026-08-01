package nicocache.cmaftomp4;

/** 長時間処理を安全に中断するための小さな契約。 */
@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();
}
