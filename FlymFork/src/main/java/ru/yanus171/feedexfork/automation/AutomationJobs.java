package ru.yanus171.feedexfork.automation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.yanus171.feedexfork.utils.StateZip;

/**
 * HandyRss: the jobs the data door has started, and the flag each of them watches to stop.
 *
 * What this owns is the mapping from the id a caller was handed to a cancellation it can act on,
 * which must outlive the binder call that created it and be reachable from a service that never saw
 * the caller.
 *
 * The flag is a {@link StateZip.Canceller} rather than a bare boolean so it drops straight into
 * {@code StateZip.write}, which POLLS it at every category and every file boundary — never a thread
 * interrupt, so a cancelled archive is never half a file.
 */
public class AutomationJobs {

    private static final ConcurrentHashMap<String, StateZip.Canceller> sJobs = new ConcurrentHashMap<>();

    public static String begin() {
        final String id = UUID.randomUUID().toString();
        sJobs.put( id, new StateZip.Canceller() );
        return id;
    }

    public static StateZip.Canceller canceller(String jobId) {
        return jobId == null ? null : sJobs.get( jobId );
    }

    /**
     * Ask a job to stop. A no-op for an id that is finished or was never real.
     *
     * Deliberately silent: a cancel arriving after the work completed is the normal race, not an
     * error, and answering it as one would make every well-behaved caller look broken.
     */
    public static void cancel(String jobId) {
        final StateZip.Canceller c = canceller( jobId );
        if ( c != null )
            c.cancel();
    }

    public static boolean isCancelled(String jobId) {
        final StateZip.Canceller c = canceller( jobId );
        return c != null && c.isCancelled();
    }

    public static void finish(String jobId) {
        if ( jobId != null )
            sJobs.remove( jobId );
    }
}
