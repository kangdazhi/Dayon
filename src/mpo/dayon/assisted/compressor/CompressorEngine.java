package mpo.dayon.assisted.compressor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import mpo.dayon.assisted.capture.CaptureEngineListener;
import mpo.dayon.common.buffer.MemByteBuffer;
import mpo.dayon.common.capture.Capture;
import mpo.dayon.common.concurrent.DefaultThreadFactoryEx;
import mpo.dayon.common.concurrent.Executable;
import mpo.dayon.common.configuration.ReConfigurable;
import mpo.dayon.common.event.Listeners;
import mpo.dayon.common.log.Log;
import mpo.dayon.common.squeeze.CompressionMethod;
import mpo.dayon.common.squeeze.Compressor;
import mpo.dayon.common.squeeze.NullTileCache;
import mpo.dayon.common.squeeze.RegularTileCache;
import mpo.dayon.common.squeeze.TileCache;

public class CompressorEngine implements ReConfigurable<CompressorEngineConfiguration>, CaptureEngineListener {
	private final Listeners<CompressorEngineListener> listeners = new Listeners<>(CompressorEngineListener.class);

	private ThreadPoolExecutor executor;

	private TileCache cache;

	private final Object reconfigurationLOCK = new Object();

	private CompressorEngineConfiguration configuration;

	private boolean reconfigured;

	public CompressorEngine() {
	}

	public void configure(CompressorEngineConfiguration configuration) {
		synchronized (reconfigurationLOCK) {
			this.configuration = configuration;
			this.reconfigured = true;
		}
	}

	public void reconfigure(CompressorEngineConfiguration configuration) {
		configure(configuration);
	}

	public void addListener(CompressorEngineListener listener) {
		listeners.add(listener);
	}

	public void removeListener(CompressorEngineListener listener) {
		listeners.remove(listener);
	}

	public void start(int queueSize) {
		// THREAD = 1
		//
		// The parallel processing is within the compressor itself - here we
		// want
		// to ensure a certain order of processing - if need more than one
		// thread
		// then have a look how the compressed data are sent over the network
		// (!)

		// QUEUESIZE = 1
		//
		// Do we need more than one here ?
		//
		// - queue full because we could not compress the last capture when a
		// new
		// one is available => too many captures (!)
		//
		// - we could not send the last compressed capture over the network as
		// the
		// network queue is full => too many capture (!)

		executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(queueSize));

		executor.setThreadFactory(new DefaultThreadFactoryEx("CompressorEngine"));

		executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
			public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
				if (!executor.isShutdown()) {
					final List<Runnable> pendings = new ArrayList<>();

					// pendings : oldest first (!)
					executor.getQueue().drainTo(pendings);

					final MyExecutable newer = (MyExecutable) runnable;

					if (pendings.size() > 0) {
						final Capture[] cpendings = new Capture[pendings.size()];

						int pos = 0;

						for (int idx = pendings.size() - 1; idx > -1; idx--) {
							cpendings[pos++] = ((MyExecutable) pendings.get(idx)).capture;
						}

						newer.capture.mergeDirtyTiles(cpendings);
					}

					executor.execute(newer);
				}
			}
		});
	}

	/**
	 * Must not block.
	 * <p/>
	 * Each capture is posted in the right order (according to its capture-id)
	 * from a SINGLE thread. Have a look to the rejection execution handler =>
	 * between the poll() and re-execute() another thread must not execute
	 * during the rejection processing to keep the right order of the captures
	 * in the queue.
	 * <p/>
	 * Note that the current implementation should never block and never throw
	 * any rejected exception.
	 */
	public void onCaptured(Capture capture) {
		executor.execute(new MyExecutable(capture));
	}

	public void onRawCaptured(int id, byte[] grays) {
	}

	private class MyExecutable extends Executable {
		private final Capture capture;

		public MyExecutable(Capture capture) {
			super(executor);

			this.capture = capture;
		}

		protected void execute() throws Exception {
			try {
				final CompressorEngineConfiguration xconfiguration;
				final boolean xreconfigured;

				synchronized (reconfigurationLOCK) {
					xconfiguration = configuration;
					xreconfigured = reconfigured;

					if (reconfigured) {
						cache = xconfiguration.useCache() ? new RegularTileCache(xconfiguration.getCacheMaxSize(), xconfiguration.getCachePurgeSize())
								: new NullTileCache();

						reconfigured = false;
					}
				}

				if (xreconfigured) {
					Log.info("Compressor engine has been reconfigured [tile:" + capture.getId() + "] " + xconfiguration);
				}

				final Compressor compressor = Compressor.get(xconfiguration.getMethod());

				final MemByteBuffer compressed = compressor.compress(cache, capture);

				// Possibly blocking - no problem as we'll replace (and merge)
				// in our queue
				// the oldest capture (if any) until we can compress it and send
				// it to the next
				// stage of processing.

				if (!xreconfigured) {
					fireOnCompressed(capture, compressor.getMethod(), null, compressed);
				} else {
					// we have to send the whole configuration => de-compressor
					// synchronization (!)
					fireOnCompressed(capture, compressor.getMethod(), xconfiguration, compressed);
				}
			} finally {
				cache.onCaptureProcessed();
			}
		}
	}

	private void fireOnCompressed(Capture capture, CompressionMethod compressionMethod, @Nullable CompressorEngineConfiguration compressionConfiguration,
			MemByteBuffer compressed) {
		final CompressorEngineListener[] xlisteners = listeners.getListeners();

		if (xlisteners == null) {
			return;
		}

		for (final CompressorEngineListener xlistener : xlisteners) {
			xlistener.onCompressed(capture, compressionMethod, compressionConfiguration, compressed);
		}
	}

}
