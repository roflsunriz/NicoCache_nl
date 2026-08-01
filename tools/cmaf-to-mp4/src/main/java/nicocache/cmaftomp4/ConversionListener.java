package nicocache.cmaftomp4;

import java.nio.file.Path;
import java.util.List;

/** 変換の状態をGUIやヘッドレスCLIへ通知する。 */
public interface ConversionListener {
    void onStarted(List<String> command);

    void onOutput(String line);

    void onFinished(Path output);
}
