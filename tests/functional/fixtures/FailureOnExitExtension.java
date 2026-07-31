package extensions;

import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.SystemEventListener;

/**
 * 終了通知で例外を投げ、後続の終了処理が継続することを検証するExtension。
 */
public final class FailureOnExitExtension
        implements Extension2, SystemEventListener {
    @Override
    public void registerExtensions(ExtensionManager manager) {
        manager.registerEventListener(this);
    }

    @Override
    public String getVersionString() {
        return "FailureOnExitExtension/1";
    }

    @Override
    public int onSystemEvent(int id, EventSource source) {
        if (id == SYSTEM_EXIT) {
            throw new IllegalStateException("intentional shutdown test failure");
        }
        return RESULT_OK;
    }
}
