package dareka.updater;

/** Immutable result shown for one independently checkable dependency. */
final class DependencyStatus {
    final String id;
    final String displayName;
    final String installedVersion;
    final String latestVersion;
    final String message;
    final boolean checked;
    final boolean updateAvailable;
    final boolean installable;

    DependencyStatus(String id, String displayName, String installedVersion,
            String latestVersion, String message, boolean checked,
            boolean updateAvailable, boolean installable) {
        this.id = id;
        this.displayName = displayName;
        this.installedVersion = installedVersion;
        this.latestVersion = latestVersion;
        this.message = message == null ? "" : message;
        this.checked = checked;
        this.updateAvailable = updateAvailable;
        this.installable = installable;
    }

    static DependencyStatus failure(String id, String displayName,
            String message) {
        return new DependencyStatus(id, displayName, "不明", "不明", message,
                true, false, false);
    }

    boolean canInstall() {
        return checked && updateAvailable && installable;
    }

    String installedLabel() {
        return installedVersion == null || installedVersion.isBlank()
                ? "未導入" : installedVersion;
    }

    String latestLabel() {
        return latestVersion == null || latestVersion.isBlank()
                ? "不明" : latestVersion;
    }
}
