package dareka.processor;

import java.io.File;
import java.io.IOException;

public class LocalCacheFileResource extends LocalFileResource {

    public LocalCacheFileResource(File file) throws IOException {
        super(fixLastModified(file));
    }

    /**
     * touchCacheしているとアクセスするたびにLast-Modifiedが変化して
     * 良からぬことが起きる(HTML5プレイヤーでシークバーが白くならない)ので
     * 固定してやる．
     */
    @SuppressWarnings("serial")
    private static File fixLastModified(File file) {
        if (Boolean.getBoolean("touchCache")) {
            return new File(file.getPath()) {
                @Override
                public long lastModified() {
                    // 2017-01-01 00:00:00 JST
                    return 1483196400000L;
                }
            };
        } else {
            return file;
        }
    }
}
