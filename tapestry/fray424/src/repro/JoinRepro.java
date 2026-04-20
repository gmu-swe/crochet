package repro;

import java.util.concurrent.CountDownLatch;

public class JoinRepro {
    public static void main(String[] args) throws InterruptedException {
        CountDownLatch joinerParked = new CountDownLatch(1);
        CountDownLatch workerRelease = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                // Signal that we're alive, then wait to be released.
                // This ensures the joiner calls wait() on a live thread.
                joinerParked.countDown();
                workerRelease.await();
            } catch (InterruptedException ignored) {}
            // exits here — JVM fires notify_all_at_thread_exit
        }, "exits-after-signal");

        Thread joiner = new Thread(() -> {
            try {
                joinerParked.await();   // wait until worker is live and blocking
                worker.join();          // now join() → Object.wait() on a live thread
            } catch (InterruptedException ignored) {}
        }, "joiner");

        worker.start();
        joiner.start();

        joinerParked.await();       // ensure worker is running before releasing
        workerRelease.countDown();  // let worker exit → native notify_all_at_thread_exit

        joiner.join();
    }
}
