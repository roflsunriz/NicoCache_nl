package dareka;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dareka.common.Config;
import dareka.common.Logger;
import dareka.processor.impl.EasyRewriter;

/**
 * [nl] Java7 の機能を使ってフォルダの更新を監視する
 */
class DirectoryWatcher extends Thread {

    private static Config config;
    private static String config_properties;
    private static String nlFilter_sys = new String("nlFilter_sys.txt");
    private static String nlFilter_usr = new String("nlFilter.txt");
    private static String corsLiar = new String("corsLiar");

    private static ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "nicocache-directory-watcher-task");
                thread.setDaemon(true);
                return thread;
            });
    private static ConcurrentHashMap<String, ScheduledFuture<?>> futures =
            new ConcurrentHashMap<>();

    private int jarExitStatus = Integer.getInteger("exitStatusIfJarModified", 0);
    private String jarName = getJarName();
    private WatchService watchService;
    private WatchKey nicoCacheFolder, systemNlFiltersFolder,
            nlFiltersFolder, corsLiarFolder;

    private static String getJarName() {
        String name = null;
        java.net.URL main = Main.class.getResource("Main.class");
        if (main != null) {
            String path = main.getPath();
            int ext = path.indexOf(".jar!/");
            if (ext > 0) {
                int start = path.lastIndexOf('/', ext);
                if (start > 0) {
                    name = path.substring(start + 1, ext + 4);
                }
            }
        }
        return name;
    }

    private static class Task implements Callable<Object> {
        String name;
        private Task(String name) {
            this.name = name;
        }
        static Task getTask(String name) {
            if (config_properties.equals(name)) {
                return new Task(config_properties);
            } else if (nlFilter_sys.equals(name)) {
                return new Task(nlFilter_sys);
            } else if (nlFilter_usr.equals(name)) {
                return new Task(nlFilter_usr);
            } else if (corsLiar.equals(name)) {
                return new Task(corsLiar);
            }
            return null;
        }
        @Override
        public Object call() throws Exception {
            if (name == config_properties) {
                config.reload();
            } else if (name == nlFilter_sys) {
                EasyRewriter.getInstance_sys();
            } else if (name == nlFilter_usr) {
                EasyRewriter.getInstance();
            } else if (name == corsLiar) {
                CorsLiarManager.getInstance().load();
            }
            futures.remove(name);

            return null;
        }
    }

    DirectoryWatcher(Config config) {
        super("DirectoryWatcher");

        DirectoryWatcher.config = config;
        config_properties = config.getConfigFile().getName();
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path configDirectory = config.getConfigFile().toPath()
                    .toAbsolutePath().normalize().getParent();
            if (configDirectory == null) {
                configDirectory = NicoCachePaths.dataRoot();
            }
            Files.createDirectories(NicoCachePaths.nlFiltersDirectory().toPath());
            Files.createDirectories(NicoCachePaths.userPath("data/cors"));
            nicoCacheFolder = registerWatcher(configDirectory);
            Path systemFilters = NicoCachePaths.systemNlFiltersDirectory()
                    .toPath().toAbsolutePath().normalize();
            Path userFilters = NicoCachePaths.nlFiltersDirectory()
                    .toPath().toAbsolutePath().normalize();
            systemNlFiltersFolder = registerWatcher(systemFilters);
            nlFiltersFolder = systemFilters.equals(userFilters)
                    ? systemNlFiltersFolder : registerWatcher(userFilters);
            corsLiarFolder = registerWatcher(
                    NicoCachePaths.userPath("data/cors"));
        } catch (IOException e) {
            Logger.debug(e);
            Logger.warning(e.toString());
        }
    }

    @Override
    public void run() {
        Logger.debugWithThread("started, jarName=" + jarName);
        try {
            for (;;) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    String name = event.context().toString();
                    if (name.endsWith(".log")) {
                        continue; // ログファイルは追記されるので無視
                    }
Logger.debugWithThread(name + " " + kind + " count=" + event.count());
                    long delay = 1000L;
                    if (key == nicoCacheFolder && name.equals(jarName)) {
                        // jarが置き換わるまでしばらく待つ
                        // 以降もjarから読み込みが発生するような処理はしない
                        Thread.sleep(2000L);
                        if (jarExitStatus == 0) {
                            break; // 終了しないなら無視して次の監視へ
                        }
                        Logger.warning(jarName +
                                " is modified, going to system exit...");
                        System.exit(jarExitStatus);
                    } else if (key == nlFiltersFolder
                            || key == systemNlFiltersFolder) {
                        name = nlFilter_usr;
                        delay = 5000L;
                    } else if (key == corsLiarFolder) {
                        name = corsLiar;
                        delay = 5000L;
                    }
                    fireEvent(name, delay);
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Logger.debugWithThread(e);
        } finally {
            if (nicoCacheFolder != null) {
                nicoCacheFolder.cancel();
            }
            if (nlFiltersFolder != null) {
                nlFiltersFolder.cancel();
            }
            if (systemNlFiltersFolder != null
                    && systemNlFiltersFolder != nlFiltersFolder) {
                systemNlFiltersFolder.cancel();
            }
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    Logger.debugWithThread(e);
                }
            }
            executor.shutdownNow();
        }
    }

    private WatchKey registerWatcher(Path dir) throws IOException {
        return dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    private void fireEvent(String name, long delay) {
        Task task = Task.getTask(name);
        if (task != null) {
            ScheduledFuture<?> future = futures.remove(name);
            if (future != null) {
                future.cancel(false);
            }
            // 連続したイベントをキャンセル出来るように遅延実行
            future = executor.schedule(task, delay, TimeUnit.MILLISECONDS);

            futures.put(name, future);
        }
    }

}
