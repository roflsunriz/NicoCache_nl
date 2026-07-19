package dareka.extensions;

import java.util.EventListener;

import dareka.processor.Processor;

public interface ExtensionManager {
    public abstract void registerProcessor(Processor p);
    public abstract void registerProcessor(Processor p, boolean stopper);
    public abstract void registerRewriter(Rewriter r);
    public abstract void registerRequestFilter(RequestFilter f);

    /**
     * キャッシュ完了時に呼び出される Extension を登録する。
     * @param c CompleteCache を実装した Extension のインスタンス
     * @since NicoCache_nl (9).10
     */
    public abstract void registerCompleteCache(CompleteCache c);

    /**
     * 各種イベントを受け取る Extension を登録する。
     * 実装している EventListener 派生インターフェースの全てが対象となる。
     * @param listener EventListener を実装した Extension のインスタンス
     * @since NicoCache_nl+111111mod
     */
    public abstract void registerEventListener(EventListener listener);

}
