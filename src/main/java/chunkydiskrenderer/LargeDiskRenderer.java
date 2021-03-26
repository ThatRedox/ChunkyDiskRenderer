package chunkydiskrenderer;

import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.math.Ray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

public class LargeDiskRenderer extends Thread {
    // Let up to 16 million rays be waiting for a trace
    private static final int MAX_RAYS_BUFFER = 1<<24;

    private final ArrayList<CacheRay> waitQueue = new ArrayList<>();
    private ArrayList<CacheRay> processQueue = new ArrayList<>();

    private final ReentrantLock traceLock = new ReentrantLock();
    private final Object traceDoneMonitor = new Object();

    public LargeDiskRendererTracer tracer;

    // A minimal class representing a ray to be traced
    public static class CacheRay {
        int depth;
        int x;
        int y;
        float distance;
        Float3 normal;
        Float3 origin;
        Float3 direction;

        Float3 color;
        Float3 emittance;

        CacheRay prev;

        public static CacheRay create(int depth, int x, int y, Float3 origin, Float3 direction) {
            CacheRay ray = new CacheRay();
            ray.depth = depth;
            ray.x = x;
            ray.y = y;
            ray.distance = 0;
            ray.normal = Float3.create(0, 0, 0);
            ray.origin = Float3.copy(origin);
            ray.direction = Float3.copy(direction);
            ray.color = Float3.create(0, 0, 0);
            ray.emittance = Float3.create(0, 0, 0);
            ray.prev = null;
            return ray;
        }

        public static CacheRay copy(CacheRay other) {
            CacheRay ray = new CacheRay();
            ray.depth = other.depth+1;
            ray.x = other.x;
            ray.y = other.y;
            ray.normal = Float3.create(0, 0, 0);
            ray.origin = Float3.copy(other.origin);
            ray.direction = Float3.copy(other.direction);
            ray.color = Float3.create(0, 0, 0);
            ray.emittance = Float3.create(0, 0, 0);
            ray.prev = other;
            return ray;
        }

        public static Ray toRay(CacheRay ray) {
            Ray out = new Ray();
            out.depth = ray.depth;
            out.distance = ray.distance;
            out.o.set(ray.origin.x, ray.origin.y, ray.origin.z);
            out.d.set(ray.direction.x, ray.direction.y, ray.direction.z);
            out.n.set(ray.normal.x, ray.normal.y, ray.normal.z);
            return out;
        }
    }

    public static class Float3 {
        public float x;
        public float y;
        public float z;

        public static Float3 create(float x, float y, float z) {
            Float3 o = new Float3();
            o.x = x;
            o.y = y;
            o.z = z;
            return o;
        }

        public static Float3 copy(Float3 other) {
            Float3 o = new Float3();
            o.x = other.x;
            o.y = other.y;
            o.z = other.z;
            return o;
        }
    }

    public LargeDiskRenderer(Scene scene) {
        if (scene.getWorldOctree().getImplementation() instanceof LargeDiskOctree) {
            LargeDiskOctree octree = (LargeDiskOctree) scene.getWorldOctree().getImplementation();
            octree.cleanup();
            tracer = new LargeDiskRendererTracer(octree.octrees);
            tracer.start();
            tracer.scene = scene;
            tracer.palette = scene.getPalette();
        }
    }

    public boolean doneTracing() {
        return waitQueue.size() == 0 && traceLock.tryLock();
    }

    public void addRay(CacheRay ray) throws InterruptedException {
        synchronized (traceDoneMonitor) {
            while (waitQueue.size() > MAX_RAYS_BUFFER) {
                traceDoneMonitor.wait();
            }
        }

        waitQueue.add(ray);

        synchronized (waitQueue) {
            waitQueue.notifyAll();
        }
    }

    public synchronized Collection<CacheRay> getProcessRays() {
        traceLock.lock();
        Collection<CacheRay> process = processQueue;
        processQueue = new ArrayList<>();
        traceLock.unlock();

        return process;
    }

    @Override
    public void run() {
        try {
            while (!interrupted()) {
                synchronized (waitQueue) {
                    while (waitQueue.size() == 0) {
                        waitQueue.wait();
                    }
                }

                // Start tracing rays
                traceLock.lock();
                tracer.traceRays(processQueue, waitQueue);
                traceLock.unlock();

                synchronized (traceDoneMonitor) {
                    traceDoneMonitor.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            // Stopped
        }
    }
}
