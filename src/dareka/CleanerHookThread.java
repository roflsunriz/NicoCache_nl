package dareka;

/**
 * Thread for shutdown hook.
 */
class CleanerHookThread extends Thread {
    private final Thread joinedThread;

    public CleanerHookThread(Thread joinedThread) {
        super("CleanerHook");

        this.joinedThread = joinedThread;
    }

    @Override
    public void run() {
    	if (Main.isDone()) { // [nl]
    		return;
    	}
        Main.stop();

        try {
            joinedThread.join(60000);
        } catch (InterruptedException e) {
            // do not wait too long
        }
    }
}
